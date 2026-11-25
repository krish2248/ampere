package dev.ampere.battery

import android.app.Application
import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import dev.ampere.battery.core.Fmt
import dev.ampere.battery.data.AmpereDb
import dev.ampere.battery.data.Prefs
import dev.ampere.battery.data.Settings
import dev.ampere.battery.data.Unlock
import dev.ampere.battery.domain.Achievement
import dev.ampere.battery.domain.Achievements
import dev.ampere.battery.domain.Game
import dev.ampere.battery.domain.GameState
import dev.ampere.battery.domain.Goals
import dev.ampere.battery.domain.Health
import dev.ampere.battery.domain.HealthCalc
import dev.ampere.battery.domain.Ranks
import dev.ampere.battery.domain.Stats
import dev.ampere.battery.domain.StatsCalc
import dev.ampere.battery.hw.BatteryReader
import dev.ampere.battery.hw.DesignResolver
import dev.ampere.battery.hw.DeviceDb
import dev.ampere.battery.hw.DeviceInfo
import dev.ampere.battery.monitor.Engine
import dev.ampere.battery.monitor.MonitorService
import dev.ampere.battery.monitor.Notifs
import dev.ampere.battery.monitor.TimerReceiver
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AmpereApp : Application() {
    val container: Container by lazy { Container(this) }

    override fun onCreate() {
        super.onCreate()
        Notifs.channels(this)
        container.warm()
    }
}

/** Everything derived from stored history, computed once and shared by every screen. */
data class Derived(
    val loaded: Boolean = false,
    val stats: Stats = Stats(),
    val health: Health = HealthCalc.of(null, emptyList(), null, null, null),
    val game: GameState = GameState(0, Ranks.all[0], 0, 0, 0, emptyMap(), emptyMap(), 0, 0),
    val samples: Int = 0,
    val estimates: List<dev.ampere.battery.data.Estimate> = emptyList(),
)

