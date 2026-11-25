package dev.ampere.battery.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings.Global
import androidx.core.content.ContextCompat
import dev.ampere.battery.core.Fmt
import dev.ampere.battery.data.AmpereDao
import dev.ampere.battery.data.Day
import dev.ampere.battery.data.Estimate
import dev.ampere.battery.data.KIND_CHARGE
import dev.ampere.battery.data.KIND_DRAIN
import dev.ampere.battery.data.Prefs
import dev.ampere.battery.data.Sample
import dev.ampere.battery.data.Session
import dev.ampere.battery.data.Settings
import dev.ampere.battery.domain.Capacity
import dev.ampere.battery.domain.ChargeModel
import dev.ampere.battery.domain.Grader
import dev.ampere.battery.domain.Goals
import dev.ampere.battery.domain.Marks
import dev.ampere.battery.domain.Warning
import dev.ampere.battery.domain.CurrentCalibrator
import dev.ampere.battery.domain.stdOverMean
import dev.ampere.battery.hw.BatteryReader
import dev.ampere.battery.hw.DesignResolver
import dev.ampere.battery.hw.RawBattery
import dev.ampere.battery.hw.Units
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class Power { CHARGING, FULL, PLUGGED_IDLE, BATTERY }

/** Everything the UI needs about right now. Nulls mean the phone does not report it. */
data class Live(
    val ready: Boolean = false,
    val ts: Long = 0,
    val level: Int = 0,
    val power: Power = Power.BATTERY,
    val plug: Int = 0,
    val healthFlag: Int = 1,
    val mv: Int? = null,
    val ua: Int? = null,
    val mw: Int? = null,
    /** Display-only averages; stored values are never smoothed. */
    val uaSmooth: Int? = null,
    val mwSmooth: Int? = null,
    val tempC: Float? = null,
    val cc: Int? = null,
    val tech: String? = null,
    val cycleCount: Int? = null,
    val negotiatedMv: Int? = null,
    val negotiatedUa: Int? = null,
    val soh: Int? = null,
    val thermal: Int = 0,
    val headroom: Float? = null,
    val sysEtaMs: Long? = null,
    val etaFullMs: Long? = null,
    val eta80Ms: Long? = null,
    val etaLearned: Boolean = false,
    val leftMs: Long? = null,
    val session: Session? = null,
    val marks: IntArray? = null,
    val warnings: List<Warning> = emptyList(),
    val screenOn: Boolean = true,
    val designMah: Int? = null,
    val raw: RawBattery? = null,
    val histMw: FloatArray = FloatArray(0),
    val histMv: FloatArray = FloatArray(0),
    val histUa: FloatArray = FloatArray(0),
    val histTemp: FloatArray = FloatArray(0),
) {
    val plugged: Boolean get() = plug != 0
    val charging: Boolean get() = power == Power.CHARGING
    val negotiatedMw: Int? get() = Units.powerMw(negotiatedMv, negotiatedUa)
}

/**
 * The one sampling loop in the app. It runs at 1 Hz while a screen is visible and at a slow adaptive
 * rate while only the background service needs it. It never holds a wakelock: battery broadcasts
 * wake it at every percent, which is what the records are built from.
 */
