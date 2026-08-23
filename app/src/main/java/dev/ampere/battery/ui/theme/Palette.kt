package dev.ampere.battery.ui.theme

import androidx.compose.ui.graphics.Color

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AmpereColors(
    val bg: Color,
    val card: Color,
    val cardAlt: Color,
    val line: Color,
    val text: Color,
    val textDim: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val info: Color,
    val chart: Color,
)

fun darkColors() = AmpereColors(
    bg = DBg, card = DCard, cardAlt = DCardAlt, line = DLine, text = DText, textDim = DTextDim,
    good = DGood, warn = DWarn, bad = DBad, info = DInfo, chart = DChart,
)

fun lightColors() = AmpereColors(
    bg = LBg, card = LCard, cardAlt = LCardAlt, line = LLine, text = LText, textDim = LTextDim,
    good = LGood, warn = LWarn, bad = LBad, info = LInfo, chart = LChart,
)
