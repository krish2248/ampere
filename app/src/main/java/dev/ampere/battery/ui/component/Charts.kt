package dev.ampere.battery.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ampere.battery.ui.theme.Amp
import dev.ampere.battery.ui.theme.Motion
import dev.ampere.battery.ui.theme.Type
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The instrument at the heart of the app: one tick per percent. While charging, a pulse of light runs
 * along the lit ticks and its speed follows the real wattage.
 */
@Composable
fun TickDial(level: Int, watts: Float, charging: Boolean, color: Color, modifier: Modifier = Modifier, desc: String = "", content: @Composable () -> Unit) {
    val lit by animateFloatAsState(level.toFloat(), Motion.settle(), label = "dial")
    val glow by animateFloatAsState(if (charging) 1f else 0f, tween(700), label = "glow")
    val speed by rememberUpdatedState(0.22f + min(watts, 60f) / 60f * 0.75f)
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(charging) {
        if (!charging) return@LaunchedEffect
        var last = 0L
        while (true) withInfiniteAnimationFrameMillis { t ->
            if (last != 0L) phase = (phase + (t - last) / 1000f * speed) % 1.35f
            last = t
        }
    }
    val dim = Amp.c.line
    val hot = if (Amp.c.dark) Color.White else lerp(color, Color.White, 0.55f)
    Box(
        modifier.aspectRatio(1f).semantics(mergeDescendants = true) { contentDescription = desc }.drawBehind {
            val c = center
            val outer = size.minDimension / 2 - 2.dp.toPx()
            val long = 22.dp.toPx()
            val short = 15.dp.toPx()
            val sw = 2.6.dp.toPx()
            for (i in 0 until 100) {
                val a = (-90f + 3.6f * i) * (PI / 180).toFloat()
                val on = (lit - i).coerceIn(0f, 1f)
                val major = i % 25 == 0
                val head = exp(-(i - (lit - 1)).let { it * it } / 3f) * on
                var col = lerp(dim, color, on)
                if (glow > 0f && on > 0f) {
                    val d = i / max(1f, lit) - phase
                    val wave = exp(-(d * d) / 0.006f) * glow
                    col = lerp(col, hot, wave * 0.85f)
                }
                val len = (if (major) long else short) + head * 6.dp.toPx()
                val ca = cos(a)
                val sa = sin(a)
                drawLine(col, Offset(c.x + (outer - len) * ca, c.y + (outer - len) * sa), Offset(c.x + outer * ca, c.y + outer * sa), sw, StrokeCap.Round)
            }
        },
        Alignment.Center,
    ) { content() }
}

/** Live trace that slides continuously between one-second samples, like an oscilloscope. */
@Composable
fun Scope(values: FloatArray, color: Color, modifier: Modifier = Modifier, height: Dp = 44.dp, window: Int = 60, baseline: Boolean = false, animate: Boolean = true) {
    var slide by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(values) {
        if (!animate || values.size < 3) return@LaunchedEffect
        // The trace moves about 5 px a second, so 20 steps look continuous and cost a sixth of 120 Hz.
        for (i in 0..20) {
            slide = i / 20f
            kotlinx.coroutines.delay(48)
        }
    }
    val lo0 = values.minOrNull() ?: 0f
    val hi0 = values.maxOrNull() ?: 1f
    val pad = max((hi0 - lo0) * 0.18f, max(abs(hi0) * 0.02f, 0.01f))
    val lo by animateFloatAsState(if (baseline) min(0f, lo0) else lo0 - pad, Motion.settle(), label = "lo")
    val hi by animateFloatAsState(hi0 + pad, Motion.settle(), label = "hi")
    val path = remember { Path() }
    val fill = remember { Path() }
    Spacer(
        modifier.fillMaxWidth().height(height).clearAndSetSemantics { }.drawBehind {
            if (values.size < 2) return@drawBehind
            val dx = size.width / (window - 2)
            val off = (1f - slide) * dx
            val n = values.size
            fun x(i: Int) = size.width - (n - 1 - i) * dx + off
            fun y(i: Int) = size.height * (1f - (values[i] - lo) / (hi - lo).coerceAtLeast(1e-4f))
            path.rewind()
            path.moveTo(x(0), y(0))
            for (i in 1 until n) {
                val mx = (x(i - 1) + x(i)) / 2
                path.cubicTo(mx, y(i - 1), mx, y(i), x(i), y(i))
            }
            fill.rewind()
            fill.addPath(path)
            fill.lineTo(x(n - 1), size.height)
            fill.lineTo(x(0), size.height)
            fill.close()
            clipRect {
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0f))))
                drawPath(path, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        },
    )
}

