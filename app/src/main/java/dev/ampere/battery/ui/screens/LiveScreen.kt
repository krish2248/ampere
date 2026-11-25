package dev.ampere.battery.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ampere.battery.Container
import dev.ampere.battery.Derived
import dev.ampere.battery.core.Fmt
import dev.ampere.battery.data.Prefs
import dev.ampere.battery.data.Settings
import dev.ampere.battery.domain.Bracket
import dev.ampere.battery.domain.Brackets
import dev.ampere.battery.domain.ChargeModel
import dev.ampere.battery.domain.Game
import dev.ampere.battery.domain.Grader
import dev.ampere.battery.domain.Severity
import dev.ampere.battery.domain.TempState
import dev.ampere.battery.domain.Thresholds
import dev.ampere.battery.domain.Warning
import dev.ampere.battery.monitor.Live
import dev.ampere.battery.monitor.Power
import dev.ampere.battery.ui.Nav
import dev.ampere.battery.ui.TabPage
import dev.ampere.battery.ui.component.AmpButton
import dev.ampere.battery.ui.component.Bar
import dev.ampere.battery.ui.component.Figure
import dev.ampere.battery.ui.component.Grid
import dev.ampere.battery.ui.component.Hairline
import dev.ampere.battery.ui.component.IconBtn
import dev.ampere.battery.ui.component.KV
import dev.ampere.battery.ui.component.Panel
import dev.ampere.battery.ui.component.Pill
import dev.ampere.battery.ui.component.Rolling
import dev.ampere.battery.ui.component.Ruler
import dev.ampere.battery.ui.component.Scope
import dev.ampere.battery.ui.component.Section
import dev.ampere.battery.ui.component.Segmented
import dev.ampere.battery.ui.component.T
import dev.ampere.battery.ui.component.TickDial
import dev.ampere.battery.ui.theme.Amp
import dev.ampere.battery.ui.theme.Motion
import dev.ampere.battery.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

fun plugName(p: Int) = when (p) {
    1 -> "Wall charger"
    2 -> "USB port"
    4 -> "Wireless"
    8 -> "Dock"
    0 -> "Unplugged"
    else -> "Charger"
}

@Composable
fun tempColor(s: TempState): Color = when (s) {
    TempState.COLD, TempState.CHILLY -> Amp.c.cold
    TempState.NORMAL -> Amp.c.good
    TempState.WARM -> Amp.c.warn
    else -> Amp.c.bad
}

@Composable
fun LiveScreen(c: Container, s: Settings, nav: Nav) {
    val l by c.engine.live.collectAsStateWithLifecycle()
    val d by c.derived.collectAsStateWithLifecycle()
    val dev by c.device.collectAsStateWithLifecycle()
    var timer by rememberSaveable { mutableStateOf(false) }
    TabPage(dev?.name ?: "Ampere", nav, sub = dev?.brand?.takeIf { it.isNotBlank() && dev?.name?.contains(it, true) != true }, actions = {
        IconBtn(Icons.Rounded.Timer, "Timer and target", tint = if (s.timerAt > 0) Amp.c.charge else Amp.c.dim) { timer = true }
    }) {
        item("dial") { Hero(l) }
        item("warn") { Warnings(l) }
        item("trio") { Trio(l) }
        if (l.plugged) {
            item("eta") { Eta(l, s) }
            item("charger") { Charger(l, dev?.spec?.wiredW, dev?.spec?.wirelessW, dev?.name) }
            item("splits") { Splits(l, d) }
        } else {
            item("left") { OnBattery(l, d) }
            item("whatif") { WhatIf(l, d, dev?.spec?.wiredW) }
        }
        item("temp") { Heat(l, s) }
        item("cell") { Cell(l) }
        if (!s.monitoring) item("mon") {
            Panel(onClick = { nav.go("settings") }) {
                T("Background tracking is off", Type.section)
                Spacer(Modifier.height(4.dp))
                T("Records, health and goals need it on to see whole charges. Tap to open settings.", Type.small, Amp.c.dim)
            }
        }
    }
    if (timer) TimerSheet(c, l, s) { timer = false }
}

