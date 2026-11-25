package dev.ampere.battery.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.provider.Settings as SysSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ampere.battery.BuildConfig
import dev.ampere.battery.Container
import dev.ampere.battery.core.Countries
import dev.ampere.battery.core.Fmt
import dev.ampere.battery.data.Settings
import dev.ampere.battery.domain.Thresholds
import dev.ampere.battery.hw.PhoneSpecs
import dev.ampere.battery.hw.SpecGroup
import dev.ampere.battery.hw.Sysfs
import dev.ampere.battery.monitor.Live
import dev.ampere.battery.ui.Nav
import dev.ampere.battery.ui.SubPage
import dev.ampere.battery.ui.TabPage
import dev.ampere.battery.ui.component.Grid
import dev.ampere.battery.ui.component.IconBtn
import dev.ampere.battery.ui.component.KV
import dev.ampere.battery.ui.component.NavRow
import dev.ampere.battery.ui.component.Panel
import dev.ampere.battery.ui.component.Pill
import dev.ampere.battery.ui.component.Section
import dev.ampere.battery.ui.component.StatTile
import dev.ampere.battery.ui.component.T
import dev.ampere.battery.ui.component.rememberReveal
import dev.ampere.battery.ui.theme.Amp
import dev.ampere.battery.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PhoneScreen(c: Container, s: Settings, nav: Nav) {
    val dev by c.device.collectAsStateWithLifecycle()
    val l by c.engine.live.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val groups by produceState<List<SpecGroup>>(emptyList(), dev) {
        dev?.let { value = withContext(Dispatchers.Default) { PhoneSpecs.read(ctx, it) } }
    }
    TabPage("Phone", nav) {
        val info = dev
        item("id") {
            Panel {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhoneGlyph(l.level)
                    Column(Modifier.weight(1f)) {
                        T(info?.name ?: Build.MODEL, Type.title.copy(fontSize = 25.sp))
                        T(listOfNotNull(info?.spec?.country?.let { Countries.flag(it) }, info?.brand, info?.model?.takeIf { it != info.name }).joinToString("  "), Type.small, Amp.c.dim)
                        Spacer(Modifier.height(10.dp))
                        if (info != null) Pill(if (info.spec != null) "Recognised, spec known" else if (info.recognised) "Recognised" else "New model", if (info.recognised) Amp.c.good else Amp.c.warn)
                    }
                }
            }
        }
        val spec = info?.spec
        item("chg") {
            Grid(2) {
                StatTile("Rated battery", (spec?.mah ?: l.designMah)?.let { "${Fmt.int(it)} mAh" } ?: Fmt.NONE)
                StatTile("Wired charging", spec?.let { "${num(it.wiredW)} W" } ?: Fmt.NONE, if (spec == null) "no published figure" else null)
                StatTile("Wireless charging", spec?.let { if (it.wirelessW > 0) "${num(it.wirelessW)} W" else "No" } ?: Fmt.NONE)
                StatTile("Released", spec?.year?.toString() ?: Fmt.NONE)
            }
        }
        groups.forEach { g ->
            item(g.title) {
                Panel {
                    T(g.title, Type.section)
                    Spacer(Modifier.height(4.dp))
                    g.rows.forEach { (k, v) -> KV(k, v) }
                }
            }
        }
        item("diag") {
            Panel { NavRow("Raw diagnostics", "Every value the phone exposes", Icons.Rounded.Terminal) { nav.go("diagnostics") } }
        }
    }
}

private fun num(v: Float) = Fmt.dec(v, if (v % 1f == 0f) 0 else 1)

@Composable
private fun PhoneGlyph(level: Int) {
    val r by rememberReveal()
    val line = Amp.c.faint
    val fill = Amp.c.charge
    Spacer(Modifier.size(58.dp, 112.dp).drawBehind {
        val w = 2.dp.toPx()
        drawRoundRect(line, Offset(w, w), Size(size.width - 2 * w, size.height - 2 * w), CornerRadius(13.dp.toPx()), Stroke(w))
        drawCircle(line, 2.6.dp.toPx(), Offset(size.width / 2, 11.dp.toPx()))
        val inset = 7.dp.toPx()
        val h = (size.height - inset * 2 - 12.dp.toPx()) * level / 100f * r
        drawRoundRect(fill, Offset(inset, size.height - inset - h), Size(size.width - inset * 2, h), CornerRadius(8.dp.toPx().coerceAtMost(h / 2)))
    })
}

