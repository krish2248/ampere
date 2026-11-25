package dev.ampere.battery.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ampere.battery.Container
import dev.ampere.battery.Derived
import dev.ampere.battery.core.Fmt
import dev.ampere.battery.data.Prefs
import dev.ampere.battery.data.Settings
import dev.ampere.battery.domain.Confidence
import dev.ampere.battery.domain.HealthCalc
import dev.ampere.battery.domain.Insights
import dev.ampere.battery.hw.DesignSource
import dev.ampere.battery.monitor.Live
import dev.ampere.battery.ui.Nav
import dev.ampere.battery.ui.TabPage
import dev.ampere.battery.ui.component.Bars
import dev.ampere.battery.ui.component.CellViz
import dev.ampere.battery.ui.component.Dot
import dev.ampere.battery.ui.component.Empty
import dev.ampere.battery.ui.component.Expandable
import dev.ampere.battery.ui.component.Grid
import dev.ampere.battery.ui.component.Hairline
import dev.ampere.battery.ui.component.IconBtn
import dev.ampere.battery.ui.component.KV
import dev.ampere.battery.ui.component.LineChart
import dev.ampere.battery.ui.component.Panel
import dev.ampere.battery.ui.component.Pill
import dev.ampere.battery.ui.component.Points
import dev.ampere.battery.ui.component.Rolling
import dev.ampere.battery.ui.component.Section
import dev.ampere.battery.ui.component.StatTile
import dev.ampere.battery.ui.component.T
import dev.ampere.battery.ui.theme.Amp
import dev.ampere.battery.ui.theme.Type
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun healthColor(p: Float?) = when {
    p == null -> Amp.c.faint
    p >= 85 -> Amp.c.good
    p >= 75 -> Amp.c.warn
    else -> Amp.c.bad
}

@Composable
fun HealthScreen(c: Container, s: Settings, nav: Nav) {
    val d by c.derived.collectAsStateWithLifecycle()
    val l by c.engine.live.collectAsStateWithLifecycle()
    var edit by rememberSaveable { mutableStateOf(false) }
    var export by rememberSaveable { mutableStateOf(false) }
    val design = remember(s.manualDesignMah) { c.design.resolve(s.manualDesignMah) }
    TabPage("Health", nav, actions = { IconBtn(Icons.Rounded.IosShare, "Export report", tint = Amp.c.dim) { export = true } }) {
        item("cap") { Capacity(d, l) }
        item("how") { How(d, design.source, design.mah) { edit = true } }
        item("cyc") { Cycles(d, l, s) }
        item("trend") { Trend(d) }
        item("heat") { HeatExposure(d, s) }
        val ins = Insights.of(d.stats, d.health)
        item("habit") { Habits(d) }
        if (ins.isNotEmpty()) {
            item("inh") { Section("What the data says") }
            item("ins") {
                Panel {
                    ins.forEachIndexed { i, x ->
                        if (i > 0) Hairline(Modifier.padding(vertical = 12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.padding(top = 7.dp)) { Dot(if (x.good == null) Amp.c.cold else if (x.good) Amp.c.good else Amp.c.warn) }
                            Column {
                                T(x.title, Type.strong)
                                T(x.text, Type.small, Amp.c.dim)
                            }
                        }
                    }
                }
            }
        }
    }
    if (edit) DesignDialog(c, s) { edit = false }
    if (export) ExportSheet(c, s, d) { export = false }
}