@Composable
private fun Hero(l: Live) {
    val target = when {
        !l.ready -> Amp.c.faint
        l.level <= 15 && !l.plugged -> Amp.c.bad
        l.level <= 30 && !l.plugged -> Amp.c.warn
        else -> Amp.c.charge
    }
    val col by animateColorAsState(target, tween(600), label = "hc")
    val watts = abs(l.mwSmooth ?: 0) / 1000f
    val status = when (l.power) {
        Power.CHARGING -> "Charging"
        Power.FULL -> "Full"
        Power.PLUGGED_IDLE -> "Plugged in, paused"
        Power.BATTERY -> "On battery"
    }
    val line = when (l.power) {
        Power.CHARGING -> l.etaFullMs?.let { "Full in ${Fmt.duration(it)}" } ?: "Measuring speed"
        Power.FULL -> "Safe to unplug"
        Power.PLUGGED_IDLE -> "The phone is holding the charge"
        Power.BATTERY -> l.leftMs?.let { "About ${Fmt.duration(it)} left" } ?: "Measuring drain"
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TickDial(
            l.level, watts, l.charging, col, Modifier.widthIn(max = 330.dp).fillMaxWidth(0.88f),
            desc = "Battery ${l.level} percent. $status, ${Fmt.watts(l.mwSmooth)} watts. $line",
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(status, Type.small, Amp.c.dim)
                Row {
                    Rolling(if (l.ready) l.level.toFloat() else null, { it.roundToInt().toString() }, Type.hero, Modifier.alignByBaseline())
                    T("%", Type.figure, Amp.c.dim, Modifier.alignByBaseline().padding(start = 3.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val sign = if (l.mwSmooth == null) "" else if (l.mwSmooth >= 0) "+" else "−"
                    T(sign, Type.figure, Amp.c.power, maxLines = 1)
                    Figure(l.mwSmooth?.let { abs(it) / 1000f }, "W", { Fmt.dec(it, if (it >= 100) 0 else 1) }, Type.figure, Amp.c.power)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(line, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "line") {
            T(it, Type.section, align = TextAlign.Center)
        }
        val now = l.ts
        val sub = when {
            l.charging && l.etaFullMs != null -> "at ${Fmt.clock(now + l.etaFullMs)}" + (l.eta80Ms?.let { ", 80% at ${Fmt.clock(now + it)}" } ?: "")
            l.power == Power.BATTERY && l.leftMs != null -> "until ${Fmt.clock(now + l.leftMs)}"
            else -> ""
        }
        if (sub.isNotEmpty()) T(sub, Type.small, Amp.c.dim)
    }
}

@Composable
private fun Warnings(l: Live) {
    AnimatedVisibility(l.warnings.isNotEmpty(), enter = fadeIn() + expandVertically(Motion.move()), exit = fadeOut() + shrinkVertically(Motion.move())) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { l.warnings.forEach { WarningRow(it) } }
    }
}

@Composable
private fun WarningRow(w: Warning) {
    val col = when (w.severity) {
        Severity.BAD -> Amp.c.bad
        Severity.WARN -> Amp.c.warn
        Severity.INFO -> Amp.c.cold
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(col.copy(alpha = 0.12f)).padding(16.dp),
        Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Warning, null, Modifier.size(22.dp), tint = col)
        Column {
            T(w.title, Type.strong)
            T(w.text, Type.small, Amp.c.dim)
        }
    }
}

@Composable
private fun Trio(l: Live) {
    Grid(3, 10.dp) {
        Tile("Voltage", l.mv?.div(1000f), "V", { Fmt.dec(it, 2) }, Amp.c.volt, l.histMv, false)
        Tile("Current", l.uaSmooth?.let { abs(it) / 1000f }, "mA", { Fmt.int(it.roundToInt()) }, Amp.c.current, l.histUa, false)
        Tile("Power", l.mwSmooth?.let { abs(it) / 1000f }, "W", { Fmt.dec(it, 1) }, Amp.c.power, l.histMw, false)
    }
}

@Composable
private fun Tile(label: String, v: Float?, unit: String, f: (Float) -> String, col: Color, hist: FloatArray, baseline: Boolean) {
    Panel(pad = 14.dp) {
        T(label, Type.small, Amp.c.dim, maxLines = 1)
        Figure(v, unit, f, Type.figure.copy(fontSize = Type.figure.fontSize * 0.82f))
        Spacer(Modifier.height(8.dp))
        if (v != null) Scope(hist, col, height = 38.dp, baseline = baseline) else Spacer(Modifier.height(38.dp))
    }
}

@Composable
private fun Eta(l: Live, s: Settings) {
    Panel {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                T("To 80%", Type.small, Amp.c.dim)
                T(if (l.level >= 80) "Done" else Fmt.duration(l.eta80Ms), Type.figure)
                if (l.eta80Ms != null) T(Fmt.clock(l.ts + l.eta80Ms), Type.small, Amp.c.dim)
            }
            Column(Modifier.weight(1f)) {
                T("To 100%", Type.small, Amp.c.dim)
                T(if (l.power == Power.FULL) "Done" else Fmt.duration(l.etaFullMs), Type.figure)
                if (l.etaFullMs != null) T(Fmt.clock(l.ts + l.etaFullMs), Type.small, Amp.c.dim)
            }
        }
        val x = l.session
        if (x != null) {
            Spacer(Modifier.height(14.dp))
            Hairline()
            Spacer(Modifier.height(6.dp))
            KV("Plugged in for", Fmt.stopwatch(l.ts - x.startTs))
            KV("Gained", "${x.startLevel}% → ${x.endLevel}%")
            KV("Energy in", Fmt.wh(x.energyMwh))
            KV("Average power", "${Fmt.watts(x.avgMw)} W", sub = "peak ${Fmt.watts(x.peakMw)} W")
            if (x.gained >= 5) {
                val (score, _) = Grader.grade(x)
                KV("Grade so far", "${Grader.letter(score)}  $score", sub = "+${Game.sessionVp(score, x.gained)} VP if you unplug now")
            }
        }
        if (l.charging) {
            Spacer(Modifier.height(6.dp))
            val pm = if (l.level >= 80) 30 else if (l.etaLearned) 12 else 20
            T(
                (if (l.etaLearned) "Learned from how this phone really charges" else "From live current and a typical lithium taper") + ", ±$pm%. " +
                    (l.sysEtaMs?.let { "Android's own guess: ${Fmt.duration(it)}." } ?: ""),
                Type.small, Amp.c.faint,
            )
        }
    }
}

