package dev.ampere.battery.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ampere.battery.Container
import dev.ampere.battery.Derived
import dev.ampere.battery.core.Fmt
import dev.ampere.battery.data.Sample
import dev.ampere.battery.data.Session
import dev.ampere.battery.data.Settings
import dev.ampere.battery.domain.Game
import dev.ampere.battery.domain.Grader
import dev.ampere.battery.domain.Lttb
import dev.ampere.battery.domain.Marks
import dev.ampere.battery.domain.Thresholds
import dev.ampere.battery.ui.Nav
import dev.ampere.battery.ui.SubPage
import dev.ampere.battery.ui.TabPage
import dev.ampere.battery.ui.component.Bars
import dev.ampere.battery.ui.component.ChargeMap
import dev.ampere.battery.ui.component.Empty
import dev.ampere.battery.ui.component.Grid
import dev.ampere.battery.ui.component.Hairline
import dev.ampere.battery.ui.component.IconBtn
import dev.ampere.battery.ui.component.KV
import dev.ampere.battery.ui.component.LineChart
import dev.ampere.battery.ui.component.Panel
import dev.ampere.battery.ui.component.Pill
import dev.ampere.battery.ui.component.Points
import dev.ampere.battery.ui.component.Section
import dev.ampere.battery.ui.component.Segmented
import dev.ampere.battery.ui.component.ShareBar
import dev.ampere.battery.ui.component.StatTile
import dev.ampere.battery.ui.component.T
import dev.ampere.battery.ui.theme.Amp
import dev.ampere.battery.ui.theme.Type
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private class SeriesSet(val level: Points, val power: Points, val temp: Points, val volt: Points)

private fun series(list: List<Sample>, n: Int = 400): SeriesSet {
    fun pts(f: (Sample) -> Float?): Points {
        val ok = list.filter { f(it) != null }
        val x = LongArray(ok.size) { ok[it].ts }
        val y = FloatArray(ok.size) { f(ok[it])!! }
        val k = Lttb.pick(x, y, n)
        return Points(LongArray(k.size) { x[k[it]] }, FloatArray(k.size) { y[k[it]] })
    }
    return SeriesSet(pts { it.level.toFloat() }, pts { it.mw?.let { m -> m / 1000f } }, pts { s -> s.tempT.takeIf { it != 0 }?.let { it / 10f } }, pts { it.mv?.let { v -> v / 1000f } })
}

