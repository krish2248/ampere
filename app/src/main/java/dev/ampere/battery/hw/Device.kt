package dev.ampere.battery.hw

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.res.Resources
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.view.Display
import dev.ampere.battery.core.Fmt
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Published charging spec for a phone. */
data class PhoneSpec(
    val key: String,
    val brand: String,
    val country: String,
    val name: String,
    val mah: Int,
    val wiredW: Float,
    val wirelessW: Float,
    val year: Int,
)

data class DeviceInfo(
    val name: String,
    val brand: String,
    val model: String,
    val recognised: Boolean,
    val spec: PhoneSpec?,
)

class DeviceDb(private val ctx: Context) {
    val specs: List<PhoneSpec> by lazy {
        ctx.assets.open("specs.tsv").bufferedReader().useLines { lines ->
            lines.map { it.split('\t') }.filter { it.size == 8 }
                .map { PhoneSpec(it[0], it[1], it[2], it[3], it[4].toInt(), it[5].toFloat(), it[6].toFloat(), it[7].toInt()) }
                .toList()
        }
    }

    /** Scans Google's public Play catalogue (bundled, 25k models) for this exact model number. */
    private fun catalogue(model: String): Pair<String, String>? = runCatching {
        val prefix = model + "\t"
        ctx.assets.open("models.tsv").bufferedReader().useLines { l -> l.firstOrNull { it.startsWith(prefix, ignoreCase = true) } }
            ?.split('\t')?.let { it[1] to it[2] }
    }.getOrNull()

    val info: DeviceInfo by lazy { identify(Build.MODEL, Build.BRAND, Build.MANUFACTURER) }

    fun identify(model: String, brand: String, manufacturer: String): DeviceInfo {
        val hit = catalogue(model)
        val prop = listOf(
            "ro.product.marketname", "ro.config.marketing_name", "ro.vendor.oplus.market.name",
            "ro.oppo.market.name", "ro.vivo.market.name",
        ).firstNotNullOfOrNull { systemProp(it).takeIf { v -> v.length > 2 } }
        val name = hit?.second ?: prop ?: model
        val niceBrand = hit?.first?.takeIf { it.isNotBlank() } ?: brand.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val owners = setOfNotNull(hit?.first, brand, manufacturer).map { alias(it.lowercase(Locale.ROOT)) }.toSet()
        val spec = matchSpec(specs, name, model, owners)
        return DeviceInfo(name, niceBrand, model, hit != null || prop != null || spec != null, spec)
    }

    companion object {
        private val prefixes = listOf(
            "motorola", "samsung", "huawei", "honor", "oneplus", "poco", "xiaomi", "realme", "vivo", "oppo",
            "infinix", "tecno", "nothing", "sony", "asus",
        )

        fun alias(b: String) = when (b) {
            "iqoo" -> "vivo"
            "cmf" -> "nothing"
            "nubia" -> "redmagic"
            "lge" -> "lg"
            "hmd global" -> "nokia"
            else -> b
        }

        /** Name must match AND the brand must own it: "12" is both a Xiaomi and a OnePlus. */
        fun matchSpec(specs: List<PhoneSpec>, name: String, model: String, owners: Set<String>): PhoneSpec? {
            val key = norm(name)
            val keyModel = norm(model)
            return specs.firstOrNull { (it.key == key || it.key == keyModel) && it.brand.lowercase(Locale.ROOT) in owners }
        }

        /** Must stay in step with tools/build_assets.py norm(). */
        fun norm(s: String): String {
            var n = s.lowercase(Locale.ROOT).replace("+", " plus ")
            for (b in prefixes) if (n.startsWith("$b ")) n = n.substring(b.length + 1)
            n = n.replace(Regex("(?<!\\()\\b5g\\b"), "")
            return n.replace(Regex("[^a-z0-9]"), "")
        }

        @SuppressLint("PrivateApi")
        fun systemProp(key: String): String = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
        }.getOrDefault("").trim()
    }
}

enum class DesignSource(val label: String) {
    MANUAL("you entered it"),
    DATABASE("published spec for this phone"),
    PROFILE("phone firmware (power profile)"),
    SYSFS("battery fuel gauge"),
    RESOURCE("system resource"),
    NONE("not found"),
}

data class Design(val mah: Int?, val source: DesignSource)

class DesignResolver(private val ctx: Context, private val db: DeviceDb) {
    private val auto: Design by lazy {
        db.info.spec?.let { return@lazy Design(it.mah, DesignSource.DATABASE) }
        profile()?.let { return@lazy Design(it, DesignSource.PROFILE) }
        sysfs()?.let { return@lazy Design(it, DesignSource.SYSFS) }
        resource()?.let { return@lazy Design(it, DesignSource.RESOURCE) }
        Design(null, DesignSource.NONE)
    }

    fun resolve(manualMah: Int): Design = if (manualMah in 500..30_000) Design(manualMah, DesignSource.MANUAL) else auto

    @SuppressLint("PrivateApi")
    fun profile(): Int? = runCatching {
        val c = Class.forName("com.android.internal.os.PowerProfile")
        val p = c.getConstructor(Context::class.java).newInstance(ctx)
        (c.getMethod("getBatteryCapacity").invoke(p) as Double).roundToInt()
    }.getOrNull()?.takeIf { it in 1500..30_000 } // 1000 is the AOSP placeholder

    fun sysfs(): Int? = Sysfs.int("charge_full_design")?.let { if (it > 100_000) it / 1000 else it }?.takeIf { it in 1000..30_000 }

    @SuppressLint("DiscouragedApi")
    fun resource(): Int? = runCatching {
        val r = Resources.getSystem()
        val id = r.getIdentifier("config_batteryCapacity", "integer", "android")
        if (id != 0) r.getInteger(id) else 0
    }.getOrNull()?.takeIf { it in 1500..30_000 }
}