class Points(val x: LongArray, val y: FloatArray)

/**
 * Time series with a hold-to-inspect cursor. NaN values and gaps longer than [gapMs] break the line:
 * the chart never draws through time nobody measured.
 */
@Composable
fun LineChart(
    data: Points, color: Color, modifier: Modifier = Modifier, height: Dp = 160.dp,
    fmtY: (Float) -> String, fmtX: (Long) -> String, yMin: Float? = null, yMax: Float? = null,
    gapMs: Long = Long.MAX_VALUE, from: Long? = null, to: Long? = null, desc: String = "", xTicks: Boolean = true, dots: Boolean = false,
) {
    val reveal by rememberReveal(data)
    val tm = rememberTextMeasurer()
    val hap = LocalHapticFeedback.current
    var sel by remember(data) { mutableIntStateOf(-1) }
    val c = Amp.c
    if (data.x.isEmpty()) return
    val x0 = from ?: data.x.first()
    val x1 = max(to ?: data.x.last(), x0 + 1)
    val bounds = remember(data, yMin, yMax) {
        val ok = data.y.filter { !it.isNaN() }
        val a = ok.minOrNull() ?: 0f
        val b = ok.maxOrNull() ?: 1f
        val p = max((b - a) * 0.12f, max(abs(b) * 0.01f, 0.05f))
        (yMin ?: (a - p)) to (yMax ?: (b + p))
    }
    Spacer(
        modifier.fillMaxWidth().height(height).semantics { contentDescription = desc }
            .pointerInput(data, x0, x1) {
                fun pick(px: Float) {
                    val t = x0 + ((px / size.width).coerceIn(0f, 1f) * (x1 - x0)).toLong()
                    var i = data.x.binarySearch(t)
                    if (i < 0) i = (-i - 1).coerceIn(0, data.x.size - 1)
                    if (i > 0 && t - data.x[i - 1] < data.x[i] - t) i--
                    if (i != sel && !data.y[i].isNaN()) {
                        sel = i
                        hap.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    }
                }
                detectDragGesturesAfterLongPress(onDragStart = { pick(it.x) }, onDragEnd = { sel = -1 }, onDragCancel = { sel = -1 }) { ch, _ ->
                    ch.consume()
                    pick(ch.position.x)
                }
            }
            .drawWithCache {
                val bottom = if (xTicks) 20.dp.toPx() else 2.dp.toPx()
                val top = 8.dp.toPx()
                val h = size.height - bottom - top
                val (lo, hi) = bounds
                fun px(t: Long) = (t - x0).toFloat() / (x1 - x0) * size.width
                fun py(v: Float) = top + h * (1f - (v - lo) / (hi - lo))
                val line = Path()
                val area = Path()
                var open = false
                var startX = 0f
                var lastX = 0f
                fun closeArea() {
                    if (open) {
                        area.lineTo(lastX, top + h)
                        area.lineTo(startX, top + h)
                        area.close()
                    }
                }
                for (i in data.x.indices) {
                    val v = data.y[i]
                    val broken = v.isNaN() || (i > 0 && data.x[i] - data.x[i - 1] > gapMs)
                    if (broken) {
                        closeArea()
                        open = false
                        if (v.isNaN()) continue
                    }
                    val x = px(data.x[i])
                    val y = py(v)
                    if (!open) {
                        line.moveTo(x, y)
                        area.moveTo(x, y)
                        startX = x
                        open = true
                    } else {
                        line.lineTo(x, y)
                        area.lineTo(x, y)
                    }
                    lastX = x
                }
                closeArea()
                val brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.20f), color.copy(alpha = 0f)), top, top + h)
                val yHi = tm.measure(fmtY(hi), Type.monoSmall.copy(color = c.faint))
                val yLo = tm.measure(fmtY(lo), Type.monoSmall.copy(color = c.faint))
                val xs = if (xTicks) listOf(tm.measure(fmtX(x0), Type.monoSmall.copy(color = c.faint)), tm.measure(fmtX((x0 + x1) / 2), Type.monoSmall.copy(color = c.faint)), tm.measure(fmtX(x1), Type.monoSmall.copy(color = c.faint))) else emptyList()
                onDrawBehind {
                    for (k in 0..2) {
                        val y = top + h * k / 2
                        drawLine(c.line.copy(alpha = 0.55f), Offset(0f, y), Offset(size.width, y), 1f)
                    }
                    clipRect(right = size.width * reveal) {
                        drawPath(area, brush)
                        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (dots) for (i in data.x.indices) if (!data.y[i].isNaN()) drawCircle(color, 3.dp.toPx(), Offset(px(data.x[i]), py(data.y[i])))
                    }
                    if (sel < 0) {
                        // A soft plate keeps the scale readable where the line runs under it.
                        for ((t, y) in listOf(yHi to top + 2.dp.toPx(), yLo to top + h - yLo.size.height - 2.dp.toPx())) {
                            val pad = 4.dp.toPx()
                            drawRoundRect(c.surface.copy(alpha = 0.78f), Offset(size.width - t.size.width - pad, y), Size(t.size.width + pad, t.size.height.toFloat()), CornerRadius(pad))
                            drawText(t, topLeft = Offset(size.width - t.size.width, y))
                        }
                    }
                    if (xs.size == 3) {
                        val y = size.height - xs[0].size.height
                        drawText(xs[0], topLeft = Offset(0f, y))
                        drawText(xs[1], topLeft = Offset((size.width - xs[1].size.width) / 2, y))
                        drawText(xs[2], topLeft = Offset(size.width - xs[2].size.width, y))
                    }
                    val s = sel
                    if (s >= 0) {
                        val x = px(data.x[s])
                        val y = py(data.y[s])
                        drawLine(c.dim, Offset(x, top), Offset(x, top + h), 1.dp.toPx())
                        drawCircle(c.surface, 6.dp.toPx(), Offset(x, y))
                        drawCircle(color, 4.dp.toPx(), Offset(x, y))
                        callout(tm.measure(fmtY(data.y[s]) + "  " + fmtX(data.x[s]), Type.tiny.copy(color = c.bg)), x, c.text)
                    }
                }
            },
    )
}

