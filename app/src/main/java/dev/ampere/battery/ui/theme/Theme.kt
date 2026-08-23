package dev.ampere.battery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalAmpereColors = staticCompositionLocalOf { darkColors() }

@Composable
fun ampereColors(): AmpereColors = LocalAmpereColors.current

private val DarkScheme = darkColorScheme(
    background = DBg,
    surface = DCard,
    surfaceVariant = DCardAlt,
    outline = DLine,
    onBackground = DText,
    onSurface = DText,
    primary = DInfo,
    secondary = DGood,
    error = DBad,
)

private val LightScheme = lightColorScheme(
    background = LBg,
    surface = LCard,
    surfaceVariant = LCardAlt,
    outline = LLine,
    onBackground = LText,
    onSurface = LText,
    primary = LInfo,
    secondary = LGood,
    error = LBad,
)

@Composable
fun AmpereTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = if (dark) darkColors() else lightColors()
    CompositionLocalProvider(LocalAmpereColors provides palette) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AmpereTypography,
            content = content,
        )
    }
}
