package dev.ampere.battery

import dev.ampere.battery.core.Countries
import dev.ampere.battery.core.Fmt
import dev.ampere.battery.data.Day
import dev.ampere.battery.data.Estimate
import dev.ampere.battery.data.GoalWin
import dev.ampere.battery.data.KIND_CHARGE
import dev.ampere.battery.data.Session
import dev.ampere.battery.data.Settings
import dev.ampere.battery.domain.Achievements
import dev.ampere.battery.domain.Bracket
import dev.ampere.battery.domain.Brackets
import dev.ampere.battery.domain.Capacity
import dev.ampere.battery.domain.ChargeModel
import dev.ampere.battery.domain.Confidence
import dev.ampere.battery.domain.CurrentCalibrator
import dev.ampere.battery.domain.Game
import dev.ampere.battery.domain.GoalState
import dev.ampere.battery.domain.Goals
import dev.ampere.battery.domain.Grader
import dev.ampere.battery.domain.HealthCalc
import dev.ampere.battery.domain.Lttb
import dev.ampere.battery.domain.Marks
import dev.ampere.battery.domain.Ranks
import dev.ampere.battery.domain.StatsCalc
import dev.ampere.battery.domain.TempState
import dev.ampere.battery.domain.Thresholds
import dev.ampere.battery.hw.DeviceDb
import dev.ampere.battery.hw.PhoneSpec
import dev.ampere.battery.hw.Units
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcTest {
    private fun feed(c: CurrentCalibrator, raw: Int, plugged: Boolean, n: Int = 40): Int? {
        var last: Int? = null
        repeat(n) { last = c.normalise(raw, plugged, plugged, 50) }
        return last
    }

    @Test fun calibrator_microamps_positive() = assertEquals(2_500_000, feed(CurrentCalibrator(), 2_500_000, true))

    @Test fun calibrator_microamps_inverted() {
        val c = CurrentCalibrator()
        assertEquals(2_500_000, feed(c, -2_500_000, true))
        assertEquals(-400_000, feed(c, 400_000, false))
    }

    @Test fun calibrator_milliamps() {
        val c = CurrentCalibrator()
        assertEquals(2_500_000, feed(c, 2_500, true))
        assertEquals(-350_000, feed(c, -350, false))
    }

    @Test fun calibrator_always_positive_phones_still_drain() {
        val c = CurrentCalibrator()
        assertEquals(-400_000, feed(c, 400_000, false))
        assertEquals(1_800_000, feed(c, 1_800_000, true))
    }

    @Test fun calibrator_idle_at_full_is_not_mistaken_for_milliamps() {
        val c = CurrentCalibrator()
        repeat(50) { assertNull(c.normalise(4_000, true, false, 100)) }
        assertEquals(0, c.scale)
    }

    @Test fun calibrator_recovers_from_wrong_unit() {
        val c = CurrentCalibrator()
        feed(c, 900, false)
        assertEquals(1000, c.scale)
        feed(c, 450_000, false)
        assertEquals(1, c.scale)
    }

    @Test fun power_does_not_overflow() = assertEquals(13_200, Units.powerMw(4_400, 3_000_000))

    @Test fun millivolt_units() {
        assertEquals(4312, Units.millivolts(4312))
        assertEquals(4312, Units.millivolts(4_312_000))
        assertEquals(8400, Units.millivolts(8400))
        assertEquals(4000, Units.millivolts(4))
        assertNull(Units.millivolts(17))
    }

    @Test fun capacity_valid_run() {
        val r = Capacity.fromCharge(20, 90, 800_000, 3_250_000, 2_440_000f, 1f, 280, 340, 310, 4_000_000)!!
        assertEquals(3_500_000, r.uah)
        assertEquals("charge counter", r.method)
    }

    @Test fun capacity_rejects_short_hot_and_garbage() {
        assertNull(Capacity.fromCharge(50, 60, 2_000_000, 2_400_000, 400_000f, 1f, 280, 320, 300, 4_000_000))
        assertNull(Capacity.fromCharge(20, 90, 800_000, 3_250_000, 2_440_000f, 1f, 280, 470, 380, 4_000_000))
        assertNull(Capacity.fromCharge(20, 90, 0, 8_000_000, 0f, 0f, 280, 340, 310, 4_000_000))
    }

    @Test fun capacity_spots_a_gauge_that_mirrors_design() {
        val r = Capacity.fromCharge(20, 80, 800_000, 3_200_000, 2_100_000f, 1f, 280, 340, 310, 4_000_000)!!
        assertEquals("coulomb counting", r.method)
        assertEquals(3_500_000, r.uah)
    }

    @Test fun capacity_rejects_an_unverifiable_perfect_gauge() {
        assertNull(Capacity.fromCharge(20, 80, 800_000, 3_200_000, 0f, 0f, 280, 340, 310, 4_000_000))
        assertNotNull(Capacity.fromCharge(20, 80, 800_000, 3_200_000, 2_390_000f, 1f, 280, 340, 310, 4_000_000))
        assertNotNull(Capacity.fromCharge(20, 80, 800_000, 3_000_000, 0f, 0f, 280, 340, 310, 4_000_000))
    }

    @Test fun weighted_median_ignores_one_outlier() {
        val v = Capacity.weightedMedian(listOf(3_500_000 to 1f, 3_520_000 to 1f, 3_480_000 to 1f, 2_000_000 to 0.3f))
        assertEquals(3_500_000, v)
    }

    @Test fun health_confidence_ladder() {
        fun e(i: Int) = Estimate(ts = i.toLong(), uah = 3_600_000, weight = 1f, method = "charge counter", spanPct = 60, tempC = 30f, sessionId = 1)
        assertEquals(Confidence.NONE, HealthCalc.of(4000, emptyList(), null, 50, null).confidence)
        val q = HealthCalc.of(4000, emptyList(), 1_800_000, 50, null)
        assertEquals(Confidence.QUICK, q.confidence)
        assertEquals(90f, q.pct!!, 0.1f)
        assertEquals(Confidence.PROVISIONAL, HealthCalc.of(4000, listOf(e(1)), null, 50, null).confidence)
        assertEquals(Confidence.MEASURED, HealthCalc.of(4000, listOf(e(1), e(2), e(3)), null, 50, null).confidence)
        assertNull("no quick read at low percent", HealthCalc.of(4000, emptyList(), 400_000, 10, null).pct)
        assertNull("no percentage without a design figure", HealthCalc.of(null, listOf(e(1)), null, 50, null).pct)
    }

    @Test fun health_trend_projects_eighty() {
        val day = 86_400_000L
        val est = (0..12).map { Estimate(ts = it * 10 * day, uah = 3_800_000 - it * 10_000, weight = 1f, method = "charge counter", spanPct = 60, tempC = 30f, sessionId = 1) }
        val h = HealthCalc.of(4000, est, null, null, null)
        assertEquals(-0.75f, h.trendPerMonth!!, 0.01f)
        assertNotNull(h.eightyAt)
    }

    @Test fun eta_tapers_near_the_top() {
        val full = 4_000_000
        val naive = (3600f * 5 / 100 * full / 2_000_000 * 1000).toLong()
        val late = ChargeModel.eta(80, 100, 2_000_000, full, null)!!
        val naiveLate = (3600f * 20 / 100 * full / 2_000_000 * 1000).toLong()
        assertTrue(late > naiveLate * 1.5)
        val cc = ChargeModel.eta(20, 25, 2_000_000, full, null)!!
        assertTrue(abs(cc - naive) < naive * 0.05)
        assertNull(ChargeModel.eta(50, 100, 5_000, full, null))
        assertEquals(0L, ChargeModel.eta(100, 100, 2_000_000, full, null))
    }

    @Test fun eta_follows_learned_profile() {
        val learned = FloatArray(100) { 60f }
        // 4000 mAh at 2.4 A is 60 s per percent, exactly as learned.
        assertEquals(50 * 60_000L, ChargeModel.eta(50, 100, 2_400_000, 4_000_000, learned)!!, 2_000)
        // Twice the current halves it.
        assertEquals(25 * 60_000L, ChargeModel.eta(50, 100, 4_800_000, 4_000_000, learned)!!, 2_000)
    }

    private fun assertEquals(e: Long, a: Long, tol: Long) = assertTrue("$e vs $a", abs(e - a) <= tol)

    @Test fun learn_uses_whole_percent_steps_only() {
        val m = IntArray(101) { -1 }
        for (l in 20..80) m[l] = (l - 20) * 50
        val s = charge(1, 20, 80, m)
        val lrn = ChargeModel.learn(listOf(s), 1)!!
        assertEquals(0f, lrn[20], 0f) // first percent is partial
        assertEquals(50f, lrn[40], 0.01f)
        assertNull(ChargeModel.learn(listOf(s), 4))
    }

    private fun charge(id: Long, a: Int, b: Int, m: IntArray, score: Int = 95) = Session(
        id = id, kind = KIND_CHARGE, startTs = id * 100_000_000, endTs = id * 100_000_000 + 3_600_000, startLevel = a, endLevel = b, plug = 1,
        durationMs = 3_600_000, marks = Marks.encode(m), score = score, open = false, peakMw = 20_000,
    )

    private fun linear(a: Int, b: Int, sec: Int) = IntArray(101) { if (it in a..b) (it - a) * sec else -1 }

    @Test fun marks_round_trip_and_bracket_times() {
        val m = linear(0, 100, 36)
        assertTrue(m.contentEquals(Marks.decode(Marks.encode(m))))
        assertEquals(3600, Marks.time(m, 0, 100))
        assertEquals(2160, Marks.time(m, 20, 80))
        assertNull(Marks.time(linear(30, 60, 30), 20, 60))
    }

    @Test fun records_ignore_jumps_and_impossible_speed() {
        val jump = IntArray(101) { if (it in 40..50) (it - 40) * 30 else if (it == 100) 333 else -1 }
        assertNull(Marks.time(jump, 50, 100))
        assertEquals(300, Marks.time(jump, 40, 50))
        assertNull(Marks.time(linear(0, 100, 1), 0, 100))
        val oneSkipped = linear(20, 80, 30).also { it[50] = -1 }
        assertEquals(1800, Marks.time(oneSkipped, 20, 80))
        assertNull(StatsCalc.of(listOf(charge(1, 40, 100, jump)), emptyList()).bracket(Bracket(50, 100)).bestSec)
    }

    @Test fun brackets_count_and_best() {
        val list = listOf(charge(1, 0, 100, linear(0, 100, 40)), charge(2, 10, 100, linear(10, 100, 30)), charge(3, 50, 80, linear(50, 80, 30)))
        val st = StatsCalc.of(list, emptyList())
        val full = st.bracket(Bracket(0, 100))
        assertEquals(1, full.count)
        assertEquals(4000, full.bestSec)
        val mid = st.bracket(Bracket(20, 80))
        assertEquals(2, mid.count)
        assertEquals(1800, mid.bestSec)
        assertEquals(2L, mid.bestSession)
        assertEquals(2.2f, st.cycles, 0.001f)
        assertEquals(1, st.zeroTo100)
        assertEquals(8, Brackets.standard.size)
    }

    @Test fun grades_at_boundaries() {
        assertEquals(listOf("A", "B", "B", "C", "C", "D", "D", "F"), listOf(90, 89, 78, 77, 65, 64, 50, 49).map { Grader.letter(it) })
        val clean = charge(1, 30, 80, linear(30, 80, 30)).copy(peakTempT = 350)
        assertEquals(100, Grader.grade(clean).first)
        val hot = clean.copy(peakTempT = 450, heat43Ms = 10 * 60_000, thermalMax = 3)
        val (score, ded) = Grader.grade(hot)
        assertEquals(100 - 20 - 15 - 15, score)
        assertEquals(ded, Grader.decode(Grader.encode(ded)))
    }

    @Test fun temperature_bands() {
        assertEquals(
            listOf(TempState.COLD, TempState.CHILLY, TempState.NORMAL, TempState.NORMAL, TempState.WARM, TempState.HOT, TempState.VERY_HOT, TempState.CRITICAL),
            listOf(-1f, 5f, 10f, 34.9f, 35f, 40f, 45f, 45.1f).map { Thresholds.temp(it) },
        )
    }

    @Test fun lttb_keeps_extremes_and_ends() {
        val n = 100_000
        val x = LongArray(n) { it.toLong() }
        val y = FloatArray(n) { if (it == 43_210) 99f else if (it == 77_000) -99f else (it % 17) / 17f }
        val k = Lttb.pick(x, y, 400)
        assertEquals(400, k.size)
        assertEquals(0, k.first())
        assertEquals(n - 1, k.last())
        assertTrue(k.any { it == 43_210 } && k.any { it == 77_000 })
        assertTrue(k.toList().zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun ranks_are_ordered_and_progress_is_bounded() {
        assertEquals(18, Ranks.all.size)
        assertTrue(Ranks.all.zipWithNext().all { (a, b) -> b.at > a.at })
        assertEquals("Copper I", Ranks.of(0).name)
        assertEquals("Copper II", Ranks.of(150).name)
        assertEquals("Grid Master", Ranks.of(1_000_000).name)
        assertEquals(1f, Ranks.progress(1_000_000), 0f)
    }

    @Test fun points_need_a_real_charge() {
        assertEquals(0, Game.sessionVp(100, 4))
        assertEquals(75, Game.sessionVp(95, 50))
        assertEquals(25, Game.sessionVp(20, 50))
    }

    @Test fun achievements_are_unique_and_start_locked() {
        assertEquals(Achievements.all.size, Achievements.byId.size)
        val ctx = dev.ampere.battery.domain.Achievement.Ctx(StatsCalc.of(emptyList(), emptyList()), HealthCalc.of(null, emptyList(), null, null, null), emptyList(), 0, 0, false)
        Achievements.all.forEach { assertTrue(it.id, it.value(ctx) < it.target) }
    }

    @Test fun goals_and_streaks() {
        val s = Settings()
        val good = Day(10, chargedPct = 60, charges = 1, minLevel = 30, maxTempT = 350, screenOnMs = 3_600_000, trackedMs = 10 * 3_600_000L, maxUnplug = 80)
        assertEquals(5, Goals.wins(good, s).size)
        assertTrue(Goals.wins(good.copy(trackedMs = 1000), s).isEmpty())
        assertEquals(GoalState.MISSED, Goals.eval(good.copy(maxTempT = 400), s).first { it.id == "cool" }.state)
        assertEquals(GoalState.OFF, Goals.eval(good, s.copy(goalCool = 0)).first { it.id == "cool" }.state)
        val wins = listOf(1L, 2L, 3L, 7L, 8L).flatMap { d -> listOf("a", "b", "c").map { GoalWin(d, it) } }
        assertEquals(3 to 2, Goals.streaks(wins, 9))
        assertEquals(3 to 0, Goals.streaks(wins, 20))
    }

    @Test fun device_matching_respects_brand() {
        fun p(brand: String, name: String) = PhoneSpec(DeviceDb.norm(name), brand, "XX", name, 5000, 50f, 0f, 2024)
        val specs = listOf(p("OnePlus", "OnePlus 12"), p("Xiaomi", "Xiaomi 12"), p("Samsung", "Galaxy S24"), p("Samsung", "Galaxy S24+"))
        assertEquals("Xiaomi 12", DeviceDb.matchSpec(specs, "Xiaomi 12", "2201123G", setOf("xiaomi"))?.name)
        assertEquals("OnePlus 12", DeviceDb.matchSpec(specs, "OnePlus 12", "CPH2581", setOf("oneplus"))?.name)
        assertEquals("Galaxy S24", DeviceDb.matchSpec(specs, "Galaxy S24", "SM-S921B", setOf("samsung"))?.name)
        assertEquals("Galaxy S24+", DeviceDb.matchSpec(specs, "Galaxy S24+", "SM-S926B", setOf("samsung"))?.name)
        assertNull(DeviceDb.matchSpec(specs, "12", "X12", setOf("blackview")))
    }

    @Test fun bundled_tables_recognise_the_s24_family() {
        val dir = File("src/main/assets")
        val specs = File(dir, "specs.tsv").readLines().map { it.split('\t') }.map { PhoneSpec(it[0], it[1], it[2], it[3], it[4].toInt(), it[5].toFloat(), it[6].toFloat(), it[7].toInt()) }
        assertTrue(specs.all { it.key == DeviceDb.norm(it.name) && it.mah in 2000..8000 && it.wiredW in 10f..240f && it.country.length == 2 })
        assertEquals(specs.size, specs.map { it.brand + it.key }.toSet().size)
        val models = File(dir, "models.tsv").readLines().associate { l -> l.split('\t').let { it[0] to (it[1] to it[2]) } }
        listOf("SM-S921B" to 4000, "SM-S921U" to 4000, "SM-S928B" to 5000, "SM-S926B" to 4900, "CPH2581" to 5400).forEach { (m, mah) ->
            val (brand, name) = models.getValue(m)
            assertEquals(m, mah, DeviceDb.matchSpec(specs, name, m, setOf(brand.lowercase()))?.mah)
        }
        assertEquals(5050, DeviceDb.matchSpec(specs, "Pixel 8 Pro", "Pixel 8 Pro", setOf("google"))?.mah)
    }

    @Test fun formatting() {
        assertEquals("1h 24m", Fmt.duration(84 * 60_000L))
        assertEquals("—", Fmt.duration(null))
        assertEquals("1:00:05", Fmt.split(3605))
        assertEquals("12:08", Fmt.split(728))
        assertEquals("95°F", Fmt.temp(35f, true, 0))
        assertEquals("🇮🇳", Countries.flag("IN"))
        assertTrue(Countries.all.size > 200)
        assertFalse(Countries.all.any { it.name.isBlank() })
    }
}