private fun DrawScope.callout(t: androidx.compose.ui.text.TextLayoutResult, x: Float, bg: Color) {
    val px = 9.dp.toPx()
    val py = 5.dp.toPx()
    val w = t.size.width + px * 2
    val hh = t.size.height + py * 2
    val left = (x - w / 2).coerceIn(0f, max(0f, size.width - w))
    drawRoundRect(bg, Offset(left, 0f), Size(w, hh), CornerRadius(hh / 2))
    drawText(t, topLeft = Offset(left + px, py))
}

/** Bars with rounded data ends; the tapped bar shows its value. */
@Composable
fun Bars(values: List<Float>, labels: List<String>, color: Color, fmt: (Float) -> String, modifier: Modifier = Modifier, height: Dp = 130.dp, desc: String = "", every: Int = 1) {
    val reveal by rememberReveal(values.size)
    val tm = rememberTextMeasurer()
    var sel by remember(values) { mutableIntStateOf(-1) }
    val c = Amp.c
    val top = values.maxOrNull()?.takeIf { it > 0 } ?: 1f
    Spacer(
        modifier.fillMaxWidth().height(height).semantics { contentDescription = desc }
            .pointerInput(values) {
                detectTapGestures { o ->
                    val i = (o.x / size.width * values.size).toInt().coerceIn(0, values.size - 1)
                    sel = if (sel == i) -1 else i
                }
            }
            .drawBehind {
                val lh = 20.dp.toPx()
                val head = 26.dp.toPx()
                val h = size.height - lh - head
                val slot = size.width / values.size
                val w = min(slot * 0.62f, 22.dp.toPx())
                drawLine(c.line, Offset(0f, head + h), Offset(size.width, head + h), 1f)
                values.forEachIndexed { i, v ->
                    val grow = ((reveal * 1.6f) - i * 0.6f / values.size).coerceIn(0f, 1f)
                    val bh = v / top * h * grow
                    val x = i * slot + (slot - w) / 2
                    if (bh > 0.5f) drawRoundRect(if (sel == -1 || sel == i) color else color.copy(alpha = 0.35f), Offset(x, head + h - bh), Size(w, bh), CornerRadius(min(w / 2, 5.dp.toPx()).coerceAtMost(bh / 2)))
                    if (i % every == 0 && labels.getOrNull(i)?.isNotEmpty() == true) {
                        val t = tm.measure(labels[i], Type.monoSmall.copy(color = c.faint))
                        drawText(t, topLeft = Offset((i * slot + slot / 2 - t.size.width / 2).coerceIn(0f, size.width - t.size.width), size.height - t.size.height))
                    }
                }
                if (sel >= 0) callout(tm.measure("${labels[sel]}  ${fmt(values[sel])}".trim(), Type.tiny.copy(color = c.bg)), sel * slot + slot / 2, c.text)
            },
    )
}

