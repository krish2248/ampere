package dev.ampere.battery.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ampere.battery.BuildConfig
import dev.ampere.battery.Container
import dev.ampere.battery.Derived
import dev.ampere.battery.core.Fmt
import dev.ampere.battery.data.Board
import dev.ampere.battery.data.Prefs
import dev.ampere.battery.data.Settings
import dev.ampere.battery.export.Share
import dev.ampere.battery.monitor.Notifs
import dev.ampere.battery.ui.Nav
import dev.ampere.battery.ui.SubPage
import dev.ampere.battery.ui.component.AmpButton
import dev.ampere.battery.ui.component.Dot
import dev.ampere.battery.ui.component.Hairline
import dev.ampere.battery.ui.component.NavRow
import dev.ampere.battery.ui.component.Panel
import dev.ampere.battery.ui.component.Ruler
import dev.ampere.battery.ui.component.Section
import dev.ampere.battery.ui.component.Segmented
import dev.ampere.battery.ui.component.T
import dev.ampere.battery.ui.component.TickDial
import dev.ampere.battery.ui.component.ToggleRow
import dev.ampere.battery.ui.theme.Amp
import dev.ampere.battery.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PRIVACY_URL = "https://github.com/krish2248/ampere/blob/main/PRIVACY.md"

/** Turns tracking on and asks for the notification permission on the way (Android 13+). */
@Composable
fun rememberTracking(c: Container): (Boolean) -> Unit {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { c.setMonitoring(true) }
    val ctx = LocalContext.current
    return { on ->
        if (on && Build.VERSION.SDK_INT >= 33 && !Notifs.allowed(ctx)) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else c.setMonitoring(on)
    }
}

