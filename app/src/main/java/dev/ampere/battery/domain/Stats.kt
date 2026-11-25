package dev.ampere.battery.domain

import dev.ampere.battery.core.Fmt
import dev.ampere.battery.data.Day
import dev.ampere.battery.data.Session
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

data class Bracket(val from: Int, val to: Int) {
    val label: String get() = "$from → $to"
}

/** How often and how fast one stretch of the battery has been charged. */
data class BracketStat(
    val bracket: Bracket,
    val count: Int,
    val bestSec: Int?,
    val avgSec: Int?,
    val bestSession: Long?,
    val bestTs: Long?,
)

object Brackets {
    val standard = listOf(
        Bracket(0, 100), Bracket(0, 50), Bracket(10, 100), Bracket(20, 80),
        Bracket(40, 100), Bracket(40, 80), Bracket(50, 100), Bracket(80, 100),
    )

    fun stat(b: Bracket, charges: List<Pair<Session, IntArray>>): BracketStat {
        var n = 0
        var best = Int.MAX_VALUE
        var sum = 0L
        var who: Session? = null
        for ((s, m) in charges) {
            if (s.endLevel < b.to || s.startLevel > b.from + 1) continue
            val t = Marks.time(m, b.from, b.to) ?: continue
            n++
            sum += t
            if (t < best) {
                best = t
                who = s
            }
        }
        return BracketStat(b, n, best.takeIf { n > 0 }, if (n > 0) (sum / n).toInt() else null, who?.id, who?.startTs)
    }
}

data class Stats(
    val charges: List<Session> = emptyList(),
    val drains: List<Session> = emptyList(),
    val days: List<Day> = emptyList(),
    val marks: Map<Long, IntArray> = emptyMap(),
    val chargeCount: Int = 0,
    val cycles: Float = 0f,
    val energyMwh: Float = 0f,
    val ahIn: Float = 0f,
    val chargingMs: Long = 0,
    val onBatteryMs: Long = 0,
    val peakMw: Int = 0,
    val peakMwTs: Long = 0,
    val maxTempT: Int? = null,
    val brackets: List<BracketStat> = emptyList(),
    val toFull: Int = 0,
    val fromUnder10: Int = 0,
    val gentle: Int = 0,
    val zeroTo100: Int = 0,
    val heat40Ms: Long = 0,
    val heat43Ms: Long = 0,
    val grades: Map<String, Int> = emptyMap(),
    val avgScore: Float? = null,
    val matrix: IntArray = IntArray(100),
    val hours: IntArray = IntArray(24),
    val plugs: Map<Int, Int> = emptyMap(),
    val avgUnplug: Float? = null,
    val avgPlugIn: Float? = null,
    val bestPctPerMin: Float? = null,
    val drainOnPctH: Float? = null,
    val drainOffPctH: Float? = null,
    val bestScreenOnMs: Long = 0,
    val longestDrainMs: Long = 0,
    val longestChargeMs: Long = 0,
    val nightCharges: Int = 0,
    val wireless: Int = 0,
    val daysTracked: Int = 0,
    val chargesPerDay: Float? = null,
    val noHeatStreak: Int = 0,
    val aStreak: Int = 0,
    val biggestGain: Int = 0,
) {
    fun decoded(): List<Pair<Session, IntArray>> = charges.map { it to (marks[it.id] ?: IntArray(101) { -1 }) }

    fun bracket(b: Bracket): BracketStat = Brackets.stat(b, decoded())

    /** Personal best run for a bracket, as marks, for live split comparison. */
    fun bestMarks(b: Bracket): IntArray? = bracket(b).bestSession?.let { marks[it] }
}