/** Where charges start (rows) and end (columns), ten percent per cell. */
@Composable
fun ChargeMap(matrix: IntArray, color: Color, modifier: Modifier = Modifier) {
    val top = (matrix.maxOrNull() ?: 0).coerceAtLeast(1)
    val reveal by rememberReveal()
    val tm = rememberTextMeasurer()
    var sel by remember { mutableStateOf<Int?>(null) }
    val c = Amp.c
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), Arrangement.SpaceBetween) {
            T(sel?.let { "${it / 10 * 10} to ${(it % 10 + 1) * 10}%: ${matrix[it]} charge${if (matrix[it] == 1) "" else "s"}" } ?: "Tap a cell", Type.small, c.dim)
        }
        Spacer(
            Modifier.fillMaxWidth().aspectRatio(1.18f).semantics { contentDescription = "Heat map of charge start and end levels" }
                .pointerInput(matrix) {
                    detectTapGestures { o ->
                        val left = 30.dp.toPx()
                        val cw = (size.width - left) / 10
                        val ch = (size.height - 20.dp.toPx()) / 10
                        val col = ((o.x - left) / cw).toInt()
                        val row = (o.y / ch).toInt()
                        sel = if (o.x < left || col !in 0..9 || row !in 0..9) null else row * 10 + col
                    }
                }
                .drawBehind {
                    val left = 30.dp.toPx()
                    val cw = (size.width - left) / 10
                    val ch = (size.height - 20.dp.toPx()) / 10
                    val g = 1.5.dp.toPx()
                    for (r in 0..9) for (k in 0..9) {
                        if (k < r) continue
                        val v = matrix[r * 10 + k]
                        val a = if (v == 0) 0f else 0.25f + 0.75f * v / top
                        val grow = (reveal * 2f - (r + k) / 18f).coerceIn(0f, 1f)
                        drawRoundRect(
                            if (v == 0) c.raised else lerp(c.raised, color, a * grow),
                            Offset(left + k * cw + g, r * ch + g), Size(cw - 2 * g, ch - 2 * g), CornerRadius(5.dp.toPx()),
                        )
                        if (sel == r * 10 + k) drawRoundRect(c.text, Offset(left + k * cw + g, r * ch + g), Size(cw - 2 * g, ch - 2 * g), CornerRadius(5.dp.toPx()), Stroke(2.dp.toPx()))
                    }
                    for (r in 0..9 step 2) {
                        val t = tm.measure("${r * 10}", Type.monoSmall.copy(color = c.faint))
                        drawText(t, topLeft = Offset(0f, r * ch + (ch - t.size.height) / 2))
                    }
                    for (k in 1..9 step 2) {
                        val t = tm.measure("${(k + 1) * 10}", Type.monoSmall.copy(color = c.faint))
                        drawText(t, topLeft = Offset(left + k * cw + (cw - t.size.width) / 2, size.height - t.size.height))
                    }
                },
        )
    }
}

