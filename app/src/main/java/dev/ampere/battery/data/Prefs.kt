package dev.ampere.battery.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("ampere")

data class Settings(
    val onboarded: Boolean = false,
    val theme: Int = 0, // 0 system, 1 light, 2 dark
    val fahrenheit: Boolean = false,
    val monitoring: Boolean = false,
    val notifStyle: Int = 2, // 0 %, 1 % + W, 2 % + W + °
    val nick: String = "",
    val country: String = "",
    val manualDesignMah: Int = 0,
    val alertFull: Boolean = true,
    val alertFullAt: Int = 80,
    val alertHot: Boolean = true,
    val alertHotAt: Int = 43,
    val alertLow: Boolean = false,
    val alertLowAt: Int = 20,
    val alertSlow: Boolean = true,
    val alertThrottle: Boolean = false,
    val alertUnlocks: Boolean = true,
    val goalCool: Int = 38, // °C, 0 = off
    val goalCeiling: Int = 85,
    val goalFloor: Int = 20,
    val goalScreenH: Int = 6,
    val goalCycles: Int = 100, // % of a cycle per day
    val curScale: Int = 0, // 0 unknown, 1 µA, 1000 mA
    val curSign: Int = 0, // 0 unknown, 1 normal (+ in), -1 inverted
    val curFingerprint: String = "",
    val installedAt: Long = 0,
    val timerAt: Long = 0,
    val boardToken: String = "",
    val boardOptIn: Boolean = false,
)

class Prefs(private val ctx: Context) {
    object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val theme = intPreferencesKey("theme")
        val fahrenheit = booleanPreferencesKey("fahrenheit")
        val monitoring = booleanPreferencesKey("monitoring")
        val notifStyle = intPreferencesKey("notifStyle")
        val nick = stringPreferencesKey("nick")
        val country = stringPreferencesKey("country")
        val design = intPreferencesKey("design")
        val alertFull = booleanPreferencesKey("alertFull")
        val alertFullAt = intPreferencesKey("alertFullAt")
        val alertHot = booleanPreferencesKey("alertHot")
        val alertHotAt = intPreferencesKey("alertHotAt")
        val alertLow = booleanPreferencesKey("alertLow")
        val alertLowAt = intPreferencesKey("alertLowAt")
        val alertSlow = booleanPreferencesKey("alertSlow")
        val alertThrottle = booleanPreferencesKey("alertThrottle")
        val alertUnlocks = booleanPreferencesKey("alertUnlocks")
        val goalCool = intPreferencesKey("goalCool")
        val goalCeiling = intPreferencesKey("goalCeiling")
        val goalFloor = intPreferencesKey("goalFloor")
        val goalScreenH = intPreferencesKey("goalScreenH")
        val goalCycles = intPreferencesKey("goalCycles")
        val curScale = intPreferencesKey("curScale")
        val curSign = intPreferencesKey("curSign")
        val curFp = stringPreferencesKey("curFp")
        val installedAt = longPreferencesKey("installedAt")
        val timerAt = longPreferencesKey("timerAt")
        val boardToken = stringPreferencesKey("boardToken")
        val boardOptIn = booleanPreferencesKey("boardOptIn")
    }

    val flow: Flow<Settings> = ctx.store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.read() }

    suspend fun edit(block: (MutablePreferences) -> Unit) {
        ctx.store.edit { block(it) }
    }

    private fun Preferences.read(): Settings {
        val d = Settings()
        return Settings(
            onboarded = this[K.onboarded] ?: d.onboarded,
            theme = this[K.theme] ?: d.theme,
            fahrenheit = this[K.fahrenheit] ?: d.fahrenheit,
            monitoring = this[K.monitoring] ?: d.monitoring,
            notifStyle = this[K.notifStyle] ?: d.notifStyle,
            nick = this[K.nick] ?: d.nick,
            country = this[K.country] ?: d.country,
            manualDesignMah = this[K.design] ?: 0,
            alertFull = this[K.alertFull] ?: d.alertFull,
            alertFullAt = this[K.alertFullAt] ?: d.alertFullAt,
            alertHot = this[K.alertHot] ?: d.alertHot,
            alertHotAt = this[K.alertHotAt] ?: d.alertHotAt,
            alertLow = this[K.alertLow] ?: d.alertLow,
            alertLowAt = this[K.alertLowAt] ?: d.alertLowAt,
            alertSlow = this[K.alertSlow] ?: d.alertSlow,
            alertThrottle = this[K.alertThrottle] ?: d.alertThrottle,
            alertUnlocks = this[K.alertUnlocks] ?: d.alertUnlocks,
            goalCool = this[K.goalCool] ?: d.goalCool,
            goalCeiling = this[K.goalCeiling] ?: d.goalCeiling,
            goalFloor = this[K.goalFloor] ?: d.goalFloor,
            goalScreenH = this[K.goalScreenH] ?: d.goalScreenH,
            goalCycles = this[K.goalCycles] ?: d.goalCycles,
            curScale = this[K.curScale] ?: 0,
            curSign = this[K.curSign] ?: 0,
            curFingerprint = this[K.curFp] ?: "",
            installedAt = this[K.installedAt] ?: 0,
            timerAt = this[K.timerAt] ?: 0,
            boardToken = this[K.boardToken] ?: "",
            boardOptIn = this[K.boardOptIn] ?: false,
        )
    }
}
