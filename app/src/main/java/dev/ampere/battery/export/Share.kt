package dev.ampere.battery.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import dev.ampere.battery.Container
import dev.ampere.battery.Derived
import dev.ampere.battery.R
import dev.ampere.battery.core.Countries
import dev.ampere.battery.core.Fmt
import dev.ampere.battery.data.Sample
import dev.ampere.battery.data.Settings
import dev.ampere.battery.domain.Achievements
import dev.ampere.battery.domain.Bracket
import dev.ampere.battery.domain.Confidence
import dev.ampere.battery.domain.Grader
import dev.ampere.battery.domain.HealthCalc
import dev.ampere.battery.domain.Insights
import dev.ampere.battery.domain.Lttb
import dev.ampere.battery.domain.Ranks
import dev.ampere.battery.hw.DeviceInfo
import dev.ampere.battery.hw.PhoneSpecs
import dev.ampere.battery.ui.component.metal
import dev.ampere.battery.ui.screens.displayNick
import dev.ampere.battery.ui.screens.plugName
import dev.ampere.battery.ui.theme.LightColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object Share {
    private fun file(ctx: Context, name: String): File = File(ctx.cacheDir, "share").apply { mkdirs() }.let { File(it, name) }

    private suspend fun send(ctx: Context, f: File, mime: String, title: String) = withContext(Dispatchers.Main) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(title, uri)
        ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    suspend fun pdf(ctx: Context, c: Container, s: Settings, d: Derived, dev: DeviceInfo?) {
        val now = System.currentTimeMillis()
        val week = withContext(Dispatchers.IO) { c.dao.samples(now - 7 * 86_400_000L, now) }
        val f = withContext(Dispatchers.Default) {
            file(ctx, "Ampere report ${stamp()}.pdf").also { out -> out.outputStream().use { Report(ctx, c, s, d, dev, week).write(it) } }
        }
        send(ctx, f, "application/pdf", "Ampere battery report")
    }

    suspend fun csv(ctx: Context, d: Derived) {
        val f = withContext(Dispatchers.IO) {
            file(ctx, "Ampere sessions ${stamp()}.csv").also { out ->
                out.bufferedWriter().use { w ->
                    w.write("type,start,end,start_pct,end_pct,duration_min,source,avg_w,peak_w,energy_wh,charge_mah,avg_temp_c,peak_temp_c,min_v,max_v,screen_on_min,grade,score\n")
                    val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    (d.stats.charges + d.stats.drains).sortedByDescending { it.startTs }.forEach { x ->
                        w.write(
                            listOf(
                                if (x.isCharge) "charge" else "discharge", df.format(Date(x.startTs)), df.format(Date(x.endTs)), x.startLevel, x.endLevel,
                                x.durationMs / 60_000, if (x.isCharge) plugName(x.plug) else "", Fmt.dec(x.avgMw / 1000f, 2), Fmt.dec(x.peakMw / 1000f, 2),
                                Fmt.dec(x.energyMwh / 1000f, 3), (x.chargeUah / 1000).roundToInt(), x.avgTempT / 10f, x.peakTempT / 10f, x.minMv / 1000f, x.maxMv / 1000f,
                                x.screenOnMs / 60_000, if (x.score >= 0) Grader.letter(x.score) else "", if (x.score >= 0) x.score else "",
                            ).joinToString(",") + "\n",
                        )
                    }
                }
            }
        }
        send(ctx, f, "text/csv", "Ampere sessions")
    }

    suspend fun samples(ctx: Context, c: Container) {
        val now = System.currentTimeMillis()
        val f = withContext(Dispatchers.IO) {
            file(ctx, "Ampere samples ${stamp()}.csv").also { out ->
                out.bufferedWriter().use { w ->
                    w.write("time,level,voltage_mv,current_ua,power_mw,charge_uah,temp_c,charging,plug,thermal,screen_on\n")
                    val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    c.dao.samples(now - 7 * 86_400_000L, now).forEach { x ->
                        w.write("${df.format(Date(x.ts))},${x.level},${x.mv ?: ""},${x.ua ?: ""},${x.mw ?: ""},${x.cc ?: ""},${x.tempT / 10f},${x.charging},${x.plug},${x.thermal},${x.screenOn}\n")
                    }
                }
            }
        }
        send(ctx, f, "text/csv", "Ampere samples")
    }

    suspend fun card(ctx: Context, c: Container, s: Settings, d: Derived, dev: DeviceInfo?) {
        val f = withContext(Dispatchers.Default) {
            file(ctx, "Ampere card.png").also { out ->
                val b = Card(ctx, s, d, dev).draw()
                out.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle()
            }
        }
        send(ctx, f, "image/png", "My Ampere card")
    }
}