@Composable
private fun Capacity(d: Derived, l: Live) {
    val h = d.health
    val col = healthColor(h.pct)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Real capacity", Type.small, Amp.c.dim, Modifier.weight(1f))
            Pill(h.confidence.label, if (h.confidence == Confidence.MEASURED) Amp.c.good else Amp.c.warn)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f)) {
                if (h.pct != null && h.confidence != Confidence.MEASURED) T("~", Type.big, Amp.c.dim, Modifier.alignByBaseline())
                Rolling(h.pct, { it.roundToInt().toString() }, Type.hero, Modifier.alignByBaseline())
                if (h.pct != null) T("%", Type.figure, Amp.c.dim, Modifier.alignByBaseline().padding(start = 3.dp))
            }
            if (h.pct != null) T(HealthCalc.band(h.pct).first, Type.section, col, Modifier.padding(bottom = 16.dp))
        }
        CellViz((h.pct ?: 0f) / 100f, col)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            Big("Original", h.designMah, Modifier.weight(1f))
            Big("Now", h.fullMah, Modifier.weight(1f))
            Big("Lost", if (h.designMah != null && h.fullMah != null) (h.designMah - h.fullMah).coerceAtLeast(0) else null, Modifier.weight(0.8f))
        }
        Spacer(Modifier.height(16.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))
        T(
            when {
                h.pct == null && h.designMah == null -> "Ampere could not find this phone's original capacity. Add it below from the spec sheet."
                h.pct == null -> "Keep tracking on and charge by at least 25% in one go. Three charges give a solid figure."
                h.pct > 100.5f -> "Your phone says ${l.level}%, and its full 100% holds slightly more than the rated figure. " + HealthCalc.band(h.pct).second
                else -> "Your phone always calls a full battery 100%. Yours now holds what ${h.pct.roundToInt()}% of a new one would, so today's ${l.level}% is about ${(l.level * h.pct / 100).roundToInt()}% of the original. " + HealthCalc.band(h.pct).second
            },
            Type.small, Amp.c.dim,
        )
    }
}

@Composable
private fun Big(label: String, mah: Int?, modifier: Modifier) {
    Column(modifier) {
        T(label, Type.small, Amp.c.dim)
        Row {
            T(mah?.let { Fmt.int(it) } ?: Fmt.NONE, Type.figure.copy(fontSize = Type.figure.fontSize * 0.85f), modifier = Modifier.alignByBaseline(), maxLines = 1)
            if (mah != null) T(" mAh", Type.tiny, Amp.c.dim, Modifier.alignByBaseline(), 1)
        }
    }
}

