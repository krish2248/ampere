package dev.ampere.battery.domain

import dev.ampere.battery.core.Fmt
import dev.ampere.battery.data.Day
import dev.ampere.battery.data.GoalWin
import dev.ampere.battery.data.Settings
import kotlin.math.min

/** Eighteen tiers. [family] drives the badge metal, [step] the number of chevrons. */
data class Rank(val index: Int, val name: String, val family: Int, val step: Int, val at: Int)

object Ranks {
    private val names = listOf(
        Triple("Copper I", 0, 1), Triple("Copper II", 0, 2), Triple("Copper III", 0, 3), Triple("Copper IV", 0, 4),
        Triple("Silver I", 1, 1), Triple("Silver II", 1, 2), Triple("Silver III", 1, 3), Triple("Silver IV", 1, 4),
        Triple("Gold I", 2, 1), Triple("Gold II", 2, 2), Triple("Gold III", 2, 3),
        Triple("Cobalt I", 3, 1), Triple("Cobalt II", 3, 2), Triple("Cobalt III", 3, 3),
        Triple("Lithium I", 4, 1), Triple("Lithium II", 4, 2), Triple("Lithium Prime", 4, 3),
        Triple("Grid Master", 5, 4),
    )
    private val at = intArrayOf(0, 150, 350, 600, 950, 1400, 2000, 2700, 3500, 4500, 5700, 7100, 8800, 10800, 13200, 16000, 19500, 24000)
    val all: List<Rank> = names.mapIndexed { i, (n, f, s) -> Rank(i, n, f, s, at[i]) }

    fun of(vp: Int): Rank = all.last { vp >= it.at }

    fun next(r: Rank): Rank? = all.getOrNull(r.index + 1)

    fun progress(vp: Int): Float {
        val r = of(vp)
        val n = next(r) ?: return 1f
        return ((vp - r.at).toFloat() / (n.at - r.at)).coerceIn(0f, 1f)
    }
}

enum class Group(val label: String) { CHARGING("Charging"), SPEED("Speed"), CARE("Battery care"), ENDURANCE("Endurance"), NERD("Nerd") }

class Achievement(
    val id: String,
    val group: Group,
    val title: String,
    val text: String,
    val vp: Int,
    val target: Float,
    val unit: (Float) -> String = { it.toInt().toString() },
    val value: (Ctx) -> Float,
) {
    data class Ctx(val s: Stats, val h: Health, val wins: List<GoalWin>, val streak: Int, val samples: Int, val exported: Boolean)
}

object Achievements {
    private val h1 = { v: Float -> Fmt.dec(v, 1) + " h" }
    private val cyc = { v: Float -> Fmt.dec(v, 1) }
    private val w = { v: Float -> Fmt.dec(v, 1) + " W" }
    private fun best(s: Stats, a: Int, b: Int) = s.bracket(Bracket(a, b)).bestSec
    /** Speed goals count up to 1 when beaten, so progress is target ÷ best. */
    private fun under(s: Stats, a: Int, b: Int, min: Int): Float = best(s, a, b)?.let { min(1f, min * 60f / it) } ?: 0f
    private val pc = { v: Float -> "${(v * 100).toInt()}%" }

