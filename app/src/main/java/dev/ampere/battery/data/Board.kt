package dev.ampere.battery.data

import dev.ampere.battery.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BoardRow(
    val id: Long, val nick: String, val country: String, val device: String, val vp: Int,
    val t0100: Int?, val t2080: Int?, val t40100: Int?, val peakMw: Int, val cycles: Float,
)

enum class BoardMetric(val label: String, val column: String, val asc: Boolean) {
    VP("Rank", "vp", false),
    T2080("20 → 80", "t_20_80", true),
    T0100("0 → 100", "t_0_100", true),
    T40100("40 → 100", "t_40_100", true),
    PEAK("Peak W", "peak_mw", false),
    CYCLES("Cycles", "cycles", false),
}

/**
 * Optional worldwide board (see backend/supabase.sql). Compiled in, but the app only gets the internet
 * permission when a backend is configured at build time, and nothing is sent until the user joins.
 */
object Board {
    val configured: Boolean = BuildConfig.BOARD_URL.isNotEmpty() && BuildConfig.BOARD_KEY.isNotEmpty()

    private fun call(path: String, body: JSONObject?): String {
        val c = URL(BuildConfig.BOARD_URL + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("apikey", BuildConfig.BOARD_KEY)
            c.setRequestProperty("Authorization", "Bearer " + BuildConfig.BOARD_KEY)
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (c.responseCode !in 200..299) error("The board answered ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun JSONObject.intOrNull(k: String): Int? = if (isNull(k)) null else optInt(k)

    suspend fun top(m: BoardMetric): Result<List<BoardRow>> = withContext(Dispatchers.IO) {
        runCatching {
            val order = "${m.column}.${if (m.asc) "asc" else "desc"}"
            val a = JSONArray(call("/rest/v1/board?select=*&${m.column}=not.is.null&order=$order&limit=100", null))
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                BoardRow(
                    o.getLong("id"), o.optString("nick"), o.optString("country"), o.optString("device"), o.optInt("vp"),
                    o.intOrNull("t_0_100"), o.intOrNull("t_20_80"), o.intOrNull("t_40_100"), o.optInt("peak_mw"), o.optDouble("cycles", 0.0).toFloat(),
                )
            }
        }
    }

    suspend fun submit(token: String, r: BoardRow): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            call(
                "/rest/v1/rpc/submit_entry",
                JSONObject().put("p_token", token).put("p_nick", r.nick).put("p_country", r.country).put("p_device", r.device).put("p_vp", r.vp)
                    .put("p_t0100", r.t0100 ?: JSONObject.NULL).put("p_t2080", r.t2080 ?: JSONObject.NULL).put("p_t40100", r.t40100 ?: JSONObject.NULL)
                    .put("p_peak", r.peakMw).put("p_cycles", r.cycles.toDouble()),
            )
            Unit
        }
    }

    suspend fun remove(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            call("/rest/v1/rpc/remove_entry", JSONObject().put("p_token", token))
            Unit
        }
    }

    suspend fun report(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            call("/rest/v1/rpc/report_entry", JSONObject().put("p_id", id))
            Unit
        }
    }
}
