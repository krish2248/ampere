package dev.ampere.battery.monitor

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import dev.ampere.battery.AmpereApp
import dev.ampere.battery.R
import dev.ampere.battery.core.Fmt
import dev.ampere.battery.hw.Units

class BatteryWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val c = (ctx.applicationContext as AmpereApp).container
        val l = c.engine.live.value
        // Without background tracking there is no live state, so read the level once.
        if (l.ready) update(ctx, l) else {
            val r = c.reader.read()
            update(ctx, Live(ready = r.level != null, level = r.level ?: 0, plug = r.plug, mv = Units.millivolts(r.voltageRaw),
                tempC = r.tempT?.let { it / 10f }, power = if (r.plug != 0) Power.CHARGING else Power.BATTERY))
        }
    }

    companion object {
        fun update(ctx: Context, l: Live) {
            if (!l.ready) return
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BatteryWidget::class.java))
            if (ids.isEmpty()) return
            val s = (ctx.applicationContext as AmpereApp).container.settings.value
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.w_level, "${l.level}%")
            v.setTextViewText(R.id.w_power, l.mwSmooth?.let { (if (l.plugged) "+" else "−") + Fmt.watts(it) + " W" } ?: Fmt.temp(l.tempC, s.fahrenheit))
            v.setTextViewText(R.id.w_detail, when (l.power) {
                Power.CHARGING -> l.etaFullMs?.let { "Full in ${Fmt.duration(it)}" } ?: "Charging"
                Power.FULL -> "Full"
                Power.PLUGGED_IDLE -> "Not charging"
                Power.BATTERY -> l.leftMs?.let { "${Fmt.duration(it)} left" } ?: "On battery"
            })
            v.setProgressBar(R.id.w_bar, 100, l.level, false)
            v.setOnClickPendingIntent(R.id.w_root, Notifs.open(ctx))
            runCatching { mgr.updateAppWidget(ids, v) }
        }
    }
}