@Composable
private fun How(d: Derived, src: DesignSource, mah: Int?, edit: () -> Unit) {
    val h = d.health
    Panel {
        T("How sure is this", Type.section)
        Spacer(Modifier.height(4.dp))
        KV("Method", h.method.replaceFirstChar { it.uppercase() })
        KV("Charges measured", "${h.count}", sub = if (h.count < 3) "3 make it solid" else null)
        if (h.minMah != null && h.maxMah != null && h.count > 1) KV("Spread", "${Fmt.int(h.minMah)} to ${Fmt.int(h.maxMah)} mAh")
        h.systemSoh?.let { KV("Android state of health", "$it%") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            KV("Original capacity", mah?.let { "${Fmt.int(it)} mAh" } ?: "Not found", Modifier.weight(1f), sub = src.label)
            IconBtn(Icons.Rounded.Edit, "Edit original capacity", tint = Amp.c.dim, onClick = edit)
        }
        Expandable("How it works") {
            T(
                "A fuel gauge counts the charge flowing in. If 2,000 mAh fills 50% of the battery, all of it holds 4,000 mAh. " +
                    "Ampere does this on every charge of 25% or more, skips any that were too hot, too cold or interrupted, and takes a weighted median of the last ten. " +
                    "Until the first one it shows a quick read: charge now divided by the percentage. Some phones derive that counter from the original capacity, which makes a quick read look perfect. Ampere checks for this and switches to counting current itself.",
                Type.small, Amp.c.dim, Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun Cycles(d: Derived, l: Live, s: Settings) {
    Grid(2) {
        StatTile("Cycles tracked", Fmt.dec(d.stats.cycles, 1), if (s.installedAt > 0) "since ${Fmt.date(s.installedAt)}" else null)
        StatTile("Lifetime cycles", l.cycleCount?.let { Fmt.int(it) } ?: Fmt.NONE, if (l.cycleCount != null) "counted by the phone" else "not reported by this phone")
        StatTile("Energy charged", Fmt.wh(d.stats.energyMwh), "${Fmt.dec(d.stats.ahIn, 1)} Ah through the cell")
        StatTile("Avg charge grade", d.stats.avgScore?.let { dev.ampere.battery.domain.Grader.letter(it.roundToInt()) } ?: Fmt.NONE, d.stats.avgScore?.let { "score ${it.roundToInt()}" })
    }
}

@Composable
private fun Trend(d: Derived) {
    val design = d.health.designMah ?: return
    val pts = remember(d.estimates, design) {
        val e = d.estimates.sortedBy { it.ts }
        Points(LongArray(e.size) { e[it].ts }, FloatArray(e.size) { e[it].uah / 10f / design })
    }
    Panel {
        T("Capacity over time", Type.section)
        Spacer(Modifier.height(8.dp))
        if (pts.x.size < 2) {
            Empty("Not enough charges yet", "Each charge of 25% or more adds a point.")
        } else {
            LineChart(pts, Amp.c.charge, height = 170.dp, fmtY = { Fmt.pct(it, 1) }, fmtX = { Fmt.dayShort(it) }, dots = pts.x.size < 40, desc = "Capacity estimates over time")
            d.health.trendPerMonth?.let { t ->
                Spacer(Modifier.height(10.dp))
                T(
                    "${if (t > 0) "+" else ""}${Fmt.dec(t, 2)}% per month." + (d.health.eightyAt?.let { " At this rate it reaches 80% around ${Fmt.date(it).substringAfter(' ')}. A rough projection." } ?: ""),
                    Type.small, Amp.c.dim,
                )
            }
        }
    }
}

@Composable
private fun HeatExposure(d: Derived, s: Settings) {
    val today = LocalDate.now().toEpochDay()
    val map = d.stats.days.associateBy { it.day }
    val vals = (13 downTo 0).map { (map[today - it]?.heat40Ms ?: 0) / 60_000f }
    val labels = (13 downTo 0).map { Fmt.dayShort(Fmt.dayStart(today - it)) }
    Panel {
        T("Heat exposure", Type.section)
        T("Minutes above ${Fmt.temp(40f, s.fahrenheit, 0)} per day, last 14 days", Type.small, Amp.c.dim)
        Spacer(Modifier.height(6.dp))
        if (vals.all { it == 0f }) Empty("No heat recorded", "That is the best thing for a battery.")
        else Bars(vals, labels, Amp.c.heat, { "${it.roundToInt()} min" }, height = 140.dp, every = 4, desc = "Minutes above 40 degrees per day")
        KV("All time above ${Fmt.temp(40f, s.fahrenheit, 0)}", Fmt.duration(d.stats.heat40Ms))
        KV("All time above ${Fmt.temp(43f, s.fahrenheit, 0)}", Fmt.duration(d.stats.heat43Ms))
        KV("Hottest ever", d.stats.maxTempT?.let { Fmt.temp(it / 10f, s.fahrenheit) } ?: Fmt.NONE)
    }
}

@Composable
private fun Habits(d: Derived) {
    val x = d.stats
    Panel {
        T("Habits", Type.section)
        Spacer(Modifier.height(4.dp))
        KV("You plug in at", x.avgPlugIn?.let { "${it.roundToInt()}%" } ?: Fmt.NONE)
        KV("You unplug at", x.avgUnplug?.let { "${it.roundToInt()}%" } ?: Fmt.NONE)
        KV("Kept within 20 to 80%", "${x.gentle} charge${if (x.gentle == 1) "" else "s"}")
        KV("Charged to 100%", "${x.toFull}")
        KV("Started under 10%", "${x.fromUnder10}")
        KV("Long overnight charges", "${x.nightCharges}")
    }
}

@Composable
fun DesignDialog(c: Container, s: Settings, close: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(if (s.manualDesignMah > 0) s.manualDesignMah.toString() else "") }
    val scope = rememberCoroutineScope()
    val v = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = close,
        containerColor = Amp.c.surface,
        title = { T("Original capacity", Type.section) },
        text = {
            Column {
                T("From the phone's spec sheet, in mAh. Leave empty to detect it automatically.", Type.small, Amp.c.dim)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    text, { text = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(), singleLine = true, textStyle = Type.body,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), suffix = { T("mAh", Type.small, Amp.c.dim) },
                    isError = text.isNotEmpty() && (v == null || v !in 500..30_000),
                    supportingText = { if (text.isNotEmpty() && (v == null || v !in 500..30_000)) T("Enter a number from 500 to 30,000", Type.small, Amp.c.bad) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Amp.c.text, unfocusedBorderColor = Amp.c.line, cursorColor = Amp.c.text),
                )
            }
        },
        confirmButton = {
            TextButton({
                scope.launch {
                    c.prefs.edit { it[Prefs.K.design] = v ?: 0 }
                    close()
                }
            }, enabled = text.isEmpty() || (v != null && v in 500..30_000)) { T("Save", Type.strong) }
        },
        dismissButton = { TextButton(close) { T("Cancel", Type.strong, Amp.c.dim) } },
    )
    Spacer(Modifier.width(0.dp))
}