@Composable
private fun Charger(l: Live, wired: Float?, wireless: Float?, name: String?) {
    val rated = if (l.plug == 4) wireless?.takeIf { it > 0 } else wired
    val mw = abs(l.mwSmooth ?: 0)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(plugName(l.plug), Type.section, modifier = Modifier.weight(1f))
            if (l.charging && l.mwSmooth != null) Pill(Thresholds.chargeClass(mw), Amp.c.power)
        }
        Spacer(Modifier.height(10.dp))
        if (rated != null && l.mwSmooth != null) {
            val p by androidx.compose.animation.core.animateFloatAsState((mw / 1000f / rated).coerceIn(0f, 1f), Motion.settle(), label = "rate")
            Bar({ p }, Amp.c.power, height = 8.dp)
            Spacer(Modifier.height(8.dp))
            T("${Fmt.watts(mw)} W reaching the battery, of ${Fmt.dec(rated, if (rated % 1f == 0f) 0 else 1)} W the ${name ?: "phone"} supports", Type.small, Amp.c.dim)
            Spacer(Modifier.height(4.dp))
        }
        if (l.negotiatedMw != null) {
            KV("Charger offers", "${Fmt.dec((l.negotiatedMw ?: 0) / 1000f, 1)} W", sub = "${Fmt.volts(l.negotiatedMv)} V at ${Fmt.amps(l.negotiatedUa)} A, as reported by Android")
        }
        if (l.mw != null && l.mv != null) KV("Into the cell", "${Fmt.volts(l.mv)} V × ${Fmt.amps(l.ua)} A")
        l.uaSmooth?.takeIf { it > 0 }?.let { ua ->
            l.designMah?.let { KV("Charge rate", "${Fmt.dec(ua / 1000f / it, 2)} C", sub = "share of full capacity per hour") }
        }
        T("Measured at the battery. The wall side is always higher because some power becomes heat.", Type.small, Amp.c.faint)
    }
}