@Composable
fun SettingsScreen(c: Container, s: Settings, nav: Nav) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val d by c.derived.collectAsStateWithLifecycle()
    val track = rememberTracking(c)
    var export by rememberSaveable { mutableStateOf(false) }
    var wipe by rememberSaveable { mutableStateOf(false) }
    var design by rememberSaveable { mutableStateOf(false) }
    var replaced by rememberSaveable { mutableStateOf(false) }
    fun <X> set(k: Preferences.Key<X>, v: X) {
        scope.launch { c.prefs.edit { it[k] = v } }
    }
    SubPage("Settings", nav) {
        item { Section("Tracking") }
        item {
            Panel {
                ToggleRow("Background tracking", "Records charges, records, health and goals with the app closed. Uses a quiet notification and samples slowly, never keeping the phone awake.", s.monitoring, track)
                Hairline()
                NavRow("Keep it alive", "Set Ampere to unrestricted so the phone does not stop it", Icons.Rounded.BatterySaver) { openBatterySettings(ctx) }
                if (s.monitoring) {
                    Hairline()
                    T("Notification shows", Type.small, Amp.c.dim, Modifier.padding(top = 12.dp, bottom = 8.dp))
                    Segmented(listOf("Level", "+ Watts", "+ Heat"), s.notifStyle) { set(Prefs.K.notifStyle, it) }
                }
            }
        }
        item { Section("Alerts") }
        item {
            Panel {
                Alert("Charge target", "Notify at ${s.alertFullAt}%", s.alertFull, { set(Prefs.K.alertFull, it) }, s.alertFullAt, 50..100, 1) { set(Prefs.K.alertFullAt, it) }
                Hairline()
                Alert("High temperature", "Notify at ${Fmt.temp(s.alertHotAt.toFloat(), s.fahrenheit, 0)}", s.alertHot, { set(Prefs.K.alertHot, it) }, s.alertHotAt, 36..50, 1) { set(Prefs.K.alertHotAt, it) }
                Hairline()
                Alert("Low battery", "Notify at ${s.alertLowAt}%", s.alertLow, { set(Prefs.K.alertLow, it) }, s.alertLowAt, 5..50, 1) { set(Prefs.K.alertLowAt, it) }
                Hairline()
                ToggleRow("Slow or unstable charging", "Weak charger, bad cable, loose port", s.alertSlow) { set(Prefs.K.alertSlow, it) }
                Hairline()
                ToggleRow("Throttling", "When the phone slows itself down", s.alertThrottle) { set(Prefs.K.alertThrottle, it) }
                Hairline()
                ToggleRow("Achievements", null, s.alertUnlocks) { set(Prefs.K.alertUnlocks, it) }
            }
        }
        item { Section("Look") }
        item {
            Panel {
                T("Theme", Type.small, Amp.c.dim, Modifier.padding(bottom = 8.dp))
                Segmented(listOf("System", "Light", "Dark"), s.theme) { set(Prefs.K.theme, it) }
                T("Temperature", Type.small, Amp.c.dim, Modifier.padding(top = 16.dp, bottom = 8.dp))
                Segmented(listOf("Celsius", "Fahrenheit"), if (s.fahrenheit) 1 else 0) { set(Prefs.K.fahrenheit, it == 1) }
            }
        }
        item { Section("You and your phone") }
        item {
            Panel {
                NavRow("Profile", "Nickname and country", Icons.Rounded.Badge, displayNick(s)) { nav.go("profile") }
                Hairline()
                NavRow("Original capacity", null, Icons.Rounded.Description, c.design.resolve(s.manualDesignMah).mah?.let { "${Fmt.int(it)} mAh" } ?: "Not set") { design = true }
                Hairline()
                NavRow("Battery was replaced", "Start health measurement again", Icons.Rounded.BatteryFull) { replaced = true }
                Hairline()
                NavRow("Recalibrate current sensor", "Use this if charging shows as draining", Icons.Rounded.Refresh) {
                    c.engine.recalibrate()
                }
                Hairline()
                NavRow("Raw diagnostics", null, Icons.Rounded.Terminal) { nav.go("diagnostics") }
            }
        }
        item { Section("Data") }
        item {
            Panel {
                NavRow("Export and share", "PDF report, CSV or card", Icons.Rounded.PictureAsPdf) { export = true }
                Hairline()
                NavRow("Privacy policy", null, Icons.Rounded.PrivacyTip) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }
                }
                Hairline()
                NavRow("Delete all data", "${Fmt.int(d.samples)} samples, ${d.stats.charges.size + d.stats.drains.size} sessions", Icons.Rounded.DeleteForever) { wipe = true }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 12.dp), Arrangement.spacedBy(6.dp)) {
                T("Ampere ${BuildConfig.VERSION_NAME}", Type.strong)
                T(
                    "Ampere measures at the battery, not at the wall. It cannot see the charger's own output, limit charging, or read per-app usage: Android does not allow it. " +
                        (if (Board.configured) "Nothing leaves your phone unless you join the world board." else "Nothing leaves your phone. The app has no internet permission."),
                    Type.small, Amp.c.dim,
                )
                T("Type set in Geist (SIL Open Font License). Device names from Google Play's public catalogue.", Type.small, Amp.c.faint)
            }
        }
    }
    if (export) ExportSheet(c, s, d) { export = false }
    if (design) DesignDialog(c, s) { design = false }
    if (replaced) AlertDialog(
        onDismissRequest = { replaced = false }, containerColor = Amp.c.surface,
        title = { T("Start health again?", Type.section) },
        text = { T("Clears the capacity measurements of the old battery. Sessions, records and ranks stay.", Type.body, Amp.c.dim) },
        confirmButton = {
            TextButton({
                c.resetHealth()
                replaced = false
            }) { T("Start again", Type.strong) }
        },
        dismissButton = { TextButton({ replaced = false }) { T("Cancel", Type.strong, Amp.c.dim) } },
    )
    if (wipe) AlertDialog(
        onDismissRequest = { wipe = false }, containerColor = Amp.c.surface,
        title = { T("Delete all data?", Type.section) },
        text = { T("Sessions, records, achievements and health history will be gone for good. Settings stay.", Type.body, Amp.c.dim) },
        confirmButton = {
            TextButton({
                c.wipe()
                wipe = false
            }) { T("Delete", Type.strong, Amp.c.bad) }
        },
        dismissButton = { TextButton({ wipe = false }) { T("Keep", Type.strong, Amp.c.dim) } },
    )
}

