package dev.ampere.battery.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ampere.battery.ui.theme.Amp
import dev.ampere.battery.ui.theme.Motion
import dev.ampere.battery.ui.theme.Type
import kotlin.math.roundToInt

val CardShape = RoundedCornerShape(24.dp)

@Composable
fun Panel(modifier: Modifier = Modifier, pad: Dp = 20.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, Motion.snap(), label = "press")
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CardShape)
            .background(Amp.c.surface)
            .then(if (onClick != null) Modifier.clickable(src, androidx.compose.material3.ripple(color = Amp.c.text), onClick = onClick) else Modifier)
            .padding(pad),
        content = content,
    )
}

@Composable
fun T(
    text: String, style: TextStyle = Type.body, color: Color = Amp.c.text, modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null,
) = Text(text, modifier, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align)

@Composable
fun Section(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        T(text, Type.section, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke(this)
    }
}

/** Figure that rolls to its new value. Recomposes only when the printed text changes. */
@Composable
fun Rolling(
    value: Float?, format: (Float) -> String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Amp.c.text, none: String = "—",
) {
    if (value == null) {
        T(none, style, color, modifier, 1)
        return
    }
    val a = remember { Animatable(value) }
    LaunchedEffect(value) { a.animateTo(value, Motion.settle()) }
    val text by remember { derivedStateOf { format(a.value) } }
    T(text, style, color, modifier, 1)
}

/** Counts up from zero the first time it is seen. */
@Composable
fun rememberReveal(key: Any? = Unit): State<Float> {
    var on by rememberSaveable(key) { mutableStateOf(false) }
    LaunchedEffect(key) { on = true }
    return animateFloatAsState(if (on) 1f else 0f, Motion.settle(), label = "reveal")
}

/** Value with its unit set smaller on the same baseline. */
@Composable
fun Figure(value: Float?, unit: String, format: (Float) -> String, style: TextStyle = Type.figure, color: Color = Amp.c.text, modifier: Modifier = Modifier) {
    Row(modifier) {
        Rolling(value, format, style, Modifier.alignByBaseline(), color)
        if (value != null) T(" $unit", Type.small, Amp.c.dim, Modifier.alignByBaseline(), 1)
    }
}

@Composable
fun KV(k: String, v: String, modifier: Modifier = Modifier, color: Color = Amp.c.text, sub: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            T(k, Type.body, Amp.c.dim)
            if (sub != null) T(sub, Type.small, Amp.c.faint)
        }
        T(v, Type.strong, color, Modifier.widthIn(max = 210.dp), align = TextAlign.End)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(1.dp).background(Amp.c.line.copy(alpha = 0.6f)))
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Spacer(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun Pill(text: String, color: Color = Amp.c.dim, modifier: Modifier = Modifier, filled: Boolean = true) {
    T(text, Type.tiny, color, modifier.clip(CircleShape).background(if (filled) color.copy(alpha = 0.13f) else Color.Transparent).padding(horizontal = 10.dp, vertical = 5.dp), 1)
}

@Composable
fun Segmented(options: List<String>, index: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val hap = LocalHapticFeedback.current
    val pos by animateFloatAsState(index.toFloat(), Motion.move(), label = "seg")
    val thumb = if (Amp.c.dark) Amp.c.line else Amp.c.surface
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Amp.c.raised).padding(4.dp).drawBehind {
            val w = size.width / options.size
            if (index >= 0) drawRoundRect(thumb, Offset(w * pos.coerceAtLeast(0f), 0f), Size(w, size.height), CornerRadius(12.dp.toPx()))
        },
    ) {
        options.forEachIndexed { i, o ->
            val col by animateColorAsState(if (i == index) Amp.c.text else Amp.c.dim, label = "segc")
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(remember { MutableInteractionSource() }, null) {
                        if (i != index) hap.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onPick(i)
                    }
                    .semantics {
                        role = Role.Tab
                        selected = i == index
                    },
                Alignment.Center,
            ) { T(o, Type.small.copy(fontWeight = Type.tiny.fontWeight), col, maxLines = 1) }
        }
    }
}

@Composable
fun AmpButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.snap(), label = "b")
    val fg = if (primary) Amp.c.bg else Amp.c.text
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
            .clip(CircleShape)
            .background(if (primary) Amp.c.text else Amp.c.raised)
            .clickable(src, androidx.compose.material3.ripple(color = fg), enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        Arrangement.Center, Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(19.dp), tint = fg)
            Spacer(Modifier.width(8.dp))
        }
        T(text, Type.strong, fg, maxLines = 1)
    }
}

@Composable
fun IconBtn(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = Amp.c.text, onClick: () -> Unit) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        Alignment.Center,
    ) { Icon(icon, null, Modifier.size(22.dp), tint = tint) }
}

@Composable
fun Toggle(on: Boolean, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(if (on) 1f else 0f, Motion.move(), label = "tg")
    val track by animateColorAsState(if (on) Amp.c.charge else Amp.c.line, label = "tt")
    val knob = if (Amp.c.dark && on) Amp.c.bg else Color.White
    Spacer(
        modifier.size(46.dp, 28.dp).drawBehind {
            drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
            val r = size.height / 2 - 4.dp.toPx()
            drawCircle(knob, r, Offset(size.height / 2 + (size.width - size.height) * p, size.height / 2))
        },
    )
}