@Composable
fun StatsScreen(c: Container, s: Settings, nav: Nav) {
    val d by c.derived.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableIntStateOf(0) }
    var export by rememberSaveable { mutableStateOf(false) }
    TabPage("Stats", nav, actions = { IconBtn(Icons.Rounded.IosShare, "Export report", tint = Amp.c.dim) { export = true } }) {
        item("range") { Segmented(listOf("24 h", "7 days", "30 days", "All"), range) { range = it } }
        if (range < 2) item("charts") { SampleCharts(c, s, if (range == 0) 86_400_000L else 7 * 86_400_000L, range) }
        else item("dayc") { DayCharts(d, s, if (range == 2) 30 else 365) }
        item("sum") { Summary(d, s, when (range) { 0 -> 1; 1 -> 7; 2 -> 30; else -> 100_000 }) }
        item("drainh") { Section("Drain") }
        item("drain") {
            Grid(2) {
                StatTile("Screen on", d.stats.drainOnPctH?.let { "${Fmt.dec(it, 1)}%/h" } ?: Fmt.NONE, d.stats.drainOnPctH?.let { "${Fmt.dec(100 / it, 1)} h per full charge" } ?: "needs a few discharges")
                StatTile("Screen off", d.stats.drainOffPctH?.let { "${Fmt.dec(it, 1)}%/h" } ?: Fmt.NONE, d.stats.drainOffPctH?.let { "${Fmt.dec(100 / it / 24, 1)} days idle" } ?: "needs a few discharges")
                StatTile("Best screen time", Fmt.duration(d.stats.bestScreenOnMs.takeIf { it > 0 }), "on one charge")
                StatTile("Longest unplugged", Fmt.duration(d.stats.longestDrainMs.takeIf { it > 0 }))
            }
        }
        item("pat") { Section("Charging patterns") }
        item("map") {
            Panel {
                T("Charge map", Type.section)
                T("From (rows) to (columns)", Type.small, Amp.c.dim)
                Spacer(Modifier.height(8.dp))
                if (d.stats.chargeCount == 0) Empty("No charges yet", "Your first charge lights up a cell.") else ChargeMap(d.stats.matrix, Amp.c.charge)
            }
        }
        if (d.stats.chargeCount > 0) {
            item("hours") {
                Panel {
                    T("When you plug in", Type.section)
                    Bars(d.stats.hours.map { it.toFloat() }, List(24) { "%02d".format(it) }, Amp.c.current, { "${it.roundToInt()}×" }, height = 130.dp, every = 4, desc = "Charges by hour of day")
                }
            }
            item("plug") {
                Panel {
                    T("Where the power comes from", Type.section)
                    Spacer(Modifier.height(12.dp))
                    val p = d.stats.plugs
                    ShareBar(listOf(Triple("Wall", (p[1] ?: 0).toFloat(), Amp.c.power), Triple("USB", (p[2] ?: 0).toFloat(), Amp.c.current), Triple("Wireless", (p[4] ?: 0).toFloat(), Amp.c.volt)))
                    Spacer(Modifier.height(18.dp))
                    T("Charge grades", Type.section)
                    Spacer(Modifier.height(12.dp))
                    Grades(d.stats.grades)
                }
            }
        }
        item("sesh") {
            Section("Sessions") {
                T("See all", Type.strong, Amp.c.dim, Modifier.clip(CircleShape).clickable { nav.go("sessions") }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        val recent = (d.stats.charges + d.stats.drains).sortedByDescending { it.startTs }.take(5)
        item("ses") {
            Panel(pad = 8.dp) {
                if (recent.isEmpty()) Empty("Nothing recorded yet", "Sessions appear after you plug in or unplug.")
                recent.forEach { SessionRow(it, s) { nav.go("session/${it.id}") } }
            }
        }
    }
    if (export) ExportSheet(c, s, d) { export = false }
}

@Composable
private fun SampleCharts(c: Container, s: Settings, span: Long, key: Int) {
    val data by produceState<SeriesSet?>(null, key) {
        value = withContext(Dispatchers.Default) { series(c.dao.samples(System.currentTimeMillis() - span, Long.MAX_VALUE)) }
    }
    val x = data
    val fx: (Long) -> String = { if (key == 0) Fmt.clock(it) else Fmt.dayShort(it) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (x != null && x.level.x.size < 3) Panel { Empty("Collecting data", "Charts fill in as Ampere watches the battery. Background tracking makes them continuous.") }
        else if (x != null) {
            ChartPanel("Battery level", x.level, Amp.c.charge, { Fmt.pct(it) }, fx, 0f, 100f)
            if (x.power.x.size > 2) ChartPanel("Power", x.power, Amp.c.power, { "${Fmt.dec(it, 1)} W" }, fx, sub = "above zero is charging")
            if (x.temp.x.size > 2) ChartPanel("Temperature", x.temp, Amp.c.heat, { Fmt.temp(it, s.fahrenheit, 0) }, fx)
            if (x.volt.x.size > 2) ChartPanel("Voltage", x.volt, Amp.c.volt, { "${Fmt.dec(it, 2)} V" }, fx)
        }
    }
}

@Composable
fun ChartPanel(title: String, p: Points, col: Color, fy: (Float) -> String, fx: (Long) -> String, min: Float? = null, max: Float? = null, sub: String? = null, gap: Long = 3 * 3_600_000L) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(title, Type.section, modifier = Modifier.weight(1f))
            p.y.lastOrNull()?.let { T(fy(it), Type.strong, col) }
        }
        if (sub != null) T(sub, Type.small, Amp.c.dim)
        Spacer(Modifier.height(8.dp))
        LineChart(p, col, height = 150.dp, fmtY = fy, fmtX = fx, yMin = min, yMax = max, gapMs = gap, desc = "$title chart")
    }
}