fun diagnostics(c: Container, l: Live): List<Pair<String, List<Pair<String, String>>>> {
    val r = l.raw
    val (scale, sign) = c.engine.calibration
    fun v(x: Any?) = x?.toString() ?: "unavailable"
    return listOf(
        "Battery service" to listOf(
            "level" to v(r?.level), "status" to v(r?.status), "plugged" to v(r?.plug), "health" to v(r?.health),
            "voltage raw" to v(r?.voltageRaw), "voltage" to (l.mv?.let { "$it mV" } ?: "unavailable"),
            "temperature" to (r?.tempT?.let { "$it (0.1 °C)" } ?: "unavailable"), "technology" to v(r?.tech),
            "max_charging_current" to v(r?.maxUa), "max_charging_voltage" to v(r?.maxUv), "cycle count" to v(r?.cycleCount),
        ),
        "Fuel gauge" to listOf(
            "CURRENT_NOW raw" to v(r?.currentNow), "current" to (l.ua?.let { "$it µA" } ?: "learning unit"),
            "unit" to when (scale) { 1 -> "µA"; 1000 -> "mA"; else -> "unknown" }, "sign" to when (sign) { 1 -> "+ is charging"; -1 -> "inverted"; else -> "unknown" },
            "CURRENT_AVERAGE raw" to v(r?.currentAvg), "CHARGE_COUNTER" to (r?.ccUah?.let { "$it µAh" } ?: "unavailable"),
            "ENERGY_COUNTER" to v(r?.energyNwh), "STATE_OF_HEALTH" to v(r?.soh), "computeChargeTimeRemaining" to (r?.sysEtaMs?.let { Fmt.duration(it) } ?: "unavailable"),
        ),
        "Thermal" to listOf("status" to "${l.thermal} ${Thresholds.thermalLabels.getOrElse(l.thermal) { "" }}", "headroom" to (l.headroom?.let { Fmt.dec(it, 3) } ?: "unavailable")),
        "Original capacity sources" to listOf(
            "device database" to v(c.devices.info.spec?.mah), "PowerProfile" to v(c.design.profile()), "charge_full_design" to v(c.design.sysfs()), "config_batteryCapacity" to v(c.design.resource()),
        ),
        "sysfs" to listOf("charge_full", "charge_full_design", "cycle_count", "current_now", "voltage_now").map { it to Sysfs.raw("/sys/class/power_supply/battery/$it") },
        "Broadcast extras" to (r?.extras?.toList() ?: emptyList()),
    )
}

@Composable
fun DiagnosticsScreen(c: Container, nav: Nav) {
    val l by c.engine.live.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val slow = remember(l.ts / 2000) { diagnostics(c, l) }
    SubPage("Diagnostics", nav, actions = {
        IconBtn(Icons.Rounded.ContentCopy, "Copy diagnostics", tint = Amp.c.dim) {
            val text = "Ampere ${BuildConfig.VERSION_NAME}, ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}\n" +
                slow.joinToString("\n") { (t, rows) -> "\n[$t]\n" + rows.joinToString("\n") { "${it.first}: ${it.second}" } }
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ampere diagnostics", text))
        }
    }) {
        slow.forEach { (t, rows) ->
            item(t) {
                Panel {
                    T(t, Type.section)
                    Spacer(Modifier.height(6.dp))
                    rows.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), Arrangement.spacedBy(12.dp)) {
                            T(k, Type.mono, Amp.c.dim, Modifier.weight(1f))
                            T(v, Type.mono, if (v == "unavailable" || v == "blocked") Amp.c.faint else Amp.c.text, Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                }
            }
        }
        item { Section("If something looks wrong") }
        item { T("Copy this page and include it in a bug report. It holds no personal data.", Type.small, Amp.c.dim, Modifier.padding(horizontal = 6.dp)) }
    }
}

fun openBatterySettings(ctx: android.content.Context) {
    runCatching { ctx.startActivity(Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