/** Original versus current capacity, drawn as one cell with the lost part hollowed out. */
@Composable
fun CellViz(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 72.dp) {
    val reveal by rememberReveal()
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.settle(), label = "cell")
    val c = Amp.c
    Spacer(
        modifier.fillMaxWidth().height(height).clearAndSetSemantics { }.drawWithCache {
            val nub = 7.dp.toPx()
            val body = size.width - nub
            val r = CornerRadius(18.dp.toPx())
            val inset = 5.dp.toPx()
            val hatch = 7.dp.toPx()
            val inner = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(inset, inset, body - inset, size.height - inset), CornerRadius(13.dp.toPx())))
            }
            onDrawBehind {
                drawRoundRect(c.line, Offset.Zero, Size(body, size.height), r, Stroke(1.5.dp.toPx()))
                drawRoundRect(c.line, Offset(body + 2.dp.toPx(), size.height * 0.34f), Size(nub - 2.dp.toPx(), size.height * 0.32f), CornerRadius(3.dp.toPx()))
                val w = body - inset * 2
                val edge = inset + w * f * reveal
                drawContext.canvas.save()
                drawContext.canvas.clipPath(inner)
                var x = edge - size.height
                while (x < body) {
                    drawLine(c.line, Offset(x, size.height), Offset(x + size.height, 0f), 1.2.dp.toPx())
                    x += hatch
                }
                drawRect(color, Offset(inset, inset), Size(edge - inset, size.height - inset * 2))
                drawRect(c.surface, Offset(edge, inset), Size(2.5.dp.toPx(), size.height - inset * 2))
                drawContext.canvas.restore()
            }
        },
    )
}

private val metals = listOf(
    Color(0xFFD08A5B) to Color(0xFF8A4F2B), Color(0xFFD9DEE6) to Color(0xFF8A939F), Color(0xFFFFD36B) to Color(0xFFB07A12),
    Color(0xFF7FA8FF) to Color(0xFF2C52C4), Color(0xFFD79BFF) to Color(0xFF7A35C9), Color(0xFF8CF5C8) to Color(0xFF12875A),
)

fun metal(family: Int): Pair<Color, Color> = metals[family.coerceIn(0, metals.lastIndex)]

