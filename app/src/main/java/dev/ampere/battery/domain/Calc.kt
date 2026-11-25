package dev.ampere.battery.domain

import android.os.BatteryManager
import dev.ampere.battery.data.Estimate
import dev.ampere.battery.data.Session
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * CURRENT_NOW has no reliable unit or sign across phones. This learns both.
 * Output convention everywhere else: positive = into the battery.
 */
class CurrentCalibrator(var scale: Int = 0, var sign: Int = 0) {
    private var smallVotes = 0
    private var signVotes = 0

    fun normalise(raw: Int?, plugged: Boolean, charging: Boolean, level: Int): Int? {
        if (raw == null || raw == 0) return raw
        val mag = abs(raw.toLong())
        val busy = if (plugged) charging && level < 90 else true
        when {
            // 20 A does not exist in a phone, so a number this big can only be microamps.
            mag >= 20_000 -> scale = 1
            scale == 0 && busy -> if (++smallVotes >= 4) scale = 1000
        }
        if (scale == 0) return null
        val ua = (mag * scale).coerceAtMost(25_000_000).toInt()
        if (!plugged) return -ua
        if (charging) {
            signVotes = (signVotes + if (raw > 0) 1 else -1).coerceIn(-60, 60)
            if (sign == 0) sign = if (raw > 0) 1 else -1
            else if (abs(signVotes) >= 30) sign = if (signVotes > 0) 1 else -1
        }
        val s = if (sign == 0) 1 else sign
        return if (raw > 0) ua * s else -ua * s
    }
}

/** First time each percent was reached in a charge, as seconds from the start. */
object Marks {
    fun encode(m: IntArray): String =
        m.withIndex().filter { it.value >= 0 }.joinToString(";") { "${it.index}:${it.value}" }

    fun decode(s: String): IntArray {
        val out = IntArray(101) { -1 }
        if (s.isEmpty()) return out
        for (p in s.split(';')) {
            val c = p.indexOf(':')
            if (c <= 0) continue
            val l = p.substring(0, c).toIntOrNull() ?: continue
            if (l in 0..100) out[l] = p.substring(c + 1).toIntOrNull() ?: continue
        }
        return out
    }

    /** Seconds for a→b, or null if this charge did not cover it. Allows one skipped percent at each end. */
    fun time(m: IntArray, a: Int, b: Int): Int? {
        val ta = at(m, a) ?: return null
        val tb = at(m, b) ?: return null
        // A hole means nobody watched that part (app killed, gauge jump), so it cannot be a record.
        var hole = 0
        for (l in a..b) {
            hole = if (m[l] < 0) hole + 1 else 0
            if (hole > MAX_HOLE) return null
        }
        // Even 240 W phones need about 5 s per percent.
        return (tb - ta).takeIf { it >= (b - a) * MIN_SEC_PER_PCT }
    }

    private const val MAX_HOLE = 2
    private const val MIN_SEC_PER_PCT = 3

    private fun at(m: IntArray, l: Int): Int? = when {
        m[l] >= 0 -> m[l]
        l < 100 && m[l + 1] >= 0 -> m[l + 1]
        else -> null
    }
}

/** How charging slows down as a lithium cell fills up, and what that does to the clock. */
object ChargeModel {
    private val xs = floatArrayOf(0f, 4f, 50f, 60f, 70f, 80f, 90f, 95f, 99f, 100f)
    private val ys = floatArrayOf(0.8f, 1f, 1f, 0.88f, 0.72f, 0.52f, 0.30f, 0.20f, 0.11f, 0.09f)

    fun rel(level: Float): Float {
        val l = level.coerceIn(0f, 100f)
        for (i in 1 until xs.size) if (l <= xs[i]) {
            val t = (l - xs[i - 1]) / (xs[i] - xs[i - 1])
            return ys[i - 1] + (ys[i] - ys[i - 1]) * t
        }
        return ys.last()
    }

    /**
     * Time from [level] to [target] if charging carries on as it is now.
     * [learned] is this phone's own seconds-per-percent (0 where unknown), scaled to the present speed.
     */
    fun eta(level: Int, target: Int, uaNow: Int, fullUah: Int, learned: FloatArray?): Long? {
        if (level >= target) return 0
        if (uaNow < 20_000 || fullUah <= 0) return null
        val nowSec = 3600f * (fullUah / 100f) / uaNow
        val ref = learned?.getOrNull(level)?.takeIf { it > 0 }
        val k = if (ref != null) (nowSec / ref).coerceIn(0.35f, 4f) else 0f
        val relNow = rel(level.toFloat())
        var total = 0f
        for (l in level until target) {
            val own = learned?.getOrNull(l)?.takeIf { it > 0 && ref != null }
            total += if (own != null) own * k else nowSec * relNow / rel(l + 0.5f)
        }
        return (total * 1000).toLong().coerceIn(60_000, 24 * 3_600_000L)
    }