private class Fonts(ctx: Context) {
    private val base: Typeface = ResourcesCompat.getFont(ctx, R.font.geist) ?: Typeface.DEFAULT
    private val mono: Typeface = ResourcesCompat.getFont(ctx, R.font.geist_mono) ?: Typeface.MONOSPACE

    private val cache = HashMap<Pair<Int, Boolean>, Typeface>()

    fun paint(size: Float, color: Int, weight: Int = 400, monospace: Boolean = false, align: Paint.Align = Paint.Align.LEFT) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        // One typeface per weight: the PDF embeds every distinct instance, so caching keeps files small.
        typeface = cache.getOrPut(weight to monospace) {
            Paint().run {
                typeface = if (monospace) mono else base
                runCatching { fontVariationSettings = "'wght' $weight" }
                typeface
            }
        }
        fontFeatureSettings = "tnum"
        textSize = size
        this.color = color
        textAlign = align
        if (size >= 20) letterSpacing = -0.025f
    }
}

/** A4, 595 × 842 pt. Same colours as the app's light theme, so printouts and screens agree. */
private class Report(
    private val ctx: Context, private val c: Container, private val s: Settings, private val d: Derived,
    private val dev: DeviceInfo?, private val week: List<Sample>,
) {
    private val fonts = Fonts(ctx)
    private val ink = 0xFF14161A.toInt()
    private val dim = 0xFF5C636E.toInt()
    private val faint = 0xFF9AA1AB.toInt()
    private val line = 0xFFE1E4E9.toInt()
    private val wash = 0xFFF2F4F6.toInt()
    private val k = LightColors
    private val charge = k.charge.toArgb()
    private val power = k.power.toArgb()
    private val heat = k.heat.toArgb()
    private val l = 48f
    private val r = 547f
    private val doc = PdfDocument()
    private lateinit var page: PdfDocument.Page
    private lateinit var cv: Canvas
    private var n = 0
    private var y = 0f

    private fun fill(col: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col }
    private fun stroke(col: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col
        style = Paint.Style.STROKE
        strokeWidth = w
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun open() {
        n++
        page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
        cv = page.canvas
        y = 56f
    }

    private fun close() {
        cv.drawText("Ampere battery report", l, 812f, fonts.paint(8f, faint))
        cv.drawText("${dev?.name ?: ""}, ${Fmt.date(System.currentTimeMillis())}, page $n", r, 812f, fonts.paint(8f, faint, align = Paint.Align.RIGHT))
        doc.finishPage(page)
    }

    private fun need(h: Float) {
        if (y + h > 786f) {
            close()
            open()
        }
    }

    private fun h2(t: String) {
        need(60f)
        y += 24f
        cv.drawText(t, l, y, fonts.paint(14f, ink, 600))
        y += 10f
    }

    private fun wrap(t: String, p: Paint, w: Float): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (word in t.split(' ')) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(next) > w && cur.isNotEmpty()) {
                out += cur
                cur = word
            } else cur = next
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun text(t: String, size: Float = 9.5f, col: Int = dim, x: Float = l, w: Float = r - l) {
        val p = fonts.paint(size, col)
        for (ln in wrap(t, p, w)) {
            need(size * 1.5f)
            y += size * 1.45f
            cv.drawText(ln, x, y, p)
        }
    }

    private fun kv(rows: List<Pair<String, String>>, cols: Int = 2) {
        val gap = 24f
        val w = (r - l - gap * (cols - 1)) / cols
        rows.chunked(cols).forEach { row ->
            need(20f)
            y += 18f
            row.forEachIndexed { i, (a, b) ->
                val x = l + i * (w + gap)
                cv.drawText(a, x, y, fonts.paint(9.5f, dim))
                val bp = fonts.paint(9.5f, ink, 500, align = Paint.Align.RIGHT)
                val room = w - fonts.paint(9.5f, dim).measureText(a) - 10
                var v = b
                while (v.length > 4 && bp.measureText(v) > room) v = v.dropLast(2)
                if (v != b) v = v.trimEnd() + "…"
                cv.drawText(v, x + w, y, bp)
                cv.drawLine(x, y + 5.5f, x + w, y + 5.5f, stroke(line, 0.5f))
            }
        }
        y += 8f
    }

    private fun tiles(items: List<Triple<String, String, String>>) {
        val gap = 10f
        val w = (r - l - gap * (items.size - 1)) / items.size
        need(64f)
        y += 8f
        items.forEachIndexed { i, (a, b, sub) ->
            val x = l + i * (w + gap)
            cv.drawRoundRect(RectF(x, y, x + w, y + 54f), 9f, 9f, fill(wash))
            cv.drawText(a, x + 10, y + 16, fonts.paint(8f, dim))
            cv.drawText(b, x + 10, y + 34, fonts.paint(15f, ink, 500))
            cv.drawText(sub, x + 10, y + 46, fonts.paint(7f, faint))
        }
        y += 60f
    }

    private fun chart(title: String, xs: LongArray, ys: FloatArray, col: Int, h: Float, fy: (Float) -> String, fx: (Long) -> String, gap: Long, range: Pair<Float, Float>? = null) {
        if (xs.size < 3) return
        need(h + 44f)
        y += 16f
        cv.drawText(title, l, y, fonts.paint(9.5f, dim))
        y += 8f
        val idx = Lttb.pick(xs, ys, 300)
        val lo0 = ys.min()
        val hi0 = ys.max()
        val pad = max((hi0 - lo0) * 0.1f, 0.05f)
        val lo = range?.first ?: (lo0 - pad)
        val hi = range?.second ?: (hi0 + pad)
        val x0 = xs.first()
        val x1 = max(xs.last(), x0 + 1)
        val w = r - l - 40
        for (i in 0..2) cv.drawLine(l, y + h * i / 2, l + w, y + h * i / 2, stroke(line, 0.5f))
        val p = Path()
        var last = 0L
        idx.forEachIndexed { i, j ->
            val px = l + (xs[j] - x0).toFloat() / (x1 - x0) * w
            val py = y + h * (1 - (ys[j] - lo) / (hi - lo))
            if (i == 0 || xs[j] - last > gap) p.moveTo(px, py) else p.lineTo(px, py)
            last = xs[j]
        }
        cv.drawPath(p, stroke(col, 1.3f))
        cv.drawText(fy(hi), r, y + 5, fonts.paint(7.5f, faint, align = Paint.Align.RIGHT))
        cv.drawText(fy(lo), r, y + h, fonts.paint(7.5f, faint, align = Paint.Align.RIGHT))
        cv.drawText(fx(x0), l, y + h + 11, fonts.paint(7.5f, faint))
        cv.drawText(fx(x1), l + w, y + h + 11, fonts.paint(7.5f, faint, align = Paint.Align.RIGHT))
        y += h + 16f
    }

    fun write(out: java.io.OutputStream) {
        val h = d.health
        val st = d.stats
        val live = c.engine.live.value
        open()
        // Header
        dial(cv, 66f, 62f, 18f, live.level, charge, line)
        cv.drawText(dev?.name ?: "Phone", 98f, 60f, fonts.paint(22f, ink, 600))
        cv.drawText(
            listOfNotNull(dev?.model?.takeIf { it != dev.name }, if (s.country.isNotEmpty()) Countries.name(s.country) else null, Fmt.date(System.currentTimeMillis())).joinToString(", "),
            98f, 76f, fonts.paint(9.5f, dim),
        )
        y = 100f
        // Health hero
        cv.drawRoundRect(RectF(l, y, r, y + 118f), 14f, 14f, fill(wash))
        val pre = if (h.confidence == Confidence.MEASURED) "" else "~"
        cv.drawText(h.pct?.let { "$pre${it.roundToInt()}%" } ?: Fmt.NONE, l + 20, y + 58, fonts.paint(46f, ink, 300))
        cv.drawText("real capacity" + (h.pct?.let { ", " + HealthCalc.band(it).first.lowercase() } ?: ""), l + 20, y + 76, fonts.paint(9.5f, dim))
        val bx = l + 210
        val bw = r - 20 - bx
        fun bar(yy: Float, label: String, mah: Int?, f: Float, col: Int) {
            cv.drawText(label, bx, yy - 6, fonts.paint(9f, dim))
            cv.drawText(mah?.let { "${Fmt.int(it)} mAh" } ?: Fmt.NONE, bx + bw, yy - 6, fonts.paint(9f, ink, 500, align = Paint.Align.RIGHT))
            cv.drawRoundRect(RectF(bx, yy, bx + bw, yy + 10), 5f, 5f, fill(line))
            if (f > 0) cv.drawRoundRect(RectF(bx, yy, bx + bw * min(1f, f), yy + 10), 5f, 5f, fill(col))
        }
        bar(y + 36, "Original", h.designMah, if (h.designMah != null) 1f else 0f, faint)
        bar(y + 74, "Now", h.fullMah, (h.pct ?: 0f) / 100f, charge)
        cv.drawText("${h.confidence.label}, ${h.count} charge${if (h.count == 1) "" else "s"}, ${h.method}", bx, y + 102, fonts.paint(8f, faint))
        y += 124f
        tiles(
            listOf(
                Triple("Cycles tracked", Fmt.dec(st.cycles, 1), if (s.installedAt > 0) "since ${Fmt.date(s.installedAt)}" else ""),
                Triple("Lifetime cycles", live.cycleCount?.toString() ?: Fmt.NONE, if (live.cycleCount != null) "reported by phone" else "not reported"),
                Triple("Energy charged", Fmt.wh(st.energyMwh), "${st.chargeCount} charges"),
                Triple("Peak power", if (st.peakMw > 0) "${Fmt.watts(st.peakMw)} W" else Fmt.NONE, "at the cell"),
            ),
        )
        val week7 = week.filter { it.mw != null }
        chart("Battery level, last 7 days", LongArray(week.size) { week[it].ts }, FloatArray(week.size) { week[it].level.toFloat() }, charge, 78f, { "${it.roundToInt()}%" }, { Fmt.dayShort(it) }, 3 * 3_600_000L, 0f to 100f)
        chart("Power, last 7 days", LongArray(week7.size) { week7[it].ts }, FloatArray(week7.size) { week7[it].mw!! / 1000f }, power, 78f, { "${Fmt.dec(it, 0)} W" }, { Fmt.dayShort(it) }, 3 * 3_600_000L)
        val tp = week.filter { it.tempT != 0 }
        chart("Temperature, last 7 days", LongArray(tp.size) { tp[it].ts }, FloatArray(tp.size) { tp[it].tempT / 10f }, heat, 78f, { Fmt.temp(it, s.fahrenheit, 0) }, { Fmt.dayShort(it) }, 3 * 3_600_000L)

        need(210f)
        h2("Charging records")
        need(30f)
        y += 14f
        val cols = floatArrayOf(l, 250f, 350f, 450f, r)
        val head = listOf("Charge", "Times", "Average", "Best", "Date")
        head.forEachIndexed { i, t -> cv.drawText(t, cols[i], y, fonts.paint(8f, faint, align = if (i == 0) Paint.Align.LEFT else Paint.Align.RIGHT)) }
        st.brackets.forEach { b ->
            need(18f)
            y += 16f
            val row = listOf(b.bracket.label.replace("→", "to") + "%", "${b.count}", b.avgSec?.let { Fmt.split(it) } ?: Fmt.NONE, b.bestSec?.let { Fmt.split(it) } ?: Fmt.NONE, b.bestTs?.let { Fmt.date(it) } ?: "")
            row.forEachIndexed { i, t -> cv.drawText(t, cols[i], y, fonts.paint(9f, if (i == 3 && b.bestSec != null) power else ink, if (i == 3) 500 else 400, align = if (i == 0) Paint.Align.LEFT else Paint.Align.RIGHT)) }
            cv.drawLine(l, y + 5, r, y + 5, stroke(line, 0.5f))
        }
        y += 8f

        h2("Habits and wear")
        kv(
            listOf(
                "Average plug-in level" to (st.avgPlugIn?.let { "${it.roundToInt()}%" } ?: Fmt.NONE), "Average unplug level" to (st.avgUnplug?.let { "${it.roundToInt()}%" } ?: Fmt.NONE),
                "Kept within 20 to 80%" to "${st.gentle}", "Charged to 100%" to "${st.toFull}",
                "From empty to 100%" to "${st.zeroTo100}", "Started under 10%" to "${st.fromUnder10}",
                "Screen-on drain" to (st.drainOnPctH?.let { "${Fmt.dec(it, 1)}% / h" } ?: Fmt.NONE), "Screen-off drain" to (st.drainOffPctH?.let { "${Fmt.dec(it, 1)}% / h" } ?: Fmt.NONE),
                "Time above ${Fmt.temp(40f, s.fahrenheit, 0)}" to Fmt.duration(st.heat40Ms), "Hottest" to (st.maxTempT?.let { Fmt.temp(it / 10f, s.fahrenheit) } ?: Fmt.NONE),
                "Average grade" to (st.avgScore?.let { "${Grader.letter(it.roundToInt())} (${it.roundToInt()})" } ?: Fmt.NONE), "Best screen time" to Fmt.duration(st.bestScreenOnMs.takeIf { it > 0 }),
                "Fastest ten percent" to (st.bestPctPerMin?.let { "${Fmt.dec(it, 2)} %/min" } ?: Fmt.NONE), "Overnight charges" to "${st.nightCharges}",
            ),
        )
        val ins = Insights.of(st, h)
        if (ins.isNotEmpty()) {
            h2("What the data says")
            ins.forEach {
                need(34f)
                y += 4f
                text(it.title, 10f, ink)
                text(it.text)
            }
        }

        h2("Arena")
        kv(
            listOf(
                "Player" to (displayNick(s) + if (s.country.isNotEmpty()) " (${s.country})" else ""), "Rank" to d.game.rank.name,
                "Points" to "${Fmt.int(d.game.vp)} VP", "Achievements" to "${d.game.unlocked.size} of ${Achievements.all.size}",
                "Best goal streak" to "${d.game.bestStreak} days", "Best A streak" to "${st.aStreak} charges",
            ),
        )

        val recent = st.charges.take(14)
        if (recent.isNotEmpty()) {
            need(50f + recent.size * 15f)
            h2("Latest charges")
            need(30f)
            y += 14f
            val cx = floatArrayOf(l, 220f, 300f, 375f, 455f, r)
            listOf("When", "Level", "Time", "Average", "Peak temp", "Grade").forEachIndexed { i, t -> cv.drawText(t, cx[i], y, fonts.paint(8f, faint, align = if (i == 0) Paint.Align.LEFT else Paint.Align.RIGHT)) }
            recent.forEach { x ->
                need(18f)
                y += 15f
                listOf("${Fmt.date(x.startTs)}, ${Fmt.clock(x.startTs)}", "${x.startLevel} to ${x.endLevel}%", Fmt.duration(x.durationMs), "${Fmt.watts(x.avgMw)} W", Fmt.temp(x.peakTempT / 10f, s.fahrenheit), Grader.letter(x.score))
                    .forEachIndexed { i, t -> cv.drawText(t, cx[i], y, fonts.paint(8.5f, ink, align = if (i == 0) Paint.Align.LEFT else Paint.Align.RIGHT)) }
                cv.drawLine(l, y + 4.5f, r, y + 4.5f, stroke(line, 0.5f))
            }
            y += 8f
        }

        if (dev != null) PhoneSpecs.read(ctx, dev).filter { it.title != "Sensors" }.forEach { g ->
            need(50f + g.rows.size / 2 * 18f)
            h2(g.title)
            kv(g.rows)
        }
        y += 10f
        text("All electrical values are measured at the battery, not at the charger. Capacity is estimated from charge that went in over the percentage it filled; see the method and count above for how certain it is. Values this phone does not report are shown as a dash.", 8f, faint)
        close()
        doc.writeTo(out)
        doc.close()
    }
}