/** Rank insignia: a shield in the family's metal, chevrons for the step, a bolt on top. Entirely vector. */
@Composable
fun RankBadge(family: Int, step: Int, modifier: Modifier = Modifier, locked: Boolean = false, shine: Boolean = false) {
    val (hi0, lo0) = metal(family)
    val c = Amp.c
    val hi = if (locked) c.line else hi0
    val lo = if (locked) c.raised else lo0
    val sweep = remember { Animatable(-0.4f) }
    LaunchedEffect(shine) {
        if (!shine) return@LaunchedEffect
        while (true) {
            sweep.snapTo(-0.4f)
            sweep.animateTo(1.4f, tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            kotlinx.coroutines.delay(2600)
        }
    }
    Spacer(
        modifier.aspectRatio(0.9f).clearAndSetSemantics { }.drawWithCache {
            val w = size.width
            val h = size.height
            val shield = Path().apply {
                moveTo(w * 0.5f, 0f)
                lineTo(w * 0.96f, h * 0.15f)
                lineTo(w * 0.96f, h * 0.55f)
                cubicTo(w * 0.96f, h * 0.78f, w * 0.72f, h * 0.92f, w * 0.5f, h)
                cubicTo(w * 0.28f, h * 0.92f, w * 0.04f, h * 0.78f, w * 0.04f, h * 0.55f)
                lineTo(w * 0.04f, h * 0.15f)
                close()
            }
            val bolt = Path().apply {
                moveTo(w * 0.55f, h * 0.15f)
                lineTo(w * 0.36f, h * 0.36f)
                lineTo(w * 0.49f, h * 0.36f)
                lineTo(w * 0.45f, h * 0.52f)
                lineTo(w * 0.65f, h * 0.30f)
                lineTo(w * 0.51f, h * 0.30f)
                close()
            }
            val bg = Brush.verticalGradient(listOf(hi, lo))
            val chev = Path()
            val ink = if (locked) c.faint else Color(0xFF101214)
            onDrawBehind {
                drawPath(shield, bg)
                drawPath(shield, Color.White.copy(alpha = if (locked) 0.05f else 0.28f), style = Stroke(w * 0.03f, join = StrokeJoin.Round))
                drawPath(bolt, ink.copy(alpha = 0.85f))
                val sw = h * 0.042f
                for (i in 0 until step) {
                    val y = h * 0.60f + i * h * 0.077f
                    val half = w * (0.27f - i * 0.035f)
                    chev.rewind()
                    chev.moveTo(w / 2 - half, y)
                    chev.lineTo(w / 2, y + h * 0.055f)
                    chev.lineTo(w / 2 + half, y)
                    drawPath(chev, ink.copy(alpha = 0.8f), style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                if (shine) {
                    drawContext.canvas.save()
                    drawContext.canvas.clipPath(shield)
                    rotate(20f) {
                        drawRect(Color.White.copy(alpha = 0.30f), Offset(w * sweep.value - w * 0.1f, -h * 0.3f), Size(w * 0.16f, h * 1.6f))
                    }
                    drawContext.canvas.restore()
                }
            }
        },
    )
}

/** Radial progress used for achievements and goals. */
@Composable
fun MiniRing(p: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 3.dp, content: @Composable () -> Unit = {}) {
    val a by animateFloatAsState(p.coerceIn(0f, 1f), Motion.settle(), label = "ring")
    val track = Amp.c.raised
    Box(
        modifier.drawBehind {
            val s = stroke.toPx()
            val inset = s / 2
            val sz = Size(size.width - s, size.height - s)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(s))
            if (a > 0.003f) drawArc(color, -90f, 360f * a, false, Offset(inset, inset), sz, style = Stroke(s, cap = StrokeCap.Round))
        },
        Alignment.Center,
    ) { content() }
}

/** Stacked share bar with a legend in text, so colour is never the only cue. */
@Composable
fun ShareBar(parts: List<Triple<String, Float, Color>>, modifier: Modifier = Modifier) {
    val tot = parts.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(0.001f)
    val reveal by rememberReveal()
    Column(modifier, Arrangement.spacedBy(10.dp)) {
        Spacer(
            Modifier.fillMaxWidth().height(12.dp).drawBehind {
                var x = 0f
                val gap = 3.dp.toPx()
                val shown = parts.filter { it.second > 0 }
                val usable = size.width - gap * (shown.size - 1).coerceAtLeast(0)
                for ((_, v, col) in shown) {
                    val w = usable * v / tot * reveal
                    drawRoundRect(col, Offset(x, 0f), Size(w, size.height), CornerRadius(min(4.dp.toPx(), w / 2)))
                    x += w + gap
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            parts.filter { it.second > 0 }.forEach { (n, v, col) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(col)
                    T("$n ${(v / tot * 100).toInt()}%", Type.small, Amp.c.dim)
                }
            }
        }
    }
}