    /** "What if I plug into an N watt charger": 88 % reaches the cell, capped by what the phone accepts. */
    fun whatIf(level: Int, target: Int, chargerW: Float, phoneMaxW: Float?, fullUah: Int): Long? {
        if (level >= target) return 0
        val w = (min(chargerW, phoneMaxW ?: chargerW)) * 0.88f
        val ua = (w / 3.9f * 1_000_000f)
        var sec = 0f
        for (l in level until target) sec += 3600f * (fullUah / 100f) / (ua * rel(l + 0.5f))
        return (sec * 1000).toLong()
    }

    /** Median seconds-per-percent from past charges of the same plug type. */
    fun learn(charges: List<Session>, plug: Int): FloatArray? {
        val same = charges.filter { it.plug == plug && it.gained >= 10 }.take(12)
        if (same.isEmpty()) return null
        val buckets = Array(100) { ArrayList<Float>() }
        for (s in same) {
            val m = Marks.decode(s.marks)
            var prev = -1
            for (l in 0..100) if (m[l] >= 0) {
                if (prev >= 0 && l - prev == 1 && prev > s.startLevel) {
                    val d = (m[l] - m[prev]).toFloat()
                    if (d in 3f..3600f) buckets[prev].add(d)
                }
                prev = l
            }
        }
        val out = FloatArray(100) { i -> buckets[i].sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] } }
        return if (out.count { it > 0 } >= 8) out else null
    }
}

object Capacity {
    data class Result(val uah: Int, val weight: Float, val method: String)

    /** Full capacity from one charge: charge that went in ÷ share of the battery it filled. */
    fun fromCharge(
        startLevel: Int, endLevel: Int, startCc: Int?, endCc: Int?, integratedUah: Float, coverage: Float,
        minTempT: Int, maxTempT: Int, avgTempT: Int, designUah: Int?,
    ): Result? {
        val span = endLevel - startLevel
        if (span < 25) return null
        if (minTempT < 100 || maxTempT > 450 || maxTempT - minTempT > 160) return null
        val ok = { v: Float -> if (designUah != null) v / designUah in 0.4f..1.25f else v in 500_000f..30_000_000f }
        val counter = if (startCc != null && endCc != null && endCc > startCc) (endCc - startCc) * 100f / span else null
        val coulomb = if (coverage >= 0.9f && integratedUah > 0) integratedUah * 100f / span else null
        val c = counter?.takeIf(ok)
        val q = coulomb?.takeIf(ok)
        // Some gauges just multiply percent by the design figure. That would always say 100 % health.
        // Without current to check against, a figure this close to design proves nothing, so it is not used.
        val mirrored = c != null && designUah != null && abs(c / designUah - 1) < 0.004f && (q == null || abs(q / c - 1) > 0.04f)
        val t = avgTempT / 10f
        val tempFactor = when {
            t < 20 -> 0.5f + (t - 10) / 20f
            t > 34 -> (1f - (t - 34) / 22f)
            else -> 1f
        }.coerceIn(0.4f, 1f)
        val w = ((span - 20) / 60f).coerceIn(0.1f, 1f) * tempFactor
        return when {
            c != null && !mirrored -> Result(c.roundToInt(), w, "charge counter")
            q != null -> Result(q.roundToInt(), w * 0.9f, "coulomb counting")
            else -> null
        }
    }

    fun weightedMedian(list: List<Pair<Int, Float>>): Int? {
        if (list.isEmpty()) return null
        val s = list.sortedBy { it.first }
        val half = s.sumOf { it.second.toDouble() } / 2
        var acc = 0.0
        for ((v, w) in s) {
            acc += w
            if (acc >= half) return v
        }
        return s.last().first
    }
}

enum class Confidence(val label: String) { MEASURED("Measured"), PROVISIONAL("Early reading"), QUICK("Quick read"), NONE("Learning") }

data class Health(
    val designMah: Int?,
    val fullMah: Int?,
    val pct: Float?,
    val confidence: Confidence,
    val count: Int,
    val minMah: Int?,
    val maxMah: Int?,
    val method: String,
    val systemSoh: Int?,
    /** -% per 30 days, only with enough history. */
    val trendPerMonth: Float?,
    val eightyAt: Long?,
)