internal fun dial(cv: Canvas, cx: Float, cy: Float, rad: Float, level: Int, lit: Int, dim: Int, ticks: Int = 40, len: Float = 0.28f) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = rad * 0.07f * 40 / ticks.coerceAtLeast(40) * 1.3f
        strokeCap = Paint.Cap.ROUND
    }
    for (i in 0 until ticks) {
        val a = Math.toRadians(-90.0 + 360.0 * i / ticks)
        p.color = if (i < level * ticks / 100f) lit else dim
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        cv.drawLine(cx + rad * (1 - len) * c, cy + rad * (1 - len) * s, cx + rad * c, cy + rad * s, p)
    }
}

/** 1080 × 1350 share picture. */
private class Card(ctx: Context, private val s: Settings, private val d: Derived, private val dev: DeviceInfo?) {
    private val fonts = Fonts(ctx)

    fun draw(): Bitmap {
        val b = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        cv.drawColor(0xFF0B0C0E.toInt())
        val rank = d.game.rank
        val (hi, lo) = metal(rank.family)
        val glow = Paint().apply {
            shader = android.graphics.RadialGradient(540f, 300f, 620f, Color.argb(70, Color.red(hi.toArgb()), Color.green(hi.toArgb()), Color.blue(hi.toArgb())), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        cv.drawRect(0f, 0f, 1080f, 1350f, glow)
        badge(cv, 540f, 270f, 250f, hi.toArgb(), lo.toArgb(), rank.step)
        val text = 0xFFF2F3F5.toInt()
        val dim = 0xFF979DA8.toInt()
        val mid = Paint.Align.CENTER
        cv.drawText((if (s.country.isNotEmpty()) Countries.flag(s.country) + " " else "") + displayNick(s), 540f, 500f, fonts.paint(44f, text, 500, align = mid))
        cv.drawText(rank.name, 540f, 584f, fonts.paint(78f, hi.toArgb(), 600, align = mid))
        cv.drawText("${Fmt.int(d.game.vp)} VP" + (Ranks.next(rank)?.let { ", ${Fmt.int(it.at - d.game.vp)} to ${it.name}" } ?: ""), 540f, 642f, fonts.paint(32f, dim, align = mid))
        val st = d.stats
        fun best(a: Int, z: Int) = st.bracket(Bracket(a, z)).bestSec?.let { Fmt.split(it) } ?: Fmt.NONE
        val cells = listOf(
            "0 to 100%" to best(0, 100), "20 to 80%" to best(20, 80), "40 to 100%" to best(40, 100),
            "Peak power" to if (st.peakMw > 0) "${Fmt.watts(st.peakMw)} W" else Fmt.NONE, "Cycles" to Fmt.dec(st.cycles, 1),
            "Real capacity" to (d.health.pct?.let { (if (d.health.confidence == Confidence.MEASURED) "" else "~") + "${it.roundToInt()}%" } ?: Fmt.NONE),
        )
        cells.forEachIndexed { i, (k, v) ->
            val x = 70f + i % 3 * 320f
            val y = 720f + i / 3 * 220f
            cv.drawRoundRect(RectF(x, y, x + 300, y + 196), 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF15181C.toInt() })
            cv.drawText(k, x + 30, y + 62, fonts.paint(28f, dim))
            cv.drawText(v, x + 30, y + 140, fonts.paint(58f, text, 400))
        }
        dial(cv, 100f, 1245f, 34f, 75, 0xFF5FE0A3.toInt(), 0xFF33383F.toInt(), 24, 0.34f)
        cv.drawText("Ampere", 156f, 1258f, fonts.paint(38f, text, 600))
        dev?.let { cv.drawText(it.name, 1010f, 1258f, fonts.paint(34f, dim, align = Paint.Align.RIGHT)) }
        return b
    }

    private fun badge(cv: Canvas, cx: Float, cy: Float, h: Float, hi: Int, lo: Int, step: Int) {
        val w = h * 0.9f
        val x = cx - w / 2
        val y = cy - h / 2
        fun px(f: Float) = x + w * f
        fun py(f: Float) = y + h * f
        val shield = Path().apply {
            moveTo(px(0.5f), py(0f)); lineTo(px(0.96f), py(0.15f)); lineTo(px(0.96f), py(0.55f))
            cubicTo(px(0.96f), py(0.78f), px(0.72f), py(0.92f), px(0.5f), py(1f))
            cubicTo(px(0.28f), py(0.92f), px(0.04f), py(0.78f), px(0.04f), py(0.55f))
            lineTo(px(0.04f), py(0.15f)); close()
        }
        cv.drawPath(shield, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, y, 0f, y + h, hi, lo, Shader.TileMode.CLAMP) })
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9101214.toInt() }
        cv.drawPath(Path().apply {
            moveTo(px(0.55f), py(0.15f)); lineTo(px(0.36f), py(0.36f)); lineTo(px(0.49f), py(0.36f)); lineTo(px(0.45f), py(0.52f)); lineTo(px(0.65f), py(0.30f)); lineTo(px(0.51f), py(0.30f)); close()
        }, ink)
        ink.style = Paint.Style.STROKE
        ink.strokeJoin = Paint.Join.ROUND
        ink.strokeWidth = h * 0.042f
        ink.strokeCap = Paint.Cap.ROUND
        for (i in 0 until step) {
            val yy = py(0.60f + i * 0.077f)
            val half = w * (0.27f - i * 0.035f)
            cv.drawPath(Path().apply { moveTo(cx - half, yy); lineTo(cx, yy + h * 0.055f); lineTo(cx + half, yy) }, ink)
        }
    }
}
