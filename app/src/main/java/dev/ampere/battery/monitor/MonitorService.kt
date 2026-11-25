package dev.ampere.battery.monitor

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.ampere.battery.AmpereApp
import dev.ampere.battery.MainActivity
import dev.ampere.battery.R
import dev.ampere.battery.core.Fmt
import dev.ampere.battery.data.Settings
import dev.ampere.battery.domain.Achievement
import dev.ampere.battery.domain.Warning
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object Notifs {
    const val MONITOR = "monitor"
    const val CHARGE = "alerts_charge"
    const val TEMP = "alerts_temp"
    const val HEALTH = "alerts_health"
    const val GAME = "alerts_game"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: String, imp: Int) = nm.createNotificationChannel(NotificationChannel(id, name, imp).apply { setShowBadge(false) })
        ch(MONITOR, "Background tracking", NotificationManager.IMPORTANCE_LOW)
        ch(CHARGE, "Charge level and timers", NotificationManager.IMPORTANCE_HIGH)
        ch(TEMP, "Temperature", NotificationManager.IMPORTANCE_HIGH)
        ch(HEALTH, "Charging quality", NotificationManager.IMPORTANCE_DEFAULT)
        ch(GAME, "Achievements and ranks", NotificationManager.IMPORTANCE_DEFAULT)
    }

    fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
    )

    fun headline(l: Live): String {
        if (!l.ready) return "Ampere"
        val w = l.mwSmooth?.let { " · ${if (l.plugged) "+" else "−"}${Fmt.watts(it)} W" } ?: ""
        return "${l.level}%$w"
    }

    fun detail(l: Live, s: Settings): String = listOfNotNull(
        when (l.power) {
            Power.CHARGING -> l.etaFullMs?.let { "Full in ${Fmt.duration(it)}, at ${Fmt.clock(l.ts + it)}" } ?: "Charging"
            Power.FULL -> "Fully charged"
            Power.PLUGGED_IDLE -> "Plugged in, not charging"
            Power.BATTERY -> l.leftMs?.let { "About ${Fmt.duration(it)} left" } ?: "On battery"
        },
        if (s.notifStyle >= 2) l.tempC?.let { Fmt.temp(it, s.fahrenheit) } else null,
    ).joinToString(" · ")

    fun ongoing(ctx: Context, l: Live, s: Settings): Notification = NotificationCompat.Builder(ctx, MONITOR)
        .setSmallIcon(R.drawable.ic_bolt)
        .setContentTitle(if (s.notifStyle == 0 && l.ready) "${l.level}%" else headline(l))
        .setContentText(if (l.ready) detail(l, s) else "Starting")
        .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setContentIntent(open(ctx))
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    @android.annotation.SuppressLint("MissingPermission") // allowed() checks it
    fun post(ctx: Context, id: Int, channel: String, title: String, text: String) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, channel).setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(open(ctx)).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun allowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun unlocked(ctx: Context, a: Achievement) = post(ctx, 400 + (a.id.hashCode() and 0xff), GAME, a.title, "${a.text}. +${a.vp} VP")
}