@Composable
private fun DayCharts(d: Derived, s: Settings, n: Int) {
    val today = LocalDate.now().toEpochDay()
    val days = remember(d.stats.days, n) { d.stats.days.filter { it.day > today - n } }
    if (days.size < 2) {
        Panel { Empty("Needs two days of data", "Come back tomorrow for daily trends.") }
        return
    }
    val span = (today - days.first().day + 1).toInt().coerceIn(7, 60)
    val map = days.associateBy { it.day }
    val ids = (span - 1 downTo 0).map { today - it }
    val labels = ids.map { Fmt.dayShort(Fmt.dayStart(it)) }
    val every = (span / 4).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            T("Charge used per day", Type.section)
            Bars(ids.map { (map[it]?.drainedPct ?: 0).toFloat() }, labels, Amp.c.charge, { "${it.roundToInt()}%" }, every = every, desc = "Percent drained per day")
        }
        Panel {
            T("Screen time", Type.section)
            Bars(ids.map { (map[it]?.screenOnMs ?: 0) / 3.6e6f }, labels, Amp.c.current, { "${Fmt.dec(it, 1)} h" }, every = every, desc = "Screen time per day")
        }
        Panel {
            T("Energy charged", Type.section)
            Bars(ids.map { (map[it]?.energyInMwh ?: 0f) / 1000f }, labels, Amp.c.power, { "${Fmt.dec(it, 1)} Wh" }, every = every, desc = "Energy charged per day")
        }
        val t = days.filter { it.maxTempT != Int.MIN_VALUE }
        if (t.size > 2) ChartPanel("Peak temperature", Points(LongArray(t.size) { Fmt.dayStart(t[it].day) }, FloatArray(t.size) { t[it].maxTempT / 10f }), Amp.c.heat,
            { Fmt.temp(it, s.fahrenheit, 0) }, { Fmt.dayShort(it) }, gap = 4 * 86_400_000L)
    }
}

@Composable
private fun Summary(d: Derived, s: Settings, days: Int) {
    val from = System.currentTimeMillis() - days * 86_400_000L
    val ch = d.stats.charges.filter { it.startTs >= from }
    val dr = d.stats.drains.filter { it.startTs >= from }
    Grid(2) {
        StatTile("Charges", "${ch.size}", d.stats.chargesPerDay?.let { "${Fmt.dec(it, 1)} a day on average" })
        StatTile("Cycles", Fmt.dec(ch.sumOf { it.gained } / 100f, 2), "${ch.sumOf { it.gained }}% added")
        StatTile("Energy in", Fmt.wh(ch.sumOf { it.energyMwh.toDouble() }.toFloat()))
        StatTile("Peak power", ch.maxOfOrNull { it.peakMw }?.let { "${Fmt.watts(it)} W" } ?: Fmt.NONE, d.stats.bestPctPerMin?.let { "best ${Fmt.dec(it, 2)} %/min" })
        StatTile("Time charging", Fmt.duration(ch.sumOf { it.durationMs }))
        StatTile("Screen on", Fmt.duration(dr.sumOf { it.screenOnMs }), "while unplugged")
    }
}