    val all: List<Achievement> = listOf(
        Achievement("c1", Group.CHARGING, "First spark", "Record your first charge", 20, 1f) { it.s.chargeCount.toFloat() },
        Achievement("c10", Group.CHARGING, "Plugged in", "Record 10 charges", 40, 10f) { it.s.chargeCount.toFloat() },
        Achievement("c50", Group.CHARGING, "Regular", "Record 50 charges", 80, 50f) { it.s.chargeCount.toFloat() },
        Achievement("c250", Group.CHARGING, "Socket scholar", "Record 250 charges", 200, 250f) { it.s.chargeCount.toFloat() },
        Achievement("c1000", Group.CHARGING, "Thousandfold", "Record 1,000 charges", 600, 1000f) { it.s.chargeCount.toFloat() },
        Achievement("y1", Group.CHARGING, "Full circle", "Complete one full cycle", 30, 1f, cyc) { it.s.cycles },
        Achievement("y10", Group.CHARGING, "Ten rounds", "Complete 10 cycles", 80, 10f, cyc) { it.s.cycles },
        Achievement("y50", Group.CHARGING, "Half century", "Complete 50 cycles", 200, 50f, cyc) { it.s.cycles },
        Achievement("y200", Group.CHARGING, "Long hauler", "Complete 200 cycles", 500, 200f, cyc) { it.s.cycles },
        Achievement("full", Group.CHARGING, "Back from the brink", "Charge from 2% or less to 100%", 60, 1f) { it.s.zeroTo100.toFloat() },
        Achievement("full10", Group.CHARGING, "Phoenix", "Do that 10 times", 200, 10f) { it.s.zeroTo100.toFloat() },
        Achievement("kwh", Group.CHARGING, "Kilowatt club", "Put 1 kWh into the battery", 250, 1000f, { Fmt.dec(it, 0) + " Wh" }) { it.s.energyMwh / 1000f },
        Achievement("wl", Group.CHARGING, "Cutting the cord", "Charge wirelessly", 30, 1f) { it.s.wireless.toFloat() },

        Achievement("w10", Group.SPEED, "Quick", "Hit 10 W at the cell", 20, 10f, w) { it.s.peakMw / 1000f },
        Achievement("w20", Group.SPEED, "Fast lane", "Hit 20 W at the cell", 50, 20f, w) { it.s.peakMw / 1000f },
        Achievement("w40", Group.SPEED, "High voltage", "Hit 40 W at the cell", 120, 40f, w) { it.s.peakMw / 1000f },
        Achievement("w80", Group.SPEED, "Lightning rod", "Hit 80 W at the cell", 250, 80f, w) { it.s.peakMw / 1000f },
        Achievement("s2080a", Group.SPEED, "Coffee break", "20 to 80% in under 60 minutes", 60, 1f, pc) { under(it.s, 20, 80, 60) },
        Achievement("s2080b", Group.SPEED, "Espresso", "20 to 80% in under 35 minutes", 150, 1f, pc) { under(it.s, 20, 80, 35) },
        Achievement("s2080c", Group.SPEED, "Blink", "20 to 80% in under 20 minutes", 300, 1f, pc) { under(it.s, 20, 80, 20) },
        Achievement("s40a", Group.SPEED, "Closer", "40 to 100% in under 70 minutes", 80, 1f, pc) { under(it.s, 40, 100, 70) },
        Achievement("s0100", Group.SPEED, "Sprinter", "0 to 100% in under 90 minutes", 150, 1f, pc) { under(it.s, 0, 100, 90) },
        Achievement("s0100b", Group.SPEED, "Full throttle", "0 to 100% in under 50 minutes", 350, 1f, pc) { under(it.s, 0, 100, 50) },
        Achievement("ppm", Group.SPEED, "Two a minute", "Reach 2% per minute", 150, 2f, { Fmt.dec(it, 2) + " %/min" }) { it.s.bestPctPerMin ?: 0f },

        Achievement("g10", Group.CARE, "Goldilocks", "10 charges kept within 20 to 80%", 80, 10f) { it.s.gentle.toFloat() },
        Achievement("g50", Group.CARE, "Cell whisperer", "50 charges kept within 20 to 80%", 250, 50f) { it.s.gentle.toFloat() },
        Achievement("a1", Group.CARE, "Straight A", "Get an A grade", 20, 1f) { (it.s.grades["A"] ?: 0).toFloat() },
        Achievement("a10", Group.CARE, "Honour roll", "Ten A grades in a row", 150, 10f) { it.s.aStreak.toFloat() },
        Achievement("a25", Group.CARE, "Valedictorian", "25 A grades in a row", 350, 25f) { it.s.aStreak.toFloat() },
        Achievement("cool7", Group.CARE, "Cold front", "7 days in a row under 40°C", 100, 7f) { it.s.noHeatStreak.toFloat() },
        Achievement("cool30", Group.CARE, "Ice age", "30 days in a row under 40°C", 400, 30f) { it.s.noHeatStreak.toFloat() },
        Achievement("gw1", Group.CARE, "Goal getter", "Meet your first daily goal", 20, 1f) { it.wins.size.toFloat() },
        Achievement("gw50", Group.CARE, "Disciplined", "Meet 50 daily goals", 150, 50f) { it.wins.size.toFloat() },
        Achievement("st7", Group.CARE, "Week one", "7-day goal streak", 150, 7f) { it.streak.toFloat() },
        Achievement("st30", Group.CARE, "Iron habit", "30-day goal streak", 500, 30f) { it.streak.toFloat() },

        Achievement("sot4", Group.ENDURANCE, "Day tripper", "4 h screen time on one charge", 40, 4f, h1) { it.s.bestScreenOnMs / 3.6e6f },
        Achievement("sot6", Group.ENDURANCE, "Marathon", "6 h screen time on one charge", 100, 6f, h1) { it.s.bestScreenOnMs / 3.6e6f },
        Achievement("sot9", Group.ENDURANCE, "Ultra", "9 h screen time on one charge", 250, 9f, h1) { it.s.bestScreenOnMs / 3.6e6f },
        Achievement("d24", Group.ENDURANCE, "Full day", "24 h between charges", 60, 24f, h1) { it.s.longestDrainMs / 3.6e6f },
        Achievement("d48", Group.ENDURANCE, "Weekender", "48 h between charges", 200, 48f, h1) { it.s.longestDrainMs / 3.6e6f },

        Achievement("n1", Group.NERD, "Calibrated", "Get a measured capacity", 80, 3f) { it.h.count.toFloat() },
        Achievement("n20", Group.NERD, "Statistician", "Collect 20 capacity estimates", 200, 20f) { it.h.count.toFloat() },
        Achievement("smp", Group.NERD, "Data hoarder", "Store 50,000 samples", 150, 50_000f, { Fmt.int(it.toInt()) }) { it.samples.toFloat() },
        Achievement("pdf", Group.NERD, "Peer review", "Export a PDF report", 40, 1f) { if (it.exported) 1f else 0f },
        Achievement("t30", Group.NERD, "Longitudinal", "Track 30 days", 120, 30f) { it.s.daysTracked.toFloat() },
        Achievement("t365", Group.NERD, "One orbit", "Track 365 days", 600, 365f) { it.s.daysTracked.toFloat() },
    )
    val byId = all.associateBy { it.id }
}