object HealthCalc {
    fun of(designMah: Int?, est: List<Estimate>, ccUah: Int?, level: Int?, soh: Int?): Health {
        val recent = est.sortedByDescending { it.ts }.take(10)
        val med = Capacity.weightedMedian(recent.map { it.uah to it.weight })
        // Below 20 % the percent rounding alone would swing this by several points.
        val quick = if (ccUah != null && level != null && level >= 20) (ccUah * 100L / level).toInt() else null
        val quickOk = quick?.takeIf { designMah == null || it / 1000f / designMah in 0.4f..1.25f }
        val full = med ?: quickOk
        val pct = if (full != null && designMah != null) (full / 10f / designMah).coerceIn(0f, 110f) else null
        val trend = trend(est, designMah)
        return Health(
            designMah, full?.let { (it / 1000f).roundToInt() }, pct,
            when {
                med != null && recent.size >= 3 -> Confidence.MEASURED
                med != null -> Confidence.PROVISIONAL
                quickOk != null -> Confidence.QUICK
                else -> Confidence.NONE
            },
            est.size,
            recent.minOfOrNull { it.uah }?.let { it / 1000 }, recent.maxOfOrNull { it.uah }?.let { it / 1000 },
            when {
                med != null -> recent.groupingBy { it.method }.eachCount().maxByOrNull { it.value }!!.key
                quickOk != null -> "fuel gauge now"
                else -> "none yet"
            },
            soh, trend?.first, trend?.second,
        )
    }

    private fun trend(est: List<Estimate>, designMah: Int?): Pair<Float, Long?>? {
        if (designMah == null || est.size < 6) return null
        val s = est.sortedBy { it.ts }
        val dayMs = 86_400_000.0
        if ((s.last().ts - s.first().ts) / dayMs < 30) return null
        val xs = s.map { (it.ts - s.first().ts) / dayMs }
        val ys = s.map { it.uah / 10.0 / designMah }
        val mx = xs.average()
        val my = ys.average()
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xs.sumOf { (it - mx) * (it - mx) }
        val perMonth = (slope * 30).toFloat()
        val now = my + slope * (xs.last() - mx)
        val eighty = if (slope < -0.002 && now > 80) s.last().ts + ((now - 80) / -slope * dayMs).toLong() else null
        return perMonth to eighty
    }

    fun band(p: Float): Pair<String, String> = when {
        p > 101 -> "Above rated" to "Holds slightly more than its rating. Common on newer batteries."
        p >= 90 -> "Excellent" to "Essentially like new."
        p >= 80 -> "Good" to "Normal wear. Many batteries reach 80% after 500 to 800 cycles."
        p >= 70 -> "Fair" to "Shorter days are expected. A replacement would be noticeable."
        p >= 60 -> "Poor" to "Consider replacing the battery."
        else -> "Very poor" to "Replace the battery. Sudden shutdowns are likely."
    }
}

enum class TempState(val label: String) { COLD("Too cold"), CHILLY("Cold"), NORMAL("Normal"), WARM("Warm"), HOT("Hot"), VERY_HOT("Very hot"), CRITICAL("Critical") }

object Thresholds {
    fun temp(c: Float): TempState = when {
        c < 0 -> TempState.COLD
        c < 10 -> TempState.CHILLY
        c < 35 -> TempState.NORMAL
        c < 40 -> TempState.WARM
        c < 43 -> TempState.HOT
        c <= 45 -> TempState.VERY_HOT
        else -> TempState.CRITICAL
    }

    fun tempCopy(s: TempState) = when (s) {
        TempState.COLD -> "Too cold to charge safely. The phone may refuse to charge."
        TempState.CHILLY -> "Cold. Charging will be slow to protect the cell."
        TempState.NORMAL -> "Right where a battery likes to be."
        TempState.WARM -> "Warm but fine. Common while fast charging."
        TempState.HOT -> "Hot. Long periods here speed up capacity loss."
        TempState.VERY_HOT -> "Very hot. Take the case off and stop heavy apps."
        TempState.CRITICAL -> "Critical. Unplug and let it cool."
    }

    fun chargeClass(mw: Int): String = when {
        mw < 2_500 -> "Trickle"
        mw < 5_000 -> "Slow"
        mw < 10_000 -> "Standard"
        mw < 18_000 -> "Fast"
        mw < 30_000 -> "Very fast"
        else -> "Ultra fast"
    }