class Engine(
    private val ctx: Context,
    private val reader: BatteryReader,
    private val design: DesignResolver,
    private val dao: AmpereDao,
    private val prefs: Prefs,
    private val settings: StateFlow<Settings>,
    private val scope: CoroutineScope,
) {
    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live

    /** Called after every sample with the previous and new state; the service uses it for alerts. */
    @Volatile
    var onTick: ((Live, Live) -> Unit)? = null

    private val fg = MutableStateFlow(0)
    @Volatile private var service = false
    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val cal = CurrentCalibrator()
    private val boot = runCatching { Global.getInt(ctx.contentResolver, Global.BOOT_COUNT) }.getOrDefault(0)

    @Volatile var measuredFullUah: Int? = null
    /** %/h with the screen on, with it off, and the share of time it is on; from history. */
    @Volatile var typicalDrain: Triple<Float, Float, Float>? = null

    fun attach() {
        fg.update { it + 1 }
        ensure()
        wake.trySend(Unit)
    }

    fun detach() {
        fg.update { max(0, it - 1) }
    }

    fun setService(on: Boolean) {
        service = on
        if (on) ensure()
        wake.trySend(Unit)
    }

    fun poke() {
        wake.trySend(Unit)
    }

    @Synchronized
    private fun ensure() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }.also { j ->
            // A screen can attach while the old loop is on its way out.
            j.invokeOnCompletion { if (fg.value > 0 || service) ensure() }
        }
    }

    @Volatile private var forget = false

    /** After "delete all data": drop in-memory state so nothing old is written back. */
    fun forget() {
        forget = true
        wake.trySend(Unit)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            wake.trySend(Unit)
        }
    }

    private suspend fun loop() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            prefs.flow.first().let {
                if (it.curFingerprint == Build.FINGERPRINT) {
                    cal.scale = it.curScale
                    cal.sign = it.curSign
                }
            }
            restore()
            while (fg.value > 0 || service) {
                if (forget) {
                    forget = false
                    cur = null
                    day = null
                    buf.clear()
                    lastLevel = -1
                    learnedPlug = -1
                    typicalDrain = null
                    measuredFullUah = null
                }
                val before = _live.value
                val now = sample()
                onTick?.invoke(before, now)
                val wait = when {
                    fg.value > 0 -> 1_000L
                    now.thermal >= 3 -> 5_000L
                    now.plugged -> 10_000L
                    now.screenOn -> 30_000L
                    else -> 120_000L
                }
                withTimeoutOrNull(wait) { wake.receive() }
            }
        } finally {
            runCatching { ctx.unregisterReceiver(receiver) }
            flush(true)
        }
    }

    // ---- session state -------------------------------------------------------------------------

    private var cur: Session? = null
    private var marks = IntArray(101) { -1 }
    private var lastElapsed = 0L
    private var lastTs = 0L
    private var lastLevel = -1
    private var lastCc: Int? = null
    private var lastScreen = true
    private var tempSum = 0.0
    private var tempN = 0
    private var estUah = -1f
    private var estCc: Int? = null
    private var flipSince = 0L
    private val buf = ArrayList<Sample>()
    private var lastStored = 0L
    private var lastFlush = 0L
    private var day: Day? = null
    private var unplugAtDay = 0
    private var learned: FloatArray? = null
    private var learnedPlug = -1
    private var etaAt = 0L
    private var etaFull: Long? = null
    private var eta80: Long? = null
    private var emaUa: Float? = null
    private val recentUa = ArrayDeque<Pair<Long, Float>>()
    private var hotSince = 0L
    private var slowSince = 0L
    private val hMw = Ring(60)
    private val hMv = Ring(60)
    private val hUa = Ring(60)
    private val hTemp = Ring(60)

    private suspend fun restore() {
        day = null
        val open = dao.openSession() ?: return
        if (open.bootCount != boot) {
            close(open)
            return
        }
        cur = open
        marks = Marks.decode(open.marks)
        lastTs = open.endTs
        lastLevel = open.endLevel
        lastCc = open.endCc
        tempN = 1
        tempSum = open.avgTempT.toDouble()
        // Elapsed time across the gap is real but unmeasured, so integrals skip it.
        lastElapsed = 0L
        estUah = -1f
        estCc = null
    }

    private suspend fun sample(): Live {
        val ts = System.currentTimeMillis()
        val el = SystemClock.elapsedRealtime()
        val r = reader.read()
        val level = r.level ?: return _live.value
        val plugged = r.plug != 0
        val isCharging = r.status == BatteryManager.BATTERY_STATUS_CHARGING
        val oldScale = cal.scale
        val oldSign = cal.sign
        val ua = cal.normalise(r.currentNow, plugged, isCharging, level)
        if (cal.scale != oldScale || cal.sign != oldSign) prefs.edit {
            it[Prefs.K.curScale] = cal.scale
            it[Prefs.K.curSign] = cal.sign
            it[Prefs.K.curFp] = Build.FINGERPRINT
        }
        val mv = Units.millivolts(r.voltageRaw)
        val mw = Units.powerMw(mv, ua)
        val power = when {
            !plugged -> Power.BATTERY
            r.status == BatteryManager.BATTERY_STATUS_FULL || (level >= 100 && (ua ?: 0) < 100_000) -> Power.FULL
            isCharging -> Power.CHARGING
            else -> Power.PLUGGED_IDLE
        }
        val prevLive = _live.value
        if (prevLive.ready && (prevLive.plugged != plugged)) {
            emaUa = null
            recentUa.clear()
            etaAt = 0
            hMw.clear(); hUa.clear()
        }
        val gap = if (lastElapsed == 0L) 0L else el - lastElapsed
        // The live traces are one-second data; never join them across a background stretch.
        if (gap > 5_000) {
            hMw.clear(); hMv.clear(); hUa.clear(); hTemp.clear()
        }
        if (ua != null) emaUa = emaUa?.let { it + (if (gap > 5_000) 0.6f else 0.2f) * (ua - it) } ?: ua.toFloat()
        val uaS = emaUa?.toInt()
        if (ua != null) {
            recentUa.addLast(el to ua.toFloat())
            while (recentUa.isNotEmpty() && el - recentUa.first().first > 15 * 60_000L) recentUa.removeFirst()
        }
        val screen = reader.screenOn
        val thermal = reader.thermalStatus()
        val tempT = r.tempT
        hMw.add((mw ?: 0) / 1000f); hMv.add((mv ?: 0) / 1000f); hUa.add((ua ?: 0) / 1_000_000f); hTemp.add((tempT ?: 0) / 10f)

        val unstable = power == Power.CHARGING && level < 85 && recentUa.size >= 20 &&
            // Chargers ramp and renegotiate in the first minutes; that is not a loose cable.
            ts - (cur?.startTs ?: ts) > 150_000 &&
            recentUa.filter { el - it.first < 60_000 }.map { it.second }.let { it.size >= 15 && stdOverMean(it) > 0.45f }
        track(ts, el, level, plugged, r, ua, mv, mw, tempT, screen, thermal, power, unstable, gap)

        val s = settings.value
        val designMah = design.resolve(s.manualDesignMah).mah
        val fullUah = measuredFullUah ?: designMah?.times(1000) ?: r.ccUah?.let { if (level >= 20) (it * 100L / level).toInt() else null }
        estimates(el, level, power, uaS, fullUah, r.plug)

        val warnings = warnings(el, level, r, power, mw, tempT, thermal, unstable)
        val out = Live(
            ready = true, ts = ts, level = level, power = power, plug = r.plug, healthFlag = r.health,
            mv = mv, ua = ua, mw = mw, uaSmooth = uaS, mwSmooth = Units.powerMw(mv, uaS),
            tempC = tempT?.let { it / 10f }, cc = r.ccUah, tech = r.tech, cycleCount = r.cycleCount,
            negotiatedMv = if (plugged) r.maxUv?.div(1000) else null, negotiatedUa = if (plugged) r.maxUa else null,
            soh = r.soh, thermal = thermal, headroom = reader.headroom(el), sysEtaMs = r.sysEtaMs,
            etaFullMs = etaFull, eta80Ms = eta80, etaLearned = learned?.getOrNull(level.coerceAtMost(99))?.let { it > 0 } == true,
            leftMs = timeLeft(level, power, fullUah, r.ccUah),
            session = cur, marks = if (plugged) marks.copyOf() else null, warnings = warnings, screenOn = screen,
            designMah = designMah, raw = r,
            histMw = hMw.copy(), histMv = hMv.copy(), histUa = hUa.copy(), histTemp = hTemp.copy(),
        )
        _live.value = out
        return out
    }

    private suspend fun track(
        ts: Long, el: Long, level: Int, plugged: Boolean, r: RawBattery, ua: Int?, mv: Int?, mw: Int?, tempT: Int?,
        screen: Boolean, thermal: Int, power: Power, unstable: Boolean, gap: Long,
    ) {
        val kind = if (plugged) KIND_CHARGE else KIND_DRAIN
        var c = cur
        if (c != null) {
            val wallGap = ts - lastTs
            val broken = c.kind != kind || c.bootCount != boot || wallGap < -60_000 ||
                (kind == KIND_CHARGE && (wallGap > 30 * 60_000L || level < lastLevel - 3 || (level > lastLevel + 5 && wallGap < 120_000))) ||
                // A drain may sleep for hours, but it can never gain charge unless we missed a plug-in.
                (kind == KIND_DRAIN && (level > lastLevel + 1 || wallGap > 12 * 3_600_000L))
            if (broken) {
                close(c)
                c = null
                flush(true)
            }
        }
        if (c == null) {
            marks = IntArray(101) { -1 }
            marks[level] = 0
            tempSum = 0.0
            tempN = 0
            estUah = -1f
            estCc = null
            lastElapsed = 0L
            val t = tempT ?: 250
            val fresh = Session(
                kind = kind, startTs = ts, endTs = ts, startLevel = level, endLevel = level, startCc = r.ccUah, endCc = r.ccUah,
                plug = r.plug, peakTempT = t, avgTempT = t, minTempT = t, minMv = mv ?: 0, maxMv = mv ?: 0, bootCount = boot,
                marks = Marks.encode(marks),
            )
            c = fresh.copy(id = dao.addSession(fresh))
            if (kind == KIND_CHARGE) learnedPlug = -1
        }
        val dt = if (lastElapsed == 0L) 0L else gap
        val measured = dt in 1..90_000
        if (kind == KIND_CHARGE && marks[level] < 0) marks[level] = ((ts - c.startTs) / 1000).toInt()
        if (tempT != null) {
            tempSum += tempT
            tempN++
        }
        val dUah = if (measured && ua != null) ua * (dt / 3_600_000f) else 0f
        val dMwh = if (measured && mw != null) mw * (dt / 3_600_000f) else 0f
        val covered = c.coveredMs + if (measured && ua != null) dt else 0
        val energy = c.energyMwh + if (kind == KIND_CHARGE) max(0f, dMwh) else max(0f, -dMwh)
        val charge = c.chargeUah + if (kind == KIND_CHARGE) max(0f, dUah) else max(0f, -dUah)
        if (level >= 100 && estUah < 0 && lastLevel < 100) {
            estUah = charge
            estCc = r.ccUah
        }
        // Screen-on/off split uses the charge counter so sleep time is not over-counted.
        var drop = 0f
        if (kind == KIND_DRAIN && lastLevel >= 0 && dt > 0) {
            val a = lastCc
            val b = r.ccUah
            drop = if (a != null && b != null && a > b) (a - b).toFloat()
            else if (lastLevel > level) (lastLevel - level) * ((measuredFullUah ?: 4_000_000) / 100f) else 0f
        }
        val step = if (dt in 1..12 * 3_600_000L) dt else 0L
        c = c.copy(
            endTs = ts, endLevel = level, endCc = r.ccUah ?: c.endCc,
            plug = if (r.plug != 0) r.plug else c.plug,
            peakMw = max(c.peakMw, abs(mw ?: 0)),
            avgMw = if (covered > 0) (energy / (covered / 3_600_000f)).toInt() else abs(mw ?: 0),
            peakTempT = max(c.peakTempT, tempT ?: c.peakTempT),
            minTempT = min(c.minTempT, tempT ?: c.minTempT),
            avgTempT = if (tempN > 0) (tempSum / tempN).toInt() else c.avgTempT,
            minMv = if (mv == null) c.minMv else if (c.minMv == 0) mv else min(c.minMv, mv),
            maxMv = max(c.maxMv, mv ?: 0),
            energyMwh = energy, chargeUah = charge, durationMs = c.durationMs + step, coveredMs = covered,
            screenOnMs = c.screenOnMs + if (lastScreen) step else 0,
            uahOn = c.uahOn + if (lastScreen) drop else 0f,
            uahOff = c.uahOff + if (!lastScreen) drop else 0f,
            heat40Ms = c.heat40Ms + if ((tempT ?: 0) >= 400 && measured) dt else 0,
            heat43Ms = c.heat43Ms + if ((tempT ?: 0) >= 430 && measured) dt else 0,
            holdMs = c.holdMs + if (kind == KIND_CHARGE && level >= 100 && lastLevel >= 100) step else 0,
            thermalMax = max(c.thermalMax, thermal),
            negotiatedMw = max(c.negotiatedMw, Units.powerMw(r.maxUv?.div(1000), r.maxUa) ?: 0),
            unstable = c.unstable || unstable,
        )
        cur = c
        trackDay(ts, level, c, kind, tempT, mw, dMwh, step, measured, dt)

        if (ts - lastStored >= if (plugged) 8_000 else 25_000) {
            lastStored = ts
            buf += Sample(ts = ts, level = level, mv = mv, ua = ua, cc = r.ccUah, tempT = tempT ?: 0, charging = power == Power.CHARGING,
                plug = r.plug, thermal = thermal, screenOn = screen, sessionId = c.id)
        }
        lastElapsed = el
        lastTs = ts
        lastLevel = level
        lastCc = r.ccUah
        lastScreen = screen
        if (ts - lastFlush > 30_000) flush(false)
    }

    private suspend fun trackDay(
        ts: Long, level: Int, c: Session, kind: Int, tempT: Int?, mw: Int?, dMwh: Float, step: Long, measured: Boolean, dt: Long,
    ) {
        val id = Fmt.epochDay(ts)
        var d = day
        if (d == null || d.day != id) {
            if (d != null) {
                dao.saveDay(d)
                dao.addWins(Goals.wins(d, settings.value))
            }
            d = dao.day(id) ?: Day(id, minLevel = level, maxLevel = level)
        }
        val moved = if (lastLevel >= 0 && step > 0) level - lastLevel else 0
        // One charge, once it has gained something; a wobble at the socket is not a charge.
        val counted = kind == KIND_CHARGE && c.gained >= 2 && lastLevel - c.startLevel < 2 && moved > 0
        day = d.copy(
            chargedPct = d.chargedPct + if (kind == KIND_CHARGE) max(0, moved) else 0,
            drainedPct = d.drainedPct + if (kind == KIND_DRAIN) max(0, -moved) else 0,
            charges = d.charges + if (counted) 1 else 0,
            minLevel = min(d.minLevel, level), maxLevel = max(d.maxLevel, level),
            maxTempT = max(d.maxTempT, tempT ?: d.maxTempT),
            screenOnMs = d.screenOnMs + if (lastScreen) step else 0,
            heat40Ms = d.heat40Ms + if ((tempT ?: 0) >= 400 && measured) dt else 0,
            heat43Ms = d.heat43Ms + if ((tempT ?: 0) >= 430 && measured) dt else 0,
            energyInMwh = d.energyInMwh + if (kind == KIND_CHARGE) max(0f, dMwh) else 0f,
            energyOutMwh = d.energyOutMwh + if (kind == KIND_DRAIN) max(0f, -dMwh) else 0f,
            peakMw = if (kind == KIND_CHARGE) max(d.peakMw, mw ?: 0) else d.peakMw,
            trackedMs = min(86_400_000L, d.trackedMs + step),
            maxUnplug = if (kind == KIND_CHARGE && c.gained >= 2) max(d.maxUnplug, level) else d.maxUnplug,
        )
    }

    private suspend fun flush(all: Boolean) {
        lastFlush = System.currentTimeMillis()
        if (buf.isNotEmpty()) {
            dao.addSamples(buf.toList())
            buf.clear()
        }
        day?.let { dao.saveDay(it) }
        cur?.let { dao.saveSession(it.copy(marks = Marks.encode(marks))) }
        if (all) day = null
    }

    private suspend fun close(s: Session) {
        val closed = s.copy(open = false, marks = if (s.id == cur?.id) Marks.encode(marks) else s.marks)
        cur = null
        if (abs(closed.gained) < 1 && closed.durationMs < 5 * 60_000L) {
            dao.deleteSession(closed.id)
            return
        }
        if (!closed.isCharge) {
            dao.saveSession(closed)
            return
        }
        val (score, ded) = Grader.grade(closed)
        dao.saveSession(closed.copy(score = if (closed.gained >= 3) score else -1, reasons = Grader.encode(ded)))
        val designUah = design.resolve(settings.value.manualDesignMah).mah?.times(1000)
        val cover = if (closed.durationMs > 0) closed.coveredMs / closed.durationMs.toFloat() else 0f
        Capacity.fromCharge(
            closed.startLevel, closed.endLevel, closed.startCc, estCc ?: closed.endCc,
            if (estUah >= 0) estUah else closed.chargeUah, cover, closed.minTempT, closed.peakTempT, closed.avgTempT, designUah,
        )?.let {
            dao.addEstimate(Estimate(ts = closed.endTs, uah = it.uah, weight = it.weight, method = it.method,
                spanPct = closed.gained, tempC = closed.avgTempT / 10f, sessionId = closed.id))
        }
    }

    // ---- estimates -----------------------------------------------------------------------------

    private suspend fun estimates(el: Long, level: Int, power: Power, uaS: Int?, fullUah: Int?, plug: Int) {
        if (power != Power.CHARGING) {
            etaFull = null
            eta80 = null
            etaAt = 0
            return
        }
        if (learnedPlug != plug) {
            learnedPlug = plug
            learned = ChargeModel.learn(dao.lastCharges(40), plug)
        }
        // Chargers take a few seconds to ramp; a forecast from that would be wildly long.
        if ((cur?.durationMs ?: 0) < 12_000 && cur?.let { it.endTs - it.startTs < 12_000 } != false) {
            etaFull = null
            eta80 = null
            return
        }
        // Every 5 s at most, so the number does not dance.
        if (etaAt != 0L && el - etaAt < 5_000) return
        etaAt = el
        if (uaS == null || fullUah == null) {
            etaFull = null
            eta80 = null
            return
        }
        etaFull = ChargeModel.eta(level, 100, uaS, fullUah, learned)
        eta80 = if (level < 80) ChargeModel.eta(level, 80, uaS, fullUah, learned) else null
    }

    private fun timeLeft(level: Int, power: Power, fullUah: Int?, cc: Int?): Long? {
        if (power != Power.BATTERY) return null
        val remain = cc?.toFloat() ?: fullUah?.let { it * level / 100f }
        val recent = recentUa.map { it.second }.filter { it < 0 }
        val now = if (remain != null && recent.size >= 10) (remain / (-recent.average()) * 3_600_000).toLong() else null
        val typ = typicalDrain?.let { (on, off, share) -> (level / (on * share + off * (1 - share)) * 3_600_000).toLong() }
        return when {
            typ != null && now != null -> (typ * 2 + now) / 3
            else -> typ ?: now
        }?.coerceIn(60_000, 14 * 86_400_000L)
    }

    private fun warnings(el: Long, level: Int, r: RawBattery, power: Power, mw: Int?, tempT: Int?, thermal: Int, unstable: Boolean): List<Warning> {
        hotSince = if ((tempT ?: 0) >= 430) (if (hotSince == 0L) el else hotSince) else 0L
        val slow = power == Power.CHARGING && r.plug != BatteryManager.BATTERY_PLUGGED_USB && level < 80 && mw != null &&
            mw < (if (r.plug == BatteryManager.BATTERY_PLUGGED_WIRELESS) 3_000 else 4_000)
        slowSince = if (slow) (if (slowSince == 0L) el else slowSince) else 0L
        return buildList {
            if (r.health in listOf(BatteryManager.BATTERY_HEALTH_DEAD, BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE, BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE)) add(Warning.BAD_FLAG)
            if ((tempT ?: 0) >= 460) add(Warning.EXTREME_TEMP)
            else if (hotSince != 0L && el - hotSince >= 60_000) add(Warning.HIGH_TEMP)
            if (thermal >= 3) add(Warning.SEVERE_THROTTLE) else if (thermal == 2) add(Warning.THROTTLE)
            if (unstable) add(Warning.UNSTABLE)
            if (slowSince != 0L && el - slowSince >= 180_000) add(Warning.SLOW_CHARGE)
            if ((cur?.holdMs ?: 0) > 4 * 3_600_000L) add(Warning.HOLD)
        }
    }

    fun recalibrate() {
        cal.scale = 0
        cal.sign = 0
    }

    val calibration: Pair<Int, Int> get() = cal.scale to cal.sign
}

private class Ring(private val n: Int) {
    private val a = FloatArray(n)
    private var size = 0
    private var head = 0

    fun add(v: Float) {
        a[head] = v
        head = (head + 1) % n
        if (size < n) size++
    }

    fun clear() {
        size = 0
        head = 0
    }

    fun copy(): FloatArray = FloatArray(size) { a[(head - size + it + n) % n] }
}