@Composable
private fun Grades(g: Map<String, Int>) {
    val top = g.values.maxOrNull() ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("A", "B", "C", "D", "F").forEach { k ->
            val n = g[k] ?: 0
            val col = gradeColor(k)
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(k, Type.mono, col, Modifier.width(24.dp))
                val track = Amp.c.raised
                Spacer(Modifier.weight(1f).height(8.dp).drawBehind {
                    drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
                    if (n > 0) drawRoundRect(col, Offset.Zero, Size(size.width * n / top, size.height), CornerRadius(size.height / 2))
                })
                T("$n", Type.mono, Amp.c.dim, Modifier.width(44.dp), align = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

@Composable
fun gradeColor(g: String): Color = when (g) {
    "A" -> Amp.c.good
    "B" -> Amp.c.current
    "C" -> Amp.c.warn
    "D", "F" -> Amp.c.bad
    else -> Amp.c.faint
}

@Composable
fun SessionRow(x: Session, s: Settings, onClick: () -> Unit) {
    val g = Grader.letter(x.score)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
    ) {
        val col = if (x.isCharge) gradeColor(g) else Amp.c.dim
        Box(Modifier.size(38.dp).clip(CircleShape).background(col.copy(alpha = 0.14f)), Alignment.Center) {
            T(if (x.isCharge) g else "−", Type.strong, col)
        }
        Column(Modifier.weight(1f)) {
            T("${x.startLevel}% → ${x.endLevel}%", Type.strong)
            T("${Fmt.dayLabel(x.startTs)}, ${Fmt.clock(x.startTs)}", Type.small, Amp.c.dim, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            T(Fmt.duration(x.durationMs), Type.strong)
            T(if (x.isCharge) "${Fmt.watts(x.avgMw)} W avg" else "screen ${Fmt.duration(x.screenOnMs)}", Type.small, Amp.c.dim)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(c: Container, s: Settings, nav: Nav) {
    val d by c.derived.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val list = if (tab == 0) d.stats.charges else d.stats.drains
    val groups = remember(list) { list.groupBy { Fmt.epochDay(it.startTs) } }
    SubPage("Sessions", nav) {
        item { Segmented(listOf("Charges", "Discharges"), tab) { tab = it } }
        if (list.isEmpty()) item { Panel { Empty("Nothing here yet", if (tab == 0) "Plug in to record a charge." else "Unplug to record a discharge.") } }
        groups.forEach { (day, xs) ->
            item("h$day-$tab") { T(Fmt.dayLabel(xs.first().startTs), Type.small, Amp.c.dim, Modifier.padding(start = 4.dp, top = 8.dp)) }
            item("g$day-$tab") { Panel(Modifier.animateItem(), 8.dp) { xs.forEach { SessionRow(it, s) { nav.go("session/${it.id}") } } } }
        }
    }
}

@Composable
fun SessionScreen(c: Container, s: Settings, nav: Nav, id: Long) {
    val x by remember(id) { c.dao.session(id) }.collectAsStateWithLifecycle(null)
    val samples by remember(id) { c.dao.sessionSamples(id) }.collectAsStateWithLifecycle(emptyList())
    val set = remember(samples) { if (samples.size > 2) series(samples, 300) else null }
    var ask by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ses = x
    SubPage(if (ses == null) "Session" else if (ses.isCharge) "Charge" else "Discharge", nav, actions = {
        IconBtn(Icons.Rounded.DeleteOutline, "Delete session", tint = Amp.c.dim) { ask = true }
    }) {
        if (ses == null) return@SubPage
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("${ses.startLevel}% → ${ses.endLevel}%", Type.big)
                        T("${Fmt.dayLabel(ses.startTs)}, ${Fmt.clock(ses.startTs)} to ${Fmt.clock(ses.endTs)}", Type.small, Amp.c.dim)
                    }
                    if (ses.isCharge && ses.score >= 0) {
                        val g = Grader.letter(ses.score)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            T(g, Type.big, gradeColor(g))
                            T("${ses.score}/100", Type.small, Amp.c.dim)
                        }
                    }
                }
            }
        }
        if (set != null) {
            val fx: (Long) -> String = { Fmt.clock(it) }
            item { ChartPanel("Level", set.level, Amp.c.charge, { Fmt.pct(it) }, fx) }
            if (set.power.x.size > 2) item { ChartPanel("Power", Points(set.power.x, FloatArray(set.power.y.size) { abs(set.power.y[it]) }), Amp.c.power, { "${Fmt.dec(it, 1)} W" }, fx, 0f) }
            if (set.temp.x.size > 2) item { ChartPanel("Temperature", set.temp, Amp.c.heat, { Fmt.temp(it, s.fahrenheit) }, fx) }
            if (set.volt.x.size > 2) item { ChartPanel("Voltage", set.volt, Amp.c.volt, { "${Fmt.dec(it, 2)} V" }, fx) }
        }
        item {
            Panel {
                T("Details", Type.section)
                Spacer(Modifier.height(4.dp))
                KV("Duration", Fmt.duration(ses.durationMs))
                if (ses.isCharge) KV("Source", plugName(ses.plug))
                KV("Average power", "${Fmt.watts(ses.avgMw)} W")
                KV("Peak power", "${Fmt.watts(ses.peakMw)} W", sub = if (ses.isCharge) Thresholds.chargeClass(ses.peakMw) else null)
                if (ses.negotiatedMw > 0) KV("Charger offered", "${Fmt.watts(ses.negotiatedMw)} W")
                KV(if (ses.isCharge) "Energy in" else "Energy used", Fmt.wh(ses.energyMwh))
                KV("Charge moved", "${Fmt.int((ses.chargeUah / 1000).roundToInt())} mAh", sub = "counted from live current")
                KV("Screen on", Fmt.duration(ses.screenOnMs))
                KV("Temperature", "${Fmt.temp(ses.minTempT / 10f, s.fahrenheit)} to ${Fmt.temp(ses.peakTempT / 10f, s.fahrenheit)}", sub = "average ${Fmt.temp(ses.avgTempT / 10f, s.fahrenheit)}")
                KV("Voltage", "${Fmt.volts(ses.minMv)} to ${Fmt.volts(ses.maxMv)} V")
                if (ses.heat40Ms > 0) KV("Above ${Fmt.temp(40f, s.fahrenheit, 0)}", Fmt.duration(ses.heat40Ms))
                if (ses.holdMs > 60_000) KV("Held at 100%", Fmt.duration(ses.holdMs))
                if (ses.thermalMax > 0) KV("Worst throttling", Thresholds.thermalLabels[ses.thermalMax.coerceIn(0, 6)])
            }
        }
        if (ses.isCharge && ses.score >= 0) item {
            Panel {
                T("Why ${Grader.letter(ses.score)}", Type.section)
                Spacer(Modifier.height(4.dp))
                KV("Starting score", "100")
                Grader.decode(ses.reasons).forEach { KV(it.label, "−${it.points}", color = Amp.c.bad) }
                Hairline(Modifier.padding(vertical = 4.dp))
                KV("Final score", "${ses.score}", sub = "+${Game.sessionVp(ses.score, ses.gained)} VP earned")
            }
        }
        if (ses.isCharge) {
            val m = Marks.decode(ses.marks)
            val rows = (ses.startLevel / 10 + 1..ses.endLevel / 10).map { it * 10 }.filter { m[it] > 0 }
            if (rows.isNotEmpty()) item {
                Panel {
                    T("Splits", Type.section)
                    Spacer(Modifier.height(4.dp))
                    var prev = 0
                    rows.forEach { p ->
                        KV("$p%", Fmt.split(m[p]), sub = "+${Fmt.split(m[p] - prev)}")
                        prev = m[p]
                    }
                }
            }
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false }, containerColor = Amp.c.surface,
        title = { T("Delete this session?", Type.section) },
        text = { T("Its records and capacity estimate go with it. This cannot be undone.", Type.body, Amp.c.dim) },
        confirmButton = {
            TextButton({
                scope.launch {
                    c.dao.deleteSession(id)
                    c.dao.deleteSessionSamples(id)
                    c.dao.deleteSessionEstimates(id)
                    nav.back()
                }
            }) { T("Delete", Type.strong, Amp.c.bad) }
        },
        dismissButton = { TextButton({ ask = false }) { T("Keep", Type.strong, Amp.c.dim) } },
    )
}

@Composable
fun InfoPill(t: String) = Pill(t)