class Container(val ctx: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AmpereDb by lazy { AmpereDb.open(ctx) }
    val dao by lazy { db.dao() }
    val prefs = Prefs(ctx)
    val reader = BatteryReader(ctx)
    val devices = DeviceDb(ctx)
    val design = DesignResolver(ctx, devices)
    private val settingsLoaded = MutableStateFlow<Settings?>(null)
    val settings: StateFlow<Settings> = prefs.flow.map { it.also { s -> settingsLoaded.value = s } }
        .stateIn(scope, SharingStarted.Eagerly, Settings())
    val settingsReady: StateFlow<Settings?> = settingsLoaded

    suspend fun loadedSettings(): Settings = prefs.flow.first()

    val engine: Engine by lazy { Engine(ctx, reader, design, dao, prefs, settings, scope) }
    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device
    /** Debug tools only: pretend to be another phone. */
    fun previewDevice(info: DeviceInfo) {
        _device.value = info
    }

    val newUnlocks = MutableSharedFlow<Achievement>(extraBufferCapacity = 16)
    private val exported = MutableStateFlow(false)

    private data class Hist(val s: Stats, val est: List<dev.ampere.battery.data.Estimate>)

    private val hist: Flow<Hist> = combine(
        dao.sessions().distinctUntilChanged(), dao.days().distinctUntilChanged(), dao.estimates().distinctUntilChanged(),
    ) { ses, days, est -> Hist(StatsCalc.of(ses, days), est) }.conflate()

    /** Level and counter only matter to health until something has been measured. */
    private val gauge: Flow<Triple<Int?, Int?, Int?>> by lazy {
        engine.live.map { Triple(it.cc?.let { c -> c / 20_000 * 20_000 }, if (it.ready) it.level else null, it.soh) }.distinctUntilChanged()
    }

    val derived: StateFlow<Derived> by lazy {
        val game = combine(dao.unlocks(), dao.wins(), dao.sampleCount().map { it / 500 * 500 }.distinctUntilChanged(), exported) { u, w, n, e -> Four(u, w, n, e) }
        combine(hist, game, gauge, settings.map { it.manualDesignMah }.distinctUntilChanged()) { h, g, ga, manual ->
            val health = HealthCalc.of(design.resolve(manual).mah, h.est, ga.first, ga.second, ga.third)
            val (best, curStreak) = Goals.streaks(g.w, Fmt.epochDay(System.currentTimeMillis()))
            val unlocked = g.u.associate { it.id to it.ts }
            val actx = Achievement.Ctx(h.s, health, g.w, best, g.n, g.e || "pdf" in unlocked)
            engine.measuredFullUah = if (health.count > 0) health.fullMah?.times(1000) else null
            typical(h.s)
            award(actx, unlocked.keys)
            Derived(true, h.s, health, Game.state(actx, unlocked, curStreak), g.n, h.est)
        }.conflate().flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5_000), Derived())
    }

    private data class Four(val u: List<Unlock>, val w: List<dev.ampere.battery.data.GoalWin>, val n: Int, val e: Boolean)

    private fun typical(s: Stats) {
        val on = s.drainOnPctH ?: return
        if (s.drains.size < 3) return
        val recent = s.drains.take(20)
        val total = recent.sumOf { it.durationMs }
        if (total <= 0) return
        engine.typicalDrain = Triple(on, s.drainOffPctH ?: (on * 0.1f), recent.sumOf { it.screenOnMs }.toFloat() / total)
    }

    private suspend fun award(c: Achievement.Ctx, have: Set<String>) {
        for (a in Achievements.all) {
            if (a.id in have || a.value(c) < a.target) continue
            if (dao.unlock(Unlock(a.id, System.currentTimeMillis())) != -1L) {
                newUnlocks.tryEmit(a)
                if (settings.value.alertUnlocks && MonitorService.running) Notifs.unlocked(ctx, a)
            }
        }
    }

    /** Held by the service so awards and estimates keep flowing with no screen open. */
    suspend fun keepWarm() {
        derived.collect { }
    }

    fun warm() {
        scope.launch {
            _device.value = withContext(Dispatchers.IO) { devices.info }
            design.resolve(0)
            if (loadedSettings().installedAt == 0L) prefs.edit {
                it[Prefs.K.installedAt] = System.currentTimeMillis()
                it[Prefs.K.country] = guessCountry()
            }
            newPhoneCheck()
            housekeeping()
        }
    }

    /** A cloud restore onto another phone must not bring the old battery's health with it. */
    private suspend fun newPhoneCheck() {
        var changed = false
        prefs.edit {
            val before = it[Prefs.K.phone]
            changed = before != null && before != Build.MODEL
            it[Prefs.K.phone] = Build.MODEL
            if (changed) it[Prefs.K.design] = 0
        }
        if (changed) dao.clearEstimates()
    }

    fun resetHealth() = scope.launch { dao.clearEstimates() }

    private suspend fun housekeeping() {
        val now = System.currentTimeMillis()
        dao.thin(now - 7 * 86_400_000L, 120_000)
        dao.thin(now - 40 * 86_400_000L, 900_000)
        dao.dropBefore(now - 400 * 86_400_000L)
    }

    private fun guessCountry(): String {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        return listOf(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country)
            .firstOrNull { it != null && it.length == 2 }?.uppercase(Locale.ROOT) ?: ""
    }

    fun setMonitoring(on: Boolean) = scope.launch {
        prefs.edit { it[Prefs.K.monitoring] = on }
        if (on) MonitorService.start(ctx) else MonitorService.stop(ctx)
    }

    suspend fun setTimer(at: Long) {
        prefs.edit { it[Prefs.K.timerAt] = at }
        TimerReceiver.schedule(ctx, at)
    }

    fun markExported() {
        exported.value = true
    }

    fun wipe() = scope.launch {
        withContext(Dispatchers.IO) { db.clearAllTables() }
        engine.forget()
    }

    val isEmulator: Boolean get() = Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu")
}