    val thermalLabels = listOf("None", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown")
}

enum class Severity { INFO, WARN, BAD }

enum class Warning(val severity: Severity, val title: String, val text: String) {
    EXTREME_TEMP(Severity.BAD, "Battery is dangerously hot", "Unplug it and let it cool before charging again."),
    HIGH_TEMP(Severity.WARN, "Battery is very hot", "Take the case off and stop heavy apps while charging."),
    SEVERE_THROTTLE(Severity.BAD, "Phone is throttling hard", "Performance is cut to bring heat down."),
    THROTTLE(Severity.WARN, "Phone is throttling", "The system is slowing down to manage heat."),
    SLOW_CHARGE(Severity.WARN, "Charging slowly", "Try another cable or charger, and check the port for lint."),
    UNSTABLE(Severity.WARN, "Unstable charging", "Current keeps jumping. The cable or port may be loose."),
    HOLD(Severity.INFO, "Held at 100%", "Sitting full and plugged in for hours adds wear."),
    BAD_FLAG(Severity.BAD, "The phone reports a battery fault", "Have the battery checked."),
}

data class Deduction(val code: String, val points: Int) {
    val label: String
        get() = when (code) {
            "TEMP" -> "Peak temperature above 40°C"
            "HOT43" -> "Time above 43°C"
            "THERMAL" -> "Phone throttled"
            "SLOW" -> "Under 4 W from a wall charger"
            "UNSTABLE" -> "Unstable current"
            "HOLD" -> "Held at 100% for over 30 minutes"
            "DEEP" -> "Started below 10%"
            else -> code
        }
}

object Grader {
    fun grade(s: Session): Pair<Int, List<Deduction>> {
        val d = ArrayList<Deduction>()
        val over = s.peakTempT / 10f - 40f
        if (over > 0) d += Deduction("TEMP", (over * 4).roundToInt().coerceIn(1, 40))
        val hot = s.heat43Ms / 60_000f
        if (hot >= 1) d += Deduction("HOT43", min(25f, hot * 1.5f).roundToInt())
        when (s.thermalMax) {
            0 -> Unit
            1 -> d += Deduction("THERMAL", 2)
            2 -> d += Deduction("THERMAL", 6)
            3 -> d += Deduction("THERMAL", 15)
            else -> d += Deduction("THERMAL", 25)
        }
        // Judge speed only where the phone should be fast; the last stretch is slow by design.
        if (s.plug == BatteryManager.BATTERY_PLUGGED_AC && s.avgMw in 1 until 4_000 && s.startLevel < 70) d += Deduction("SLOW", 12)
        if (s.unstable) d += Deduction("UNSTABLE", 10)
        if (s.holdMs > 30 * 60_000L) d += Deduction("HOLD", 8)
        if (s.startLevel < 10) d += Deduction("DEEP", 5)
        return (100 - d.sumOf { it.points }).coerceIn(0, 100) to d
    }

    fun letter(score: Int): String = when {
        score < 0 -> "–"
        score >= 90 -> "A"
        score >= 78 -> "B"
        score >= 65 -> "C"
        score >= 50 -> "D"
        else -> "F"
    }

    fun encode(d: List<Deduction>) = d.joinToString(";") { "${it.code}:${it.points}" }

    fun decode(s: String): List<Deduction> = s.split(';').mapNotNull { p ->
        p.split(':').takeIf { it.size == 2 }?.let { Deduction(it[0], it[1].toIntOrNull() ?: return@mapNotNull null) }
    }
}

/** Largest-triangle-three-buckets. Keeps peaks, drops bulk. */
object Lttb {
    fun pick(x: LongArray, y: FloatArray, n: Int): IntArray {
        val size = x.size
        if (size <= n || n < 3) return IntArray(size) { it }
        val out = IntArray(n)
        val every = (size - 2).toDouble() / (n - 2)
        var a = 0
        for (i in 0 until n - 2) {
            val s1 = ((i + 1) * every).toInt() + 1
            val e1 = min(((i + 2) * every).toInt() + 1, size)
            var ax = 0.0
            var ay = 0.0
            for (j in s1 until e1) {
                ax += x[j].toDouble()
                ay += y[j]
            }
            val len = max(1, e1 - s1)
            ax /= len
            ay /= len
            val s0 = (i * every).toInt() + 1
            var best = -1.0
            var pick = s0
            for (j in s0 until s1) {
                val area = abs((x[a] - ax) * (y[j] - y[a]) - (x[a] - x[j]).toDouble() * (ay - y[a]))
                if (area > best) {
                    best = area
                    pick = j
                }
            }
            out[i + 1] = pick
            a = pick
        }
        out[n - 1] = size - 1
        return out
    }
}

fun stdOverMean(v: List<Float>): Float {
    if (v.size < 2) return 0f
    val m = v.average()
    if (m == 0.0) return 0f
    return (sqrt(v.sumOf { (it - m) * (it - m) } / v.size) / abs(m)).toFloat()
}