@Composable
fun ToggleRow(title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    val hap = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) {
            hap.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onChange(!on)
        }.padding(vertical = 12.dp).semantics(mergeDescendants = true) { selected = on },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            T(title)
            if (sub != null) T(sub, Type.small, Amp.c.dim)
        }
        Toggle(on)
    }
}

@Composable
fun NavRow(title: String, sub: String? = null, icon: ImageVector? = null, value: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(21.dp), tint = Amp.c.dim)
        Column(Modifier.weight(1f)) {
            T(title)
            if (sub != null) T(sub, Type.small, Amp.c.dim)
        }
        if (value != null) T(value, Type.body, Amp.c.dim, maxLines = 1)
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = Amp.c.faint)
    }
}

/** A slider that reads as a ruler: drag anywhere on it, haptic tick per step. */
@Composable
fun Ruler(value: Int, range: IntRange, step: Int, color: Color, modifier: Modifier = Modifier, label: String = "", onChange: (Int) -> Unit) {
    val hap = LocalHapticFeedback.current
    var w by remember { mutableStateOf(1) }
    val line = Amp.c.line
    val p by animateFloatAsState((value - range.first).toFloat() / (range.last - range.first), Motion.snap(), label = "ruler")
    fun at(x: Float): Int {
        val raw = range.first + x.coerceIn(0f, w.toFloat()) / w * (range.last - range.first)
        return ((raw / step).roundToInt() * step).coerceIn(range.first, range.last)
    }
    var last by remember { mutableStateOf(value) }
    fun send(v: Int) {
        if (v == last) return
        last = v
        hap.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        onChange(v)
    }
    Spacer(
        modifier.fillMaxWidth().height(48.dp).onSizeChanged { w = it.width.coerceAtLeast(1) }
            .semantics { contentDescription = "$label $value" }
            .pointerInput(range) { detectTapGestures { send(at(it.x)) } }
            .pointerInput(range) {
                detectHorizontalDragGestures { ch, _ ->
                    ch.consume()
                    send(at(ch.position.x))
                }
            }
            .drawBehind {
                val n = 50
                val cy = size.height / 2
                for (i in 0..n) {
                    val f = i / n.toFloat()
                    val h = (if (i % 10 == 0) 20.dp else 12.dp).toPx()
                    val x = 1.dp.toPx() + f * (size.width - 2.dp.toPx())
                    drawLine(if (f <= p) color else line, Offset(x, cy - h / 2), Offset(x, cy + h / 2), 2.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                }
                drawCircle(color, 9.dp.toPx(), Offset(p * size.width, cy))
            },
    )
}

@Composable
fun Expandable(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val rot by animateFloatAsState(if (open) 180f else 0f, Motion.move(), label = "rot")
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(title, Type.body, Amp.c.dim, Modifier.weight(1f))
            Icon(Icons.Rounded.KeyboardArrowDown, if (open) "Collapse" else "Expand", Modifier.size(22.dp).graphicsLayer { rotationZ = rot }, tint = Amp.c.dim)
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(Motion.move()), exit = fadeOut() + shrinkVertically(Motion.move())) {
            Column(content = content)
        }
    }
}

@Composable
fun Empty(title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        T(title, Type.section, align = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        T(text, Type.small, Amp.c.dim, align = TextAlign.Center)
    }
}

/** Thin bar; [p] is read at draw time so it animates without recomposition. */
@Composable
fun Bar(p: () -> Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp, track: Color = Amp.c.raised) {
    Spacer(
        modifier.fillMaxWidth().height(height).drawBehind {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            val f = p().coerceIn(0f, 1f)
            if (f > 0.001f) drawRoundRect(color, size = Size((size.width * f).coerceAtLeast(size.height), size.height), cornerRadius = r)
        },
    )
}

/** Equal-width grid that stays a plain layout, so it can sit inside lazy lists. */
@Composable
fun Grid(cols: Int, gap: Dp = 12.dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { ms, cons ->
        val g = gap.roundToPx()
        val w = (cons.maxWidth - g * (cols - 1)) / cols
        val hs = ms.chunked(cols).map { row -> row.maxOf { it.minIntrinsicHeight(w) } }
        val placed = ms.mapIndexed { i, m -> m.measure(androidx.compose.ui.unit.Constraints(w, w, hs[i / cols], hs[i / cols])) }
        val total = hs.sum() + g * (hs.size - 1).coerceAtLeast(0)
        layout(cons.maxWidth, total) {
            var y = 0
            placed.chunked(cols).forEachIndexed { r, row ->
                row.forEachIndexed { c, p -> p.place(c * (w + g), y) }
                y += hs[r] + g
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, sub: String? = null, color: Color = Amp.c.text, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Panel(modifier, 16.dp, onClick) {
        T(label, Type.small, Amp.c.dim, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        T(value, Type.figure.copy(fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)), color, maxLines = 1)
        if (sub != null) T(sub, Type.small, Amp.c.faint, maxLines = 2)
    }
}

@Composable
fun Faded(visible: Boolean, content: @Composable () -> Unit) {
    val a by animateFloatAsState(if (visible) 1f else 0.35f, label = "fade")
    Box(Modifier.alpha(a)) { content() }
}