/** Keeps the sampler alive in the background when the user turns tracking on. */
class MonitorService : Service() {
    private var watch: Job? = null
    private var lastNotif = 0L
    private val fired = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val c = (application as AmpereApp).container
        Notifs.channels(this)
        runCatching {
            ServiceCompat.startForeground(
                this, 1, Notifs.ongoing(this, c.engine.live.value, c.settings.value),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        }.onFailure {
            stopSelf()
            return
        }
        running = true
        c.engine.onTick = { a, b -> tick(a, b, c.settings.value) }
        c.engine.setService(true)
        watch = c.scope.launch { c.keepWarm() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        val c = (application as AmpereApp).container
        c.engine.onTick = null
        c.engine.setService(false)
        watch?.cancel()
        super.onDestroy()
    }

    private fun tick(a: Live, b: Live, s: Settings) {
        val now = SystemClock.elapsedRealtime()
        val changed = a.level != b.level || a.power != b.power
        if (changed || now - lastNotif >= 10_000) {
            lastNotif = now
            runCatching { getSystemService(NotificationManager::class.java).notify(1, Notifs.ongoing(this, b, s)) }
            BatteryWidget.update(this, b)
        }
        alerts(a, b, s)
    }

    /** Each alert fires once per plug/unplug, so nothing nags. */
    private fun alerts(a: Live, b: Live, s: Settings) {
        if (a.plugged != b.plugged) fired.clear()
        fun once(key: String, on: Boolean, block: () -> Unit) {
            if (on && fired.add(key)) block()
        }
        once("full", s.alertFull && b.plugged && b.level >= s.alertFullAt && (a.level < b.level || !a.ready)) {
            Notifs.post(this, 2, Notifs.CHARGE, "Charged to ${b.level}%", "Your target is ${s.alertFullAt}%. Good time to unplug.")
        }
        once("low", s.alertLow && !b.plugged && b.level <= s.alertLowAt) {
            Notifs.post(this, 3, Notifs.CHARGE, "${b.level}% left", "Below your ${s.alertLowAt}% line.")
        }
        once("hot", s.alertHot && (b.tempC ?: 0f) >= s.alertHotAt) {
            Notifs.post(this, 4, Notifs.TEMP, "Battery at ${Fmt.temp(b.tempC, s.fahrenheit)}", "Let it cool. Heat wears the cell out.")
        }
        once("slow", s.alertSlow && Warning.SLOW_CHARGE in b.warnings) {
            Notifs.post(this, 5, Notifs.HEALTH, "Charging slowly", "Only ${Fmt.watts(b.mwSmooth)} W. Try another cable.")
        }
        once("unstable", s.alertSlow && Warning.UNSTABLE in b.warnings) {
            Notifs.post(this, 6, Notifs.HEALTH, Warning.UNSTABLE.title, Warning.UNSTABLE.text)
        }
        once("throttle", s.alertThrottle && b.thermal >= 2) {
            Notifs.post(this, 7, Notifs.TEMP, "Phone is throttling", "Performance is reduced to manage heat.")
        }
    }

    companion object {
        @Volatile
        var running = false

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val c = (ctx.applicationContext as AmpereApp).container
        val pending = goAsync()
        c.scope.launch {
            try {
                val s = c.loadedSettings()
                if (s.monitoring) MonitorService.start(ctx)
                // Alarms do not survive a reboot.
                if (s.timerAt > System.currentTimeMillis()) TimerReceiver.schedule(ctx, s.timerAt)
            } finally {
                pending.finish()
            }
        }
    }
}

/** The countdown on the timer sheet. Inexact on purpose: no special alarm permission needed. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val c = (ctx.applicationContext as AmpereApp).container
        Notifs.channels(ctx)
        val lvl = c.reader.read().level
        Notifs.post(ctx, 8, Notifs.CHARGE, "Timer finished", if (lvl != null) "Battery is at $lvl%." else "Time to check your charge.")
        val p = goAsync()
        c.scope.launch {
            c.setTimer(0)
            p.finish()
        }
    }

    companion object {
        private fun pi(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 9, Intent(ctx, TimerReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun schedule(ctx: Context, at: Long) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            if (at <= 0) am.cancel(pi(ctx)) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
        }
    }
}

/** Quick Settings tile: live level and watts without opening the app. */
class AmpereTile : TileService() {
    override fun onStartListening() {
        val c = (application as AmpereApp).container
        c.engine.attach()
        job = c.scope.launch {
            c.engine.live.collect { l ->
                val t = qsTile ?: return@collect
                t.icon = Icon.createWithResource(this@AmpereTile, R.drawable.ic_bolt)
                t.state = if (l.plugged) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                t.label = Notifs.headline(l)
                t.contentDescription = Notifs.detail(l, c.settings.value)
                runCatching { t.updateTile() }
            }
        }
    }

    private var job: Job? = null

    override fun onStopListening() {
        job?.cancel()
        (application as AmpereApp).container.engine.detach()
    }

    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(Notifs.open(this))
        else startActivityAndCollapse(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
