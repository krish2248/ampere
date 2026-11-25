package dev.ampere.battery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ampere.battery.monitor.MonitorService
import dev.ampere.battery.ui.Root
import dev.ampere.battery.ui.theme.AmpereTheme

val LocalContainer = staticCompositionLocalOf<Container> { error("no container") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val c = (application as AmpereApp).container
        splash.setKeepOnScreenCondition { c.settingsReady.value == null }
        setContent {
            val s by c.settingsReady.collectAsStateWithLifecycle()
            val settings = s ?: return@setContent
            val dark = when (settings.theme) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            DisposableEffect(dark) {
                val t = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
                enableEdgeToEdge(style, style)
                onDispose { }
            }
            // Sample at 1 Hz only while someone is looking.
            LifecycleStartEffect(Unit) {
                c.engine.attach()
                onStopOrDispose { c.engine.detach() }
            }
            LaunchedEffect(settings.monitoring) {
                if (settings.monitoring && !MonitorService.running) MonitorService.start(this@MainActivity)
            }
            CompositionLocalProvider(LocalContainer provides c) {
                AmpereTheme(settings.theme) { Root(c, settings) }
            }
        }
    }
}