object StatsCalc {
    fun of(sessions: List<Session>, days: List<Day>): Stats {
        val charges = sessions.filter { it.isCharge && it.gained > 0 }
        val drains = sessions.filter { !it.isCharge && it.startLevel > it.endLevel }
        val marks = charges.associate { it.id to Marks.decode(it.marks) }
        val pairs = charges.map { it to marks.getValue(it.id) }
        val zone = ZoneId.systemDefault()
        val matrix = IntArray(100)
        val hours = IntArray(24)
        var night = 0
        for (s in charges) {
            matrix[(s.startLevel / 10).coerceAtMost(9) * 10 + ((s.endLevel - 1) / 10).coerceIn(0, 9)]++
            val h = Instant.ofEpochMilli(s.startTs).atZone(zone).hour
            hours[h]++
            if ((h >= 22 || h < 5) && s.durationMs > 3 * 3_600_000L) night++
        }
        val graded = charges.filter { it.score >= 0 }
        val real = charges.filter { it.gained >= 5 && it.durationMs > 60_000 }
        val top = charges.maxByOrNull { it.peakMw }
        // Pooled over all drains so a five-minute session cannot swing the average.
        val longDrains = drains.filter { it.durationMs > 10 * 60_000L }
        val onH = longDrains.sumOf { it.screenOnMs } / 3_600_000f
        val offH = longDrains.sumOf { it.durationMs - it.screenOnMs } / 3_600_000f
        fun share(s: Session): Pair<Float, Float> {
            val tot = s.uahOn + s.uahOff
            val drop = (s.startLevel - s.endLevel).toFloat()
            return if (tot <= 0f) 0f to 0f else drop * s.uahOn / tot to drop * s.uahOff / tot
        }
        val dOn = longDrains.sumOf { share(it).first.toDouble() }.toFloat()
        val dOff = longDrains.sumOf { share(it).second.toDouble() }.toFloat()
        val tracked = days.filter { it.trackedMs > 3_600_000L }
        return Stats(
            charges = charges, drains = drains, days = days, marks = marks,
            chargeCount = charges.size,
            cycles = charges.sumOf { it.gained } / 100f,
            energyMwh = charges.sumOf { it.energyMwh.toDouble() }.toFloat(),
            ahIn = charges.sumOf { it.chargeUah.toDouble() }.toFloat() / 1_000_000f,
            chargingMs = charges.sumOf { it.durationMs },
            onBatteryMs = drains.sumOf { it.durationMs },
            peakMw = top?.peakMw ?: 0,
            peakMwTs = top?.startTs ?: 0,
            maxTempT = days.filter { it.maxTempT != Int.MIN_VALUE }.maxOfOrNull { it.maxTempT },
            brackets = Brackets.standard.map { Brackets.stat(it, pairs) },
            toFull = charges.count { it.endLevel >= 100 },
            fromUnder10 = charges.count { it.startLevel < 10 && it.gained >= 5 },
            gentle = charges.count { it.startLevel >= 20 && it.endLevel <= 80 && it.gained >= 10 },
            zeroTo100 = charges.count { it.startLevel <= 2 && it.endLevel >= 100 },
            heat40Ms = days.sumOf { it.heat40Ms },
            heat43Ms = days.sumOf { it.heat43Ms },
            grades = graded.groupingBy { Grader.letter(it.score) }.eachCount(),
            avgScore = if (graded.isEmpty()) null else graded.sumOf { it.score } / graded.size.toFloat(),
            matrix = matrix, hours = hours,
            plugs = charges.groupingBy { it.plug }.eachCount(),
            avgUnplug = if (real.isEmpty()) null else real.sumOf { it.endLevel } / real.size.toFloat(),
            avgPlugIn = if (real.isEmpty()) null else real.sumOf { it.startLevel } / real.size.toFloat(),
            bestPctPerMin = pairs.mapNotNull { (_, m) -> fastest10(m) }.maxOrNull(),
            drainOnPctH = if (onH > 0.3f && dOn > 0) dOn / onH else null,
            drainOffPctH = if (offH > 1f && dOff > 0) dOff / offH else null,
            bestScreenOnMs = drains.maxOfOrNull { it.screenOnMs } ?: 0,
            longestDrainMs = drains.maxOfOrNull { it.durationMs } ?: 0,
            longestChargeMs = charges.maxOfOrNull { it.durationMs } ?: 0,
            nightCharges = night,
            wireless = charges.count { it.plug == 4 },
            daysTracked = tracked.size,
            chargesPerDay = if (tracked.isEmpty()) null else tracked.sumOf { it.charges } / tracked.size.toFloat(),
            noHeatStreak = streak(tracked) { it.maxTempT < 400 },
            aStreak = graded.filter { it.gained >= 10 }.sortedBy { it.startTs }.let { run(it.map { s -> s.score >= 90 }) },
            biggestGain = charges.maxOfOrNull { it.gained } ?: 0,
        )
    }