/** Speedrun-style splits: each ten percent of this charge against the fastest ever run. */
@Composable
private fun Splits(l: Live, d: Derived) {
    val m = l.marks ?: return
    val x = l.session ?: return
    if (x.gained < 2) return
    val start = x.startLevel
    val best = remember(d.stats.chargeCount, start / 10) {
        d.stats.decoded().filter { (s, _) -> s.startLevel <= start + 1 && s.endLevel >= minOf(100, start + 20) }
            .minByOrNull { (s, mm) ->
                val to = minOf(100, start + 20)
                (dev.ampere.battery.domain.Marks.time(mm, start, to) ?: Int.MAX_VALUE).also { _ -> s.id }
            }?.second
    }
    val stops = (start / 10 + 1..10).map { it * 10 }.filter { it > start }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Splits", Type.section, modifier = Modifier.weight(1f))
            T(if (best != null) "vs your best" else "first run", Type.small, Amp.c.dim)
        }
        Spacer(Modifier.height(6.dp))
        stops.forEach { p ->
            val t = m[p].takeIf { it >= 0 }
            val b = best?.let { dev.ampere.battery.domain.Marks.time(it, start, p) }
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                T("$p%", Type.mono, if (t != null) Amp.c.text else Amp.c.faint, Modifier.weight(1f))
                if (t != null && b != null) {
                    val delta = t - b
                    T((if (delta <= 0) "−" else "+") + Fmt.split(abs(delta)), Type.mono, if (delta <= 0) Amp.c.good else Amp.c.bad, Modifier.weight(1f), align = TextAlign.End)
                } else {
                    T(if (b != null) Fmt.split(b) else "", Type.mono, Amp.c.faint, Modifier.weight(1f), align = TextAlign.End)
                }
                T(t?.let { Fmt.split(it) } ?: Fmt.NONE, Type.mono, if (t != null) Amp.c.text else Amp.c.faint, Modifier.weight(1f), align = TextAlign.End)
            }
        }
    }
}

@Composable
private fun OnBattery(l: Live, d: Derived) {
    Panel {
        T("This discharge", Type.section)
        Spacer(Modifier.height(4.dp))
        val x = l.session
        KV("Unplugged for", if (x != null) Fmt.stopwatch(l.ts - x.startTs) else Fmt.NONE)
        KV("Screen on", Fmt.duration(x?.screenOnMs))
        KV("Used", x?.let { "${it.startLevel - it.endLevel}%" } ?: Fmt.NONE, sub = x?.energyMwh?.takeIf { it > 10 }?.let { Fmt.wh(it) })
        val per = l.uaSmooth?.let { abs(it) / 1000f }
        val full = d.health.fullMah ?: l.designMah
        if (per != null && full != null) KV("Draining now", "${Fmt.dec(per / full * 100, 1)}% / h", sub = "${Fmt.int(per.roundToInt())} mA")
        d.stats.drainOnPctH?.let { KV("Your usual, screen on", "${Fmt.dec(it, 1)}% / h") }
        d.stats.drainOffPctH?.let { KV("Your usual, screen off", "${Fmt.dec(it, 1)}% / h") }
    }
}

@Composable
private fun WhatIf(l: Live, d: Derived, phoneMax: Float?) {
    val full = d.health.fullMah ?: l.designMah ?: return
    var target by rememberSaveable { mutableIntStateOf(1) }
    val to = if (target == 0) 80 else 100
    Panel {
        T("If you plugged in now", Type.section)
        Spacer(Modifier.height(10.dp))
        Segmented(listOf("To 80%", "To 100%"), target) { target = it }
        Spacer(Modifier.height(8.dp))
        if (l.level >= to) {
            T("Already there.", Type.body, Amp.c.dim, Modifier.padding(vertical = 8.dp))
        } else {
            listOf(5f, 10f, 15f, 25f, 45f, 65f, 100f).filter { phoneMax == null || it <= phoneMax || it == 5f || it == 10f }
                .let { if (phoneMax != null && phoneMax !in it) (it + phoneMax).sorted() else it }
                .forEach { w ->
                    val t = ChargeModel.whatIf(l.level, to, w, phoneMax, full * 1000)
                    KV("${Fmt.dec(w, if (w % 1f == 0f) 0 else 1)} W charger", "≈ ${Fmt.duration(t)}", sub = if (phoneMax != null && w == phoneMax) "fastest this phone takes" else null)
                }
            T("Estimates from your battery size and a typical taper.", Type.small, Amp.c.faint)
        }
    }
}