enum class GoalState { ON_TRACK, MISSED, OFF }

data class Goal(val id: String, val title: String, val value: String, val target: String, val progress: Float, val state: GoalState)

object Goals {
    /** A day counts once Ampere has watched at least four hours of it. */
    const val MIN_TRACKED = 4 * 3_600_000L

    fun eval(d: Day?, s: Settings): List<Goal> {
        val x = d ?: Day(0)
        val has = d != null && d.maxTempT != Int.MIN_VALUE
        fun g(id: String, title: String, on: Boolean, value: String, target: String, p: Float, ok: Boolean) =
            Goal(id, title, if (has) value else Fmt.NONE, target, if (has) p.coerceIn(0f, 1f) else 0f,
                if (!on) GoalState.OFF else if (ok || !has) GoalState.ON_TRACK else GoalState.MISSED)
        return listOf(
            g("cool", "Stay cool", s.goalCool > 0, Fmt.temp(x.maxTempT / 10f, s.fahrenheit), "under ${Fmt.temp(s.goalCool.toFloat(), s.fahrenheit, 0)}",
                (x.maxTempT / 10f - 20) / (s.goalCool - 20).coerceAtLeast(1), x.maxTempT <= s.goalCool * 10),
            g("ceiling", "Unplug early", s.goalCeiling > 0, if (x.charges == 0) "no charge yet" else "${x.maxUnplug}%", "by ${s.goalCeiling}%",
                x.maxUnplug / s.goalCeiling.coerceAtLeast(1).toFloat(), x.maxUnplug <= s.goalCeiling),
            g("floor", "Stay above", s.goalFloor > 0, "low ${x.minLevel}%", "${s.goalFloor}%",
                (100 - x.minLevel) / (100f - s.goalFloor), x.minLevel >= s.goalFloor),
            g("screen", "Screen time", s.goalScreenH > 0, Fmt.duration(x.screenOnMs), "under ${s.goalScreenH} h",
                x.screenOnMs / (s.goalScreenH.coerceAtLeast(1) * 3.6e6f), x.screenOnMs <= s.goalScreenH * 3_600_000L),
            g("cycles", "Charge used", s.goalCycles > 0, "${x.chargedPct}%", "under ${s.goalCycles}%",
                x.chargedPct / s.goalCycles.coerceAtLeast(1).toFloat(), x.chargedPct <= s.goalCycles),
        )
    }

    fun wins(d: Day, s: Settings): List<GoalWin> =
        if (d.trackedMs < MIN_TRACKED) emptyList()
        else eval(d, s).filter { it.state == GoalState.ON_TRACK }.map { GoalWin(d.day, it.id) }

    /** Best run and current run of days with at least three goals met. */
    fun streaks(wins: List<GoalWin>, today: Long): Pair<Int, Int> {
        val good = wins.groupingBy { it.day }.eachCount().filterValues { it >= 3 }.keys.sorted()
        var best = 0
        var c = 0
        var prev = Long.MIN_VALUE
        for (d in good) {
            c = if (d == prev + 1) c + 1 else 1
            prev = d
            best = maxOf(best, c)
        }
        return best to if (good.isNotEmpty() && good.last() >= today - 1) c else 0
    }
}

data class GameState(
    val vp: Int,
    val rank: Rank,
    val fromCharges: Int,
    val fromAchievements: Int,
    val fromGoals: Int,
    val unlocked: Map<String, Long>,
    val values: Map<String, Float>,
    val bestStreak: Int,
    val streak: Int,
)

object Game {
    fun sessionVp(score: Int, gained: Int): Int {
        if (gained < 5) return 0
        val k = when (Grader.letter(score)) {
            "A" -> 1.5f
            "B" -> 1.25f
            "C" -> 1f
            "D" -> 0.75f
            else -> 0.5f
        }
        return (gained * k).toInt()
    }

    fun state(ctx: Achievement.Ctx, unlocked: Map<String, Long>, streak: Int): GameState {
        val c = ctx.s.charges.sumOf { sessionVp(it.score, it.gained) }
        val a = unlocked.keys.sumOf { Achievements.byId[it]?.vp ?: 0 }
        val g = ctx.wins.size * 10
        val vp = c + a + g
        return GameState(vp, Ranks.of(vp), c, a, g, unlocked, Achievements.all.associate { it.id to it.value(ctx) }, ctx.streak, streak)
    }
}
