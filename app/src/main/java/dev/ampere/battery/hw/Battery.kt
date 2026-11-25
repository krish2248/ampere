package dev.ampere.battery.hw

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

/** Untouched values from the platform. Null always means "this phone does not say". */
data class RawBattery(
    val level: Int?,
    val status: Int,
    val plug: Int,
    val health: Int,
    val voltageRaw: Int?,
    val tempT: Int?,
    val tech: String?,
    val currentNow: Int?,
    val currentAvg: Int?,
    val ccUah: Int?,
    val energyNwh: Long?,
    val sysEtaMs: Long?,
    val cycleCount: Int?,
    val maxUa: Int?,
    val maxUv: Int?,
    val soh: Int?,
    val extras: Map<String, String>,
)

class BatteryReader(private val ctx: Context) {
    private val bm = ctx.getSystemService(BatteryManager::class.java)
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    /** Debug builds swap this to fake a charger. Identity in release. */
    @Volatile
    var tamper: (RawBattery) -> RawBattery = { it }

    fun read(): RawBattery {
        val i = runCatching { ctx.registerReceiver(null, filter) }.getOrNull()
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val raw = RawBattery(
            level = if (level >= 0 && scale > 0) level * 100 / scale else null,
            status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, 1) ?: 1,
            plug = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0,
            health = i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 1) ?: 1,
            voltageRaw = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 },
            tempT = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it in -200..800 },
            tech = i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() },
            currentNow = intProp(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            currentAvg = intProp(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            ccUah = intProp(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.let(::normaliseCc),
            energyNwh = runCatching { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) }.getOrNull()
                ?.takeIf { it > 0 },
            sysEtaMs = runCatching { bm.computeChargeTimeRemaining() }.getOrNull()?.takeIf { it > 0 },
            // Public since API 34; some vendors send it earlier under their own names.
            cycleCount = i?.let { readCycle(it) },
            maxUa = i?.getIntExtra("max_charging_current", -1)?.takeIf { it in 50_000..20_000_000 },
            maxUv = i?.getIntExtra("max_charging_voltage", -1)?.takeIf { it in 3_000_000..60_000_000 },
            // Needs a system permission on most phones; a few hand it out.
            soh = if (Build.VERSION.SDK_INT >= 34) intProp(10)?.takeIf { it in 1..100 } else null,
            extras = runCatching {
                @Suppress("DEPRECATION")
                i?.extras?.let { b -> b.keySet().sorted().associateWith { k -> b.get(k).toString() } }
            }.getOrNull() ?: emptyMap(),
        )
        return tamper(raw)
    }

    private fun readCycle(i: Intent): Int? =
        listOf("android.os.extra.CYCLE_COUNT", "battery_cycle", "cycle_count")
            .firstNotNullOfOrNull { k -> i.getIntExtra(k, -1).takeIf { it in 1..20_000 } }

    private fun normaliseCc(v: Int): Int? = when {
        v >= 100_000 -> v
        v in 100..30_000 -> v * 1000 // mAh on a few phones
        else -> null
    }

    private fun intProp(id: Int): Int? =
        runCatching { bm.getIntProperty(id) }.getOrNull()?.takeIf { it != Int.MIN_VALUE && it != Int.MAX_VALUE }

    val screenOn: Boolean get() = pm.isInteractive

    fun thermalStatus(): Int = runCatching { pm.currentThermalStatus }.getOrDefault(0)

    private var lastHeadroomAt = 0L
    private var lastHeadroom: Float? = null

    /** The platform returns NaN if asked too often, so ask every 10 s at most. 1.0 = throttling starts. */
    fun headroom(now: Long): Float? {
        if (Build.VERSION.SDK_INT < 30) return null
        if (now - lastHeadroomAt >= 10_000) {
            lastHeadroomAt = now
            val v = runCatching { pm.getThermalHeadroom(0) }.getOrNull()
            lastHeadroom = v?.takeIf { !it.isNaN() && it > 0f }
        }
        return lastHeadroom
    }
}

object Units {
    /** Accepts mV, µV or V. Two-cell packs go to ~9 V. */
    fun millivolts(raw: Int?): Int? = when (raw) {
        null -> null
        in 2_000..9_500 -> raw
        in 2_000_000..9_500_000 -> raw / 1000
        in 2..9 -> raw * 1000
        else -> null
    }

    fun powerMw(mv: Int?, ua: Int?): Int? = if (mv == null || ua == null) null else (mv.toLong() * ua / 1_000_000L).toInt()
}

object Sysfs {
    private val bases = listOf("battery", "bms", "main-battery", "maxfg").map { "/sys/class/power_supply/$it" }

    fun int(vararg names: String): Int? = bases.firstNotNullOfOrNull { b ->
        names.firstNotNullOfOrNull { n ->
            runCatching { File("$b/$n").readText().trim().toInt() }.getOrNull()
        }
    }

    fun raw(path: String): String = runCatching { File(path).readText().trim() }.getOrElse { "blocked" }
}