/** Grouped label/value rows for the Phone screen. Nothing here needs a permission or leaves the phone. */
data class SpecGroup(val title: String, val rows: List<Pair<String, String>>)

object PhoneSpecs {
    fun read(ctx: Context, info: DeviceInfo): List<SpecGroup> = listOf(
        SpecGroup("Identity", listOfNotNull(
            "Name" to info.name,
            "Brand" to info.brand,
            "Model" to info.model,
            "Codename" to Build.DEVICE,
            "Board" to Build.BOARD,
            info.spec?.let { "Released" to it.year.toString() },
        )),
        SpecGroup("Chip", chip()),
        SpecGroup("Memory", memory(ctx)),
        SpecGroup("Display", display(ctx)),
        SpecGroup("Software", software()),
        SpecGroup("Sensors", sensors(ctx)),
    )

    private fun chip(): List<Pair<String, String>> {
        val cores = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until cores).mapNotNull {
            Sysfs.raw("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").toIntOrNull()?.takeIf { f -> f > 100_000 }
        }
        val clusters = freqs.groupingBy { it }.eachCount().entries.sortedByDescending { it.key }
            .joinToString(" + ") { (f, n) -> "$n × ${Fmt.dec(f / 1_000_000f, 2)} GHz" }
        val soc = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ")
        } else {
            ""
        }
        return listOfNotNull(
            "SoC" to soc.ifEmpty { Build.HARDWARE },
            "Cores" to cores.toString(),
            clusters.takeIf { it.isNotEmpty() }?.let { "Clusters" to it },
            "Architecture" to (Build.SUPPORTED_ABIS.firstOrNull() ?: Fmt.NONE),
            gpu()?.let { "GPU" to it },
        )
    }

    private fun gpu(): String? = runCatching {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(d, v, 0, v, 1)
        val cfg = arrayOfNulls<android.opengl.EGLConfig>(1)
        val n = IntArray(1)
        val want = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 4, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        EGL14.eglChooseConfig(d, want, 0, cfg, 0, 1, n, 0)
        val c = EGL14.eglCreateContext(d, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val s = EGL14.eglCreatePbufferSurface(d, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(d, s, s, c)
        val name = GLES20.glGetString(GLES20.GL_RENDERER)
        // Only tear down what we made; the display itself is shared with the UI renderer.
        EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(d, s)
        EGL14.eglDestroyContext(d, c)
        name
    }.getOrNull()

    private fun memory(ctx: Context): List<Pair<String, String>> {
        val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val st = StatFs(Environment.getDataDirectory().path)
        return listOf(
            "RAM" to "${gb(mi.totalMem)} GB usable",
            "RAM free" to "${gb(mi.availMem)} GB",
            "Storage" to "${gb(st.totalBytes)} GB usable",
            "Storage free" to "${gb(st.availableBytes)} GB",
        )
    }

    private fun gb(b: Long) = Fmt.dec(b / 1_073_741_824f, 1)

    @Suppress("DEPRECATION")
    private fun display(ctx: Context): List<Pair<String, String>> {
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
        val m = android.util.DisplayMetrics().also { d.getRealMetrics(it) }
        val mode = d.supportedModes.maxByOrNull { it.physicalWidth }
        val w = mode?.physicalWidth ?: m.widthPixels
        val h = mode?.physicalHeight ?: m.heightPixels
        val inch = sqrt((m.widthPixels / m.xdpi).let { it * it } + (m.heightPixels / m.ydpi).let { it * it })
        return listOf(
            "Panel" to "$w × $h",
            "Size" to "≈ ${Fmt.dec(inch, 1)}\"",
            "Density" to "${m.densityDpi} dpi",
            "Refresh" to "up to ${d.supportedModes.maxOf { it.refreshRate }.roundToInt()} Hz",
            "HDR" to if (d.isHdr) "Yes" else "No",
            "Wide colour" to if (d.isWideColorGamut) "Yes" else "No",
        )
    }

    private fun software(): List<Pair<String, String>> {
        val oneUi = DeviceDb.systemProp("ro.build.version.oneui").toIntOrNull()?.let { "${it / 10000}.${it % 10000 / 100}" }
        val hyper = DeviceDb.systemProp("ro.mi.os.version.name").ifEmpty { null }
        val up = SystemClock.elapsedRealtime()
        return listOfNotNull(
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            oneUi?.let { "One UI" to it },
            hyper?.let { "HyperOS" to it },
            "Security patch" to Build.VERSION.SECURITY_PATCH,
            "Build" to Build.DISPLAY,
            "Kernel" to (System.getProperty("os.version") ?: Fmt.NONE),
            "Uptime" to Fmt.duration(up),
            "Deep sleep" to Fmt.pct((up - SystemClock.uptimeMillis()) * 100f / up) + " of uptime",
        )
    }

    private fun sensors(ctx: Context): List<Pair<String, String>> {
        val all = ctx.getSystemService(SensorManager::class.java).getSensorList(Sensor.TYPE_ALL)
        fun has(t: Int) = all.firstOrNull { it.type == t }?.name ?: "None"
        return listOf(
            "Total" to all.size.toString(),
            "Accelerometer" to has(Sensor.TYPE_ACCELEROMETER),
            "Gyroscope" to has(Sensor.TYPE_GYROSCOPE),
            "Magnetometer" to has(Sensor.TYPE_MAGNETIC_FIELD),
            "Barometer" to has(Sensor.TYPE_PRESSURE),
            "Light" to has(Sensor.TYPE_LIGHT),
        )
    }
}