@Composable
private fun Alert(title: String, sub: String, on: Boolean, setOn: (Boolean) -> Unit, v: Int, range: IntRange, step: Int, set: (Int) -> Unit) {
    Column {
        ToggleRow(title, if (on) sub else null, on, setOn)
        if (on) Ruler(v, range, step, Amp.c.text, label = title, onChange = set)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(c: Container, s: Settings, d: Derived, close: () -> Unit) {
    val ctx = LocalContext.current
    val dev by c.device.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf<String?>(null) }
    fun go(what: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = what
        // App scope: closing the sheet must not cancel a half-written file.
        c.scope.launch {
            runCatching { block() }
            withContext(Dispatchers.Main) {
                busy = null
                close()
            }
        }
    }
    ModalBottomSheet(close, containerColor = Amp.c.surface, contentColor = Amp.c.text) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            T("Export and share", Type.section)
            Spacer(Modifier.height(6.dp))
            NavRow("PDF report", "Health, records, charts and phone details", Icons.Rounded.PictureAsPdf, if (busy == "pdf") "Preparing" else null) {
                go("pdf") {
                    Share.pdf(ctx, c, s, d, dev)
                    c.markExported()
                }
            }
            Hairline()
            NavRow("Rank card", "A picture of your rank and best times", Icons.Rounded.Image, if (busy == "card") "Preparing" else null) { go("card") { Share.card(ctx, c, s, d, dev) } }
            Hairline()
            NavRow("Sessions as CSV", "For a spreadsheet", Icons.Rounded.TableChart, if (busy == "csv") "Preparing" else null) { go("csv") { Share.csv(ctx, d) } }
            Hairline()
            NavRow("Raw samples as CSV", "Last 7 days", Icons.Rounded.TableChart, if (busy == "raw") "Preparing" else null) { go("raw") { Share.samples(ctx, c) } }
        }
    }
}

// ---- first run -------------------------------------------------------------------------------------

@Composable
fun Onboarding(c: Container, s: Settings) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val l by c.engine.live.collectAsStateWithLifecycle()
    val dev by c.device.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val track = rememberTracking(c)
    fun done() {
        scope.launch { c.prefs.edit { it[Prefs.K.onboarded] = true } }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), Alignment.Center) {
        AnimatedContent(
            step, Modifier.widthIn(max = 560.dp).fillMaxSize(),
            transitionSpec = {
                (slideInHorizontally(tween(420)) { it / 5 } + fadeIn(tween(420))) togetherWith (slideOutHorizontally(tween(300)) { -it / 8 } + fadeOut(tween(200)))
            },
            label = "onb",
        ) { p ->
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (p) {
                    0 -> {
                        // The dial takes whatever height is left, so the button stays on screen in landscape.
                        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), Alignment.Center) {
                            TickDial(l.level, 20f, true, Amp.c.charge, Modifier.widthIn(max = 300.dp), desc = "Battery ${l.level} percent") {
                                T(if (l.ready) "${l.level}%" else "", Type.figure)
                            }
                        }
                        T("This is a", Type.body, Amp.c.dim)
                        T(dev?.name ?: "phone", Type.title, align = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        T(
                            dev?.spec?.let { "${Fmt.int(it.mah)} mAh when new, charges at up to ${Fmt.dec(it.wiredW, if (it.wiredW % 1f == 0f) 0 else 1)} W. Let's see what is left of it." }
                                ?: "Let's see how its battery is really doing.",
                            Type.body, Amp.c.dim, align = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        AmpButton("Start", Modifier.fillMaxWidth()) { step = 1 }
                    }
                    1 -> {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), Arrangement.Center) {
                        T("Let Ampere watch quietly", Type.title, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        T("Real capacity, charge records and goals come from watching whole charges. Tracking runs behind a quiet notification, samples slowly so it costs little, and stays on this phone.", Type.body, Amp.c.dim)
                        Spacer(Modifier.height(28.dp))
                        listOf("Measure the true capacity", "Time every charge to the second", "Warn you about heat and bad cables", "Earn ranks, streaks and achievements").forEach {
                            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
                                Dot(Amp.c.charge)
                                T(it, Type.body)
                            }
                        }
                        }
                        Spacer(Modifier.height(12.dp))
                        AmpButton("Turn on tracking", Modifier.fillMaxWidth()) {
                            track(true)
                            step = 2
                        }
                        Spacer(Modifier.height(8.dp))
                        AmpButton("Not now", Modifier.fillMaxWidth(), primary = false) { step = 2 }
                    }
                    else -> {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            T("Your player card", Type.title, modifier = Modifier.padding(top = 24.dp))
                            Spacer(Modifier.height(8.dp))
                            T("Shown with your rank and on anything you share. Both are optional.", Type.body, Amp.c.dim)
                            Spacer(Modifier.height(20.dp))
                            NickField(c, s)
                            Spacer(Modifier.height(16.dp))
                            CountryPicker(c, s, 320)
                        }
                        Spacer(Modifier.height(12.dp))
                        AmpButton("Open Ampere", Modifier.fillMaxWidth()) { done() }
                    }
                }
            }
        }
    }
}
