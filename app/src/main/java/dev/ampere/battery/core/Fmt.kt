package dev.ampere.battery.core

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Every number the user reads goes through here. Missing values are always an em dash, never a zero. */
object Fmt {
    const val NONE = "—"

    fun dec(v: Float, d: Int): String = String.format(Locale.US, "%.${d}f", v)

    fun int(v: Int): String = String.format(Locale.US, "%,d", v)

    fun watts(mw: Int?): String = if (mw == null) NONE else dec(abs(mw) / 1000f, if (abs(mw) >= 100_000) 0 else 1)

    fun volts(mv: Int?): String = if (mv == null) NONE else dec(mv / 1000f, 2)

    fun milliAmps(ua: Int?): String = if (ua == null) NONE else int(abs(ua) / 1000)

    fun amps(ua: Int?): String = if (ua == null) NONE else dec(abs(ua) / 1_000_000f, 2)

    fun mah(uah: Int?): String = if (uah == null) NONE else int((uah / 1000f).roundToInt())

    fun wh(mwh: Float): String = if (mwh >= 1_000_000) "${dec(mwh / 1_000_000f, 2)} kWh" else "${dec(mwh / 1000f, 1)} Wh"

    fun temp(c: Float?, f: Boolean, d: Int = 1): String =
        if (c == null) NONE else dec(if (f) c * 9 / 5 + 32 else c, d) + if (f) "°F" else "°C"

    fun pct(v: Float, d: Int = 0): String = dec(v, d) + "%"

    /** 1d 4h, 1h 24m, 12m, 45s */
    fun duration(ms: Long?): String {
        if (ms == null || ms < 0) return NONE
        val s = ms / 1000
        val d = s / 86400
        val h = s % 86400 / 3600
        val m = s % 3600 / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    /** 12:08, for records where seconds matter. */
    fun split(sec: Int): String {
        val h = sec / 3600
        val m = sec % 3600 / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun stopwatch(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }

    fun clock(ts: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))

    fun date(ts: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ts))

    fun dayShort(ts: Long): String = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))

    fun dayLabel(ts: Long): String {
        val zone = ZoneId.systemDefault()
        val d = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        val now = LocalDate.now(zone)
        return when (d) {
            now -> "Today"
            now.minusDays(1) -> "Yesterday"
            else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ts))
        }
    }

    fun epochDay(ts: Long): Long = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    fun dayStart(epochDay: Long): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

data class Country(val code: String, val name: String) {
    val flag: String get() = Countries.flag(code)
}

@Suppress("DEPRECATION")
object Countries {
    val all: List<Country> by lazy {
        Locale.getISOCountries().map { Country(it, Locale("", it).getDisplayCountry(Locale.ENGLISH)) }.sortedBy { it.name }
    }

    fun flag(code: String): String =
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) "🏳"
        else code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    fun name(code: String): String = Locale("", code).getDisplayCountry(Locale.ENGLISH).ifEmpty { code }
}
