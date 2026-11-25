package dev.ampere.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.ampere.battery.core.Fmt
import dev.ampere.battery.data.Day
import dev.ampere.battery.data.Estimate
import dev.ampere.battery.data.GoalWin
import dev.ampere.battery.data.KIND_CHARGE
import dev.ampere.battery.data.KIND_DRAIN
import dev.ampere.battery.data.Prefs
import dev.ampere.battery.data.Sample
import dev.ampere.battery.data.Session
import dev.ampere.battery.domain.ChargeModel
import dev.ampere.battery.domain.Grader
import dev.ampere.battery.domain.Marks
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * Debug builds only. Seeds believable history and fakes a charger so screens can be checked on an emulator.
 * adb shell am broadcast -n dev.ampere.battery/.DemoReceiver --es do seed|charge|battery|real|idle
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val c = (ctx.applicationContext as AmpereApp).container
        when (i.getStringExtra("do")) {
            "seed" -> {
                val p = goAsync()
                c.scope.launch {
                    seed(c)
                    p.finish()
                }
            }
            "charge" -> fake(c, i.getIntExtra("level", 62), i.getIntExtra("ua", 4_300_000), 1, i.getIntExtra("temp", 347), i.getIntExtra("step", 25))
            "battery" -> fake(c, i.getIntExtra("level", 62), -410_000, 0, 301, 0)
            "idle" -> fake(c, 85, 0, 1, 298, 0)
            "s24" -> c.scope.launch { c.previewDevice(c.devices.identify("SM-S921B", "samsung", "samsung")) }
            "widget" -> {
                val d = ctx.resources.displayMetrics.density
                val host = android.widget.FrameLayout(ctx)
                val v = dev.ampere.battery.monitor.BatteryWidget.views(ctx, c.engine.live.value).apply(ctx, host)
                val w = (200 * d).toInt()
                val h = (92 * d).toInt()
                v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, 1 shl 30), android.view.View.MeasureSpec.makeMeasureSpec(h, 1 shl 30))
                v.layout(0, 0, w, h)
                val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                v.draw(android.graphics.Canvas(b))
                java.io.File(ctx.cacheDir, "widget.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            "real" -> c.reader.tamper = { it }
        }
        c.engine.poke()
    }

    /** [step] seconds per percent while charging, so a whole charge can be watched in minutes. */
    private fun fake(c: Container, level: Int, ua: Int, plug: Int, temp: Int, step: Int) {
        val t0 = System.currentTimeMillis()
        c.reader.tamper = { r ->
            val s = (System.currentTimeMillis() - t0) / 1000.0
            val lv = if (plug != 0 && step > 0) (level + (s / step).toInt()).coerceAtMost(100) else level
            val k = if (plug != 0) ChargeModel.rel(lv.toFloat()) / ChargeModel.rel(level.toFloat()) else 1f
            val noise = (sin(s * 1.7) * 0.03 + sin(s * 0.43) * 0.05 + Random.nextDouble(-0.015, 0.015))
            r.copy(
                level = lv, status = if (plug == 0) 3 else if (lv >= 100) 5 else if (ua == 0) 4 else 2, plug = plug, health = 2,
                voltageRaw = (3_950 + lv * 4 + (noise * 300).toInt()), tempT = temp + (sin(s / 9) * 4).toInt(), tech = "Li-ion",
                currentNow = (ua * k * (1 + noise)).toInt(), ccUah = 37_200 * lv, sysEtaMs = null, cycleCount = 287,
                maxUa = if (plug != 0) 2_770_000 else null, maxUv = if (plug != 0) 9_000_000 else null,
            )
        }
    }

    private suspend fun seed(c: Container) {
        val rnd = Random(7)
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        c.prefs.edit {
            it[Prefs.K.onboarded] = true
            it[Prefs.K.nick] = "Volta"
            it[Prefs.K.country] = "IN"
            it[Prefs.K.design] = 4000
            it[Prefs.K.installedAt] = now - 64 * day
        }
        var id = 1000L
        for (d in 63 downTo 0) {
            val base = now - d * day - 20 * 3_600_000L
            var lv = rnd.nextInt(74, 96)
            val morning = lv
            val low = listOf(1, 8, 18, 22, 26, 31, 38)[rnd.nextInt(7)]
            val high = listOf(80, 85, 85, 90, 100, 100)[rnd.nextInt(6)]
            val drainMs = rnd.nextLong(9, 15) * 3_600_000L
            val screen = (drainMs * rnd.nextDouble(0.26, 0.42)).toLong()
            val drop = (lv - low) * 37_000f
            val drain = Session(
                id = id++, kind = KIND_DRAIN, startTs = base, endTs = base + drainMs, startLevel = lv, endLevel = low, durationMs = drainMs, coveredMs = drainMs,
                screenOnMs = screen, uahOn = drop * 0.82f, uahOff = drop * 0.18f, energyMwh = (lv - low) * 142f, chargeUah = drop, avgMw = 1450, peakMw = 5200,
                peakTempT = 342, avgTempT = 306, minTempT = 271, minMv = 3600, maxMv = 4300, open = false,
            )
            c.dao.saveSession(drain)
            val t0 = base + drainMs
            val speed = rnd.nextDouble(0.8, 1.12)
            val marks = IntArray(101) { -1 }
            var t = 0.0
            val smp = ArrayList<Sample>()
            for (l in low..high) {
                marks[l] = t.toInt()
                val sec = 31.0 * speed / ChargeModel.rel(l.toFloat())
                val ua = (4_300_000 / speed * ChargeModel.rel(l.toFloat())).toInt()
                if (d < 8) smp += Sample(ts = t0 + (t * 1000).toLong(), level = l, mv = 3_950 + l * 4, ua = ua, cc = 37_200 * l, tempT = 300 + (sin(l / 22.0) * 70).toInt(), charging = true, plug = 1, thermal = 0, screenOn = false, sessionId = id)
                t += sec
            }
            val peakT = rnd.nextInt(352, 418)
            var x = Session(
                id = id, kind = KIND_CHARGE, startTs = t0, endTs = t0 + (t * 1000).toLong(), startLevel = low, endLevel = high, startCc = low * 37_200, endCc = high * 37_200,
                plug = listOf(1, 1, 1, 1, 2, 4)[rnd.nextInt(6)], peakMw = (19_800 / speed).toInt(), avgMw = (14_200 / speed).toInt(), peakTempT = peakT, avgTempT = peakT - 34, minTempT = 278,
                minMv = 3_820, maxMv = 4_420, energyMwh = (high - low) * 148f, chargeUah = (high - low) * 37_200f, durationMs = (t * 1000).toLong(), coveredMs = (t * 1000).toLong(),
                heat40Ms = if (peakT > 400) rnd.nextLong(3, 16) * 60_000 else 0, screenOnMs = 600_000, marks = Marks.encode(marks), negotiatedMw = 24_900, open = false,
            )
            val (score, ded) = Grader.grade(x)
            x = x.copy(score = score, reasons = Grader.encode(ded))
            c.dao.saveSession(x)
            if (high - low >= 25) c.dao.addEstimate(Estimate(ts = x.endTs, uah = 3_790_000 - (63 - d) * 1_250 + rnd.nextInt(-45_000, 45_000), weight = 0.8f, method = "charge counter", spanPct = high - low, tempC = 32f, sessionId = id))
            id++
            if (d < 8) {
                var dt = 0L
                while (dt < drainMs) {
                    val f = dt.toFloat() / drainMs
                    lv = (morning - (morning - low) * f).toInt()
                    val on = sin(dt / 2.4e6) > 0.1
                    smp += Sample(ts = base + dt, level = lv, mv = 4_330 - ((1 - lv / 100f) * 560).toInt(), ua = if (on) -rnd.nextInt(300_000, 720_000) else -rnd.nextInt(25_000, 70_000),
                        cc = 37_200 * lv, tempT = 285 + (if (on) 34 else 0) + rnd.nextInt(-6, 9), charging = false, plug = 0, thermal = 0, screenOn = on, sessionId = drain.id)
                    dt += 240_000
                }
                c.dao.addSamples(smp)
            }
            val di = Day(
                Fmt.epochDay(base), chargedPct = high - low, drainedPct = morning - low, charges = 1, minLevel = low, maxLevel = high, maxTempT = peakT, screenOnMs = screen,
                heat40Ms = x.heat40Ms, energyInMwh = x.energyMwh, energyOutMwh = drain.energyMwh, peakMw = x.peakMw, trackedMs = 20 * 3_600_000L, maxUnplug = high,
            )
            c.dao.saveDay(di)
            if (d > 0) c.dao.addWins(dev.ampere.battery.domain.Goals.wins(di, c.settings.value).map { GoalWin(di.day, it.goal) })
        }
    }
}