@Composable
private fun Heat(l: Live, s: Settings) {
    val t = l.tempC
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("Battery temperature", Type.small, Amp.c.dim)
                Rolling(t, { Fmt.temp(it, s.fahrenheit) }, Type.figure)
            }
            if (t != null) Thresholds.temp(t).let { Pill(it.label, tempColor(it)) }
        }
        Spacer(Modifier.height(8.dp))
        if (t != null) {
            Scope(l.histTemp, Amp.c.heat, height = 40.dp)
            Spacer(Modifier.height(10.dp))
            T(Thresholds.tempCopy(Thresholds.temp(t)), Type.small, Amp.c.dim)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("Throttling", Type.small, Amp.c.dim, Modifier.weight(1f))
            T(Thresholds.thermalLabels.getOrElse(l.thermal) { "Unknown" }, Type.strong, if (l.thermal >= 2) Amp.c.bad else Amp.c.text)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
            for (i in 0..6) {
                val on = i <= l.thermal
                val col by animateColorAsState(if (!on) Amp.c.raised else if (l.thermal < 1) Amp.c.good else if (l.thermal < 3) Amp.c.warn else Amp.c.bad, label = "th")
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(col))
            }
        }
        l.headroom?.let {
            Spacer(Modifier.height(12.dp))
            KV("Thermal load", Fmt.pct(it * 100), sub = "100% is where the system starts to throttle")
        }
    }
}

@Composable
private fun Cell(l: Live) {
    Panel {
        T("Cell", Type.section)
        Spacer(Modifier.height(4.dp))
        KV("Charge now", l.cc?.let { "${Fmt.mah(it)} mAh" } ?: Fmt.NONE)
        KV("Chemistry", l.tech ?: Fmt.NONE)
        KV("System health flag", healthFlag(l.healthFlag), color = if (l.healthFlag in listOf(3, 4, 5, 6)) Amp.c.bad else Amp.c.text)
        KV("Cycle count", l.cycleCount?.let { Fmt.int(it) } ?: "not reported")
    }
}

fun healthFlag(h: Int) = when (h) {
    2 -> "Good"
    3 -> "Overheat"
    4 -> "Dead"
    5 -> "Over voltage"
    6 -> "Failure"
    7 -> "Cold"
    else -> "Unknown"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerSheet(c: Container, l: Live, s: Settings, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mins by rememberSaveable { mutableIntStateOf(30) }
    ModalBottomSheet(close, sheetState = rememberModalBottomSheetState(true), containerColor = Amp.c.surface, contentColor = Amp.c.text) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp), Arrangement.spacedBy(6.dp)) {
            T("Charge target", Type.section)
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("${s.alertFullAt}%", Type.big, modifier = Modifier.weight(1f))
                val eta = if (l.charging && l.uaSmooth != null && l.level < s.alertFullAt && l.designMah != null) {
                    ChargeModel.eta(l.level, s.alertFullAt, l.uaSmooth, l.designMah * 1000, null)
                } else {
                    null
                }
                if (eta != null) T("in about ${Fmt.duration(eta)}", Type.small, Amp.c.dim)
            }
            Ruler(s.alertFullAt, 50..100, 1, Amp.c.charge, label = "Charge target percent") { v ->
                scope.launch {
                    c.prefs.edit {
                        it[Prefs.K.alertFullAt] = v
                        it[Prefs.K.alertFull] = true
                    }
                }
            }
            T(if (s.monitoring) "You get a notification when the battery reaches this level." else "Turn on background tracking in settings to be notified.", Type.small, Amp.c.dim)
            Spacer(Modifier.height(14.dp))
            Hairline()
            Spacer(Modifier.height(10.dp))
            T("Countdown", Type.section)
            if (s.timerAt > l.ts) {
                T(Fmt.stopwatch(s.timerAt - l.ts), Type.big.copy(fontFeatureSettings = "tnum"))
                T("Rings at ${Fmt.clock(s.timerAt)}", Type.small, Amp.c.dim)
                Spacer(Modifier.height(8.dp))
                AmpButton("Cancel timer", Modifier.fillMaxWidth(), primary = false) { scope.launch { c.setTimer(0) } }
            } else {
                T(Fmt.duration(mins * 60_000L), Type.big)
                Ruler(mins, 5..180, 5, Amp.c.current, label = "Countdown minutes") { mins = it }
                Spacer(Modifier.height(8.dp))
                AmpButton("Start timer", Modifier.fillMaxWidth()) { scope.launch { c.setTimer(System.currentTimeMillis() + mins * 60_000L) } }
            }
        }
    }
}