    /** Best %/min over any ten-percent stretch. */
    private fun fastest10(m: IntArray): Float? {
        var best = 0f
        for (a in 0..90) Marks.time(m, a, a + 10)?.let { best = max(best, 600f / it) }
        return best.takeIf { it > 0 }
    }

    private fun run(v: List<Boolean>): Int {
        var b = 0
        var c = 0
        for (x in v) {
            c = if (x) c + 1 else 0
            b = max(b, c)
        }
        return b
    }

    fun streak(days: List<Day>, ok: (Day) -> Boolean): Int {
        var best = 0
        var c = 0
        var prev = Long.MIN_VALUE
        for (d in days.sortedBy { it.day }) {
            c = if (!ok(d)) 0 else if (d.day == prev + 1) c + 1 else 1
            prev = d.day
            best = max(best, c)
        }
        return best
    }
}

/** Plain-language observations. Only said when the data supports them. */
data class Insight(val title: String, val text: String, val good: Boolean?)

object Insights {
    fun of(s: Stats, h: Health): List<Insight> = buildList {
        s.avgUnplug?.let {
            if (s.chargeCount >= 5) add(
                if (it <= 85) Insight("Kind to the cell", "You unplug at ${it.toInt()}% on average, which keeps voltage stress low.", true)
                else Insight("You charge high", "Average unplug is ${it.toInt()}%. Stopping near 80 to 85% slows wear.", false),
            )
        }
        val week = s.days.takeLast(7)
        val heat = week.sumOf { it.heat40Ms }
        if (week.size >= 3) add(
            if (heat < 10 * 60_000L) Insight("Cool week", "Almost no time above 40°C in the last seven tracked days.", true)
            else Insight("Heat is the wear driver", "${Fmt.duration(heat)} above 40°C this week. Heat ages cells faster than anything else.", false),
        )
        val on = s.drainOnPctH
        val off = s.drainOffPctH
        if (on != null) add(Insight("Screen-on drain", "${Fmt.dec(on, 1)}% per hour, so a full charge is about ${Fmt.dec(100 / on, 1)} h of screen time.", null))
        if (off != null) add(
            if (off <= 1.2f) Insight("Sleeps well", "Only ${Fmt.dec(off, 1)}% per hour with the screen off.", true)
            else Insight("Restless standby", "${Fmt.dec(off, 1)}% per hour with the screen off. Something may be keeping it awake.", false),
        )
        val slow = s.charges.take(15).count { it.plug == 1 && it.avgMw in 1..4000 && it.startLevel < 70 }
        if (slow >= 3) add(Insight("Slow charger in rotation", "$slow of your last charges ran under 4 W. Swap that cable or adapter.", false))
        if (s.nightCharges >= 5) add(Insight("Overnight charger", "${s.nightCharges} long night charges. Adaptive charging in your phone settings helps here.", null))
        h.trendPerMonth?.let {
            if (abs(it) < 3) add(Insight("Wear rate", "Capacity is moving ${Fmt.dec(it, 2)}% per month.", it > -0.6f))
        }
    }
}
