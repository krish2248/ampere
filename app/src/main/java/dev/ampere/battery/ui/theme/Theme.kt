package dev.ampere.battery.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import dev.ampere.battery.R

/**
 * Neutral surfaces; colour is reserved for data. Each physical quantity owns one hue everywhere:
 * tile, chart, badge and PDF. Green is charge, amber is power, cyan is current, violet is voltage, coral is heat.
 */
@Immutable
data class Colors(
    val dark: Boolean,
    val bg: Color, val surface: Color, val raised: Color, val line: Color,
    val text: Color, val dim: Color, val faint: Color,
    val charge: Color, val power: Color, val current: Color, val volt: Color, val heat: Color,
    val good: Color, val warn: Color, val bad: Color, val cold: Color,
)

val DarkColors = Colors(
    true, Color(0xFF0B0C0E), Color(0xFF131518), Color(0xFF1B1E22), Color(0xFF262A30),
    Color(0xFFF2F3F5), Color(0xFF979DA8), Color(0xFF777E89),
    Color(0xFF5FE0A3), Color(0xFFFFB84D), Color(0xFF56C7F2), Color(0xFFA99BFF), Color(0xFFFF7D6B),
    Color(0xFF5FE0A3), Color(0xFFFFB84D), Color(0xFFFF6459), Color(0xFF7DB6FF),
)

val LightColors = Colors(
    false, Color(0xFFF5F6F8), Color(0xFFFFFFFF), Color(0xFFEDEFF2), Color(0xFFE1E4E9),
    Color(0xFF14161A), Color(0xFF5C636E), Color(0xFF757C87),
    Color(0xFF12875A), Color(0xFFA86400), Color(0xFF0B79A8), Color(0xFF6A55D9), Color(0xFFC9432F),
    Color(0xFF12875A), Color(0xFFA86400), Color(0xFFC62F2A), Color(0xFF2F6FC4),
)

val LocalColors = staticCompositionLocalOf { DarkColors }

object Amp {
    val c: Colors @Composable get() = LocalColors.current
}

@OptIn(ExperimentalTextApi::class)
private fun face(res: Int, w: Int) = Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Geist = FontFamily(face(R.font.geist, 300), face(R.font.geist, 400), face(R.font.geist, 500), face(R.font.geist, 600))
val GeistMono = FontFamily(face(R.font.geist_mono, 400), face(R.font.geist_mono, 500))

private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

object Type {
    /** Tabular figures so nothing shifts while numbers roll. */
    private const val TNUM = "tnum"
    val hero = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Light, fontSize = 76.sp, lineHeight = 80.sp, letterSpacing = (-3.5).sp, fontFeatureSettings = TNUM)
    val big = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Light, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-1.8).sp, fontFeatureSettings = TNUM)
    val figure = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.7).sp, fontFeatureSettings = TNUM)
    val title = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.8).sp)
    val section = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp)
    val body = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, lineHeightStyle = trim, fontFeatureSettings = TNUM)
    val strong = body.copy(fontWeight = FontWeight.Medium)
    val small = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TNUM)
    val tiny = TextStyle(fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, lineHeight = 15.sp, fontFeatureSettings = TNUM)
    val mono = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = (-0.2).sp)
    val monoSmall = TextStyle(fontFamily = GeistMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp)
}

/** Motion: one spring for things that move, one for things that settle. */
object Motion {
    fun <T> settle() = spring<T>(dampingRatio = 0.9f, stiffness = 90f)
    fun <T> move() = spring<T>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
    fun <T> snap() = spring<T>(dampingRatio = 0.8f, stiffness = 700f)
}

@Composable
fun AmpereTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val c = if (dark) DarkColors else LightColors
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        background = c.bg, surface = c.surface, surfaceVariant = c.raised, surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.raised, surfaceContainerLow = c.surface, outline = c.line, outlineVariant = c.line,
        onBackground = c.text, onSurface = c.text, onSurfaceVariant = c.dim, primary = c.text, onPrimary = c.bg,
        secondaryContainer = c.raised, onSecondaryContainer = c.text, error = c.bad,
    )
    CompositionLocalProvider(LocalColors provides c) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(bodyLarge = Type.body, bodyMedium = Type.body, bodySmall = Type.small, titleLarge = Type.title,
                titleMedium = Type.section, labelLarge = Type.strong, labelMedium = Type.small, labelSmall = Type.tiny),
            content = content,
        )
    }
}
