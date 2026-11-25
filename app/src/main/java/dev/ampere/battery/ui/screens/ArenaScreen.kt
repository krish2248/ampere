package dev.ampere.battery.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ampere.battery.Container
import dev.ampere.battery.Derived
import dev.ampere.battery.core.Countries
import dev.ampere.battery.core.Fmt
import dev.ampere.battery.data.Board
import dev.ampere.battery.data.BoardMetric
import dev.ampere.battery.data.BoardRow
import dev.ampere.battery.data.Prefs
import dev.ampere.battery.data.Settings
import dev.ampere.battery.domain.Achievement
import dev.ampere.battery.domain.Achievements
import dev.ampere.battery.domain.Bracket
import dev.ampere.battery.domain.Brackets
import dev.ampere.battery.domain.Goal
import dev.ampere.battery.domain.GoalState
import dev.ampere.battery.domain.Goals
import dev.ampere.battery.domain.Group
import dev.ampere.battery.domain.Ranks
import dev.ampere.battery.export.Share
import dev.ampere.battery.hw.PhoneSpec
import dev.ampere.battery.ui.Nav
import dev.ampere.battery.ui.SubPage
import dev.ampere.battery.ui.TabPage
import dev.ampere.battery.ui.component.AmpButton
import dev.ampere.battery.ui.component.Bar
import dev.ampere.battery.ui.component.Empty
import dev.ampere.battery.ui.component.Grid
import dev.ampere.battery.ui.component.Hairline
import dev.ampere.battery.ui.component.IconBtn
import dev.ampere.battery.ui.component.KV
import dev.ampere.battery.ui.component.MiniRing
import dev.ampere.battery.ui.component.NavRow
import dev.ampere.battery.ui.component.Panel
import dev.ampere.battery.ui.component.RankBadge
import dev.ampere.battery.ui.component.Rolling
import dev.ampere.battery.ui.component.Ruler
import dev.ampere.battery.ui.component.Section
import dev.ampere.battery.ui.component.Segmented
import dev.ampere.battery.ui.component.StatTile
import dev.ampere.battery.ui.component.T
import dev.ampere.battery.ui.component.ToggleRow
import dev.ampere.battery.ui.component.metal
import dev.ampere.battery.ui.component.rememberReveal
import dev.ampere.battery.ui.theme.Amp
import dev.ampere.battery.ui.theme.Motion
import dev.ampere.battery.ui.theme.Type
import java.util.UUID
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

fun displayNick(s: Settings) = s.nick.ifBlank { "Player" }

@Composable
fun ArenaScreen(c: Container, s: Settings, nav: Nav) {
    val d by c.derived.collectAsStateWithLifecycle()
    val l by c.engine.live.collectAsStateWithLifecycle()
    val dev by c.device.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = d.stats.days.lastOrNull()?.takeIf { it.day == Fmt.epochDay(l.ts.coerceAtLeast(1)) }
    TabPage("Arena", nav, actions = {
        IconBtn(Icons.Rounded.IosShare, "Share my card", tint = Amp.c.dim) { scope.launch { Share.card(ctx, c, s, d, dev) } }
    }) {
        item("me") { Me(s, d, dev?.name, nav) }
        item("goals") {
            Section("Today's goals") {
                if (d.game.streak > 0) {
                    Icon(Icons.Rounded.LocalFireDepartment, null, Modifier.size(18.dp), tint = Amp.c.heat)
                    T(" ${d.game.streak} day streak", Type.small, Amp.c.dim)
                }
            }
        }
        item("goalsp") {
            Panel(onClick = { nav.go("goals") }) {
                Goals.eval(today, s).filter { it.state != GoalState.OFF }.forEach { GoalRow(it) }
                Spacer(Modifier.height(4.dp))
                T("Each goal you finish the day with is worth 10 VP. Tap to change targets.", Type.small, Amp.c.faint)
            }
        }
        item("rech") { Section("Records") }
        item("rec") { Records(d, nav) }
        item("cust") { CustomBracket(d, nav) }
        item("ctr") {
            Grid(2) {
                StatTile("0 to 100%", "${d.stats.zeroTo100}×", "full charges from empty")
                StatTile("Reached 100%", "${d.stats.toFull}×")
                StatTile("Biggest single charge", "+${d.stats.biggestGain}%")
                StatTile("Fastest ten percent", d.stats.bestPctPerMin?.let { "${Fmt.dec(it, 2)} %/min" } ?: Fmt.NONE)
                StatTile("Peak power", if (d.stats.peakMw > 0) "${Fmt.watts(d.stats.peakMw)} W" else Fmt.NONE, if (d.stats.peakMwTs > 0) Fmt.date(d.stats.peakMwTs) else null)
                StatTile("Best A streak", "${d.stats.aStreak}", "charges in a row")
            }
        }
        item("achh") {
            Section("Achievements") {
                T("${d.game.unlocked.size} of ${Achievements.all.size}", Type.small, Amp.c.dim)
            }
        }
        item("ach") { AchievementPreview(d, nav) }
        item("links") {
            Panel(pad = 20.dp) {
                NavRow("World ranking", "Where your phone stands", Icons.Rounded.Public) { nav.go("world") }
                Hairline()
                NavRow("All ranks", "18 tiers, Copper to Grid Master", Icons.Rounded.MilitaryTech) { nav.go("ladder") }
                Hairline()
                NavRow("Profile", "Name and country", Icons.Rounded.Flag) { nav.go("profile") }
            }
        }
    }
}

@Composable
private fun Me(s: Settings, d: Derived, device: String?, nav: Nav) {
    val r = d.game.rank
    val next = Ranks.next(r)
    val rev by rememberReveal()
    val p by animateFloatAsState(Ranks.progress(d.game.vp), Motion.settle(), label = "vp")
    Panel(onClick = { nav.go("ladder") }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RankBadge(r.family, r.step, Modifier.width(84.dp), shine = true)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.country.isNotEmpty()) T(Countries.flag(s.country) + " ", Type.section.copy(fontSize = 20.sp))
                    T(displayNick(s), Type.section, maxLines = 1)
                }
                T(r.name, Type.title, if (Amp.c.dark) metal(r.family).first else metal(r.family).second, maxLines = 1)
                Row {
                    Rolling(d.game.vp.toFloat(), { Fmt.int(it.roundToInt()) }, Type.body)
                    T(" VP", Type.body, Amp.c.dim)
                    if (device != null) T(", $device", Type.body, Amp.c.dim, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Bar({ p * rev }, if (Amp.c.dark) metal(r.family).first else metal(r.family).second, height = 8.dp)
        Spacer(Modifier.height(8.dp))
        T(if (next != null) "${Fmt.int(next.at - d.game.vp)} VP to ${next.name}" else "Top of the ladder", Type.small, Amp.c.dim)
    }
}

@Composable
private fun GoalRow(g: Goal) {
    val col = if (g.state == GoalState.MISSED) Amp.c.bad else Amp.c.good
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
        MiniRing(if (g.state == GoalState.MISSED) 1f else g.progress, col, Modifier.size(34.dp)) {
            Icon(if (g.state == GoalState.MISSED) Icons.Rounded.Close else Icons.Rounded.Check, if (g.state == GoalState.MISSED) "Missed" else "On track", Modifier.size(15.dp), tint = col)
        }
        Column(Modifier.weight(1f)) {
            T(g.title, Type.body)
            T(g.target, Type.small, Amp.c.dim)
        }
        T(g.value, Type.strong)
    }
}

@Composable
private fun Records(d: Derived, nav: Nav) {
    Panel {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            T("Charge", Type.small, Amp.c.faint, Modifier.weight(1.2f))
            T("Times", Type.small, Amp.c.faint, Modifier.weight(0.7f), align = TextAlign.End)
            T("Average", Type.small, Amp.c.faint, Modifier.weight(1f), align = TextAlign.End)
            T("Best", Type.small, Amp.c.faint, Modifier.weight(1f), align = TextAlign.End)
        }
        val rows = d.stats.brackets.ifEmpty { Brackets.standard.map { dev.ampere.battery.domain.BracketStat(it, 0, null, null, null, null) } }
        rows.forEach { b ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(b.bestSession != null) { nav.go("session/${b.bestSession}") }.padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T(b.bracket.label, Type.mono, modifier = Modifier.weight(1.2f))
                T("${b.count}×", Type.mono, if (b.count > 0) Amp.c.dim else Amp.c.faint, Modifier.weight(0.7f), align = TextAlign.End)
                T(b.avgSec?.let { Fmt.split(it) } ?: Fmt.NONE, Type.mono, Amp.c.dim, Modifier.weight(1f), align = TextAlign.End)
                T(b.bestSec?.let { Fmt.split(it) } ?: Fmt.NONE, Type.mono, if (b.bestSec != null) Amp.c.power else Amp.c.faint, Modifier.weight(1f), align = TextAlign.End)
            }
        }
    }
}

@Composable
private fun CustomBracket(d: Derived, nav: Nav) {
    var from by rememberSaveable { mutableIntStateOf(30) }
    var to by rememberSaveable { mutableIntStateOf(90) }
    val st = remember(from, to, d.stats.chargeCount) { d.stats.bracket(Bracket(from, to)) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Any stretch", Type.section, modifier = Modifier.weight(1f))
            T("$from → $to", Type.mono, Amp.c.dim)
        }
        Ruler(from, 0..100, 5, Amp.c.dim, label = "From percent") {
            from = min(it, 95)
            to = max(to, from + 5)
        }
        Ruler(to, 0..100, 5, Amp.c.charge, label = "To percent") {
            to = max(it, 5)
            from = min(from, to - 5)
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Times" to "${st.count}×", "Average" to (st.avgSec?.let { Fmt.split(it) } ?: Fmt.NONE), "Best" to (st.bestSec?.let { Fmt.split(it) } ?: Fmt.NONE)).forEach { (k, v) ->
                Column(Modifier.weight(1f)) {
                    T(k, Type.small, Amp.c.dim)
                    T(v, Type.figure.copy(fontSize = 21.sp))
                }
            }
        }
    }
}

private fun progressOf(a: Achievement, d: Derived): Float = if (a.id in d.game.unlocked) 1f else ((d.game.values[a.id] ?: 0f) / a.target).coerceIn(0f, 1f)

@Composable
private fun AchievementPreview(d: Derived, nav: Nav) {
    val closest = remember(d.game) { Achievements.all.filter { it.id !in d.game.unlocked }.sortedByDescending { progressOf(it, d) }.take(3) }
    Panel(onClick = { nav.go("achievements") }) {
        T("Closest to unlocking", Type.small, Amp.c.dim)
        Spacer(Modifier.height(4.dp))
        closest.forEach { AchRow(it, d) }
        T("See all", Type.strong, Amp.c.dim, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun AchRow(a: Achievement, d: Derived) {
    val ts = d.game.unlocked[a.id]
    val done = ts != null
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
        MiniRing(progressOf(a, d), if (done) Amp.c.power else Amp.c.charge, Modifier.size(42.dp)) {
            if (done) Icon(Icons.Rounded.EmojiEvents, "Unlocked", Modifier.size(20.dp), tint = Amp.c.power)
            else T("${(progressOf(a, d) * 100).toInt()}", Type.tiny, Amp.c.dim)
        }
        Column(Modifier.weight(1f)) {
            T(a.title, Type.strong, if (done) Amp.c.text else Amp.c.dim)
            T(a.text, Type.small, Amp.c.dim)
            if (done) T(Fmt.date(ts!!), Type.small, Amp.c.faint)
            else if (a.target > 1) T("${a.unit(d.game.values[a.id] ?: 0f)} of ${a.unit(a.target)}", Type.small, Amp.c.faint)
        }
        T("+${a.vp}", Type.mono, if (done) Amp.c.power else Amp.c.faint)
    }
}

@Composable
fun AchievementsScreen(c: Container, nav: Nav) {
    val d by c.derived.collectAsStateWithLifecycle()
    SubPage("Achievements", nav) {
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("${d.game.unlocked.size}", Type.big)
                    T(" of ${Achievements.all.size}", Type.section, Amp.c.dim, Modifier.weight(1f))
                    T("${Fmt.int(d.game.fromAchievements)} VP", Type.strong, Amp.c.power)
                }
                Spacer(Modifier.height(10.dp))
                Bar({ d.game.unlocked.size / Achievements.all.size.toFloat() }, Amp.c.power)
            }
        }
        Group.entries.forEach { g ->
            item { Section(g.label) }
            item { Panel { Achievements.all.filter { it.group == g }.forEach { AchRow(it, d) } } }
        }
    }
}

@Composable
fun LadderScreen(c: Container, nav: Nav) {
    val d by c.derived.collectAsStateWithLifecycle()
    SubPage("Ranks", nav) {
        item {
            Panel {
                T("Where your ${Fmt.int(d.game.vp)} VP came from", Type.section)
                Spacer(Modifier.height(4.dp))
                KV("Charges", Fmt.int(d.game.fromCharges), sub = "1 VP per percent, times 1.5 for an A down to 0.5 for an F")
                KV("Achievements", Fmt.int(d.game.fromAchievements))
                KV("Daily goals", Fmt.int(d.game.fromGoals), sub = "10 VP each")
            }
        }
        item {
            Panel(pad = 10.dp) {
                Ranks.all.reversed().forEach { r ->
                    val mine = r.index == d.game.rank.index
                    val have = d.game.vp >= r.at
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (mine) Amp.c.raised else Amp.c.surface).padding(horizontal = 14.dp, vertical = 9.dp),
                        Arrangement.spacedBy(18.dp), Alignment.CenterVertically,
                    ) {
                        RankBadge(r.family, r.step, Modifier.width(38.dp), locked = !have, shine = mine)
                        T(r.name, if (mine) Type.strong else Type.body, if (have) Amp.c.text else Amp.c.faint, Modifier.weight(1f))
                        T(Fmt.int(r.at), Type.mono, if (have) Amp.c.dim else Amp.c.faint)
                    }
                }
            }
        }
    }
}

@Composable
fun GoalsScreen(c: Container, s: Settings, nav: Nav) {
    val d by c.derived.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun set(k: androidx.datastore.preferences.core.Preferences.Key<Int>, v: Int) {
        scope.launch { c.prefs.edit { it[k] = v } }
    }
    SubPage("Daily goals", nav) {
        item {
            Grid(2) {
                StatTile("Current streak", "${d.game.streak} days", "3 or more goals a day")
                StatTile("Best streak", "${d.game.bestStreak} days")
            }
        }
        item {
            Panel {
                GoalEdit("Stay cool", "Peak battery temperature", s.goalCool, 38, 33..45, 1, { Fmt.temp(it.toFloat(), s.fahrenheit, 0) }) { set(Prefs.K.goalCool, it) }
                Hairline()
                GoalEdit("Unplug early", "Highest level you charge to", s.goalCeiling, 85, 60..100, 5, { "$it%" }) { set(Prefs.K.goalCeiling, it) }
                Hairline()
                GoalEdit("Stay above", "Lowest level you let it reach", s.goalFloor, 20, 5..50, 5, { "$it%" }) { set(Prefs.K.goalFloor, it) }
                Hairline()
                GoalEdit("Screen time", "Hours of screen a day", s.goalScreenH, 6, 1..14, 1, { "$it h" }) { set(Prefs.K.goalScreenH, it) }
                Hairline()
                GoalEdit("Charge used", "Percent added per day", s.goalCycles, 100, 30..200, 10, { "$it%" }) { set(Prefs.K.goalCycles, it) }
            }
        }
        item { T("A day counts once Ampere has tracked at least four hours of it. Results are locked in at midnight.", Type.small, Amp.c.faint, Modifier.padding(horizontal = 6.dp)) }
    }
}

@Composable
private fun GoalEdit(title: String, sub: String, v: Int, def: Int, range: IntRange, step: Int, f: (Int) -> String, set: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        ToggleRow(title, if (v > 0) "$sub: ${f(v)}" else sub, v > 0) { set(if (it) def else 0) }
        if (v > 0) Ruler(v, range, step, Amp.c.good, label = title, onChange = set)
    }
}

// ---- world -----------------------------------------------------------------------------------------

private enum class League(val label: String, val unit: String, val f: (PhoneSpec) -> Float) {
    WIRED("Wired", "W", { it.wiredW }),
    WIRELESS("Wireless", "W", { it.wirelessW }),
    SIZE("Capacity", "mAh", { it.mah.toFloat() }),
    RATE("Speed", "C", { it.wiredW / (it.mah / 1000f * 3.87f) }),
}

@Composable
fun WorldScreen(c: Container, s: Settings, nav: Nav) {
    val d by c.derived.collectAsStateWithLifecycle()
    val dev by c.device.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var league by rememberSaveable { mutableIntStateOf(0) }
    val lg = League.entries[league]
    val mine = dev?.spec
    val list = remember(lg) { c.devices.specs.filter { lg.f(it) > 0 }.sortedByDescending { lg.f(it) } }
    val my = if (mine == null) -1 else list.indexOfFirst { it.key == mine.key && it.brand == mine.brand }
    val st = rememberLazyListState()
    val scope = rememberCoroutineScope()
    SubPage("World ranking", nav, st) {
        if (Board.configured) item { Segmented(listOf("Players", "Phones"), tab) { tab = it } }
        if (Board.configured && tab == 0) {
            livePlayers(c, s, d, dev?.name)
            return@SubPage
        }
        item { Segmented(League.entries.map { it.label }, league) { league = it } }
        item {
            val first = if (Board.configured) 4 else 3
            Panel(onClick = if (my >= 0) ({ scope.launch { st.animateScrollToItem(first + (my - 4).coerceAtLeast(0)) } }) else null) {
                if (mine != null && my >= 0) {
                    val value = lg.f(mine)
                    val pos = list.indexOfFirst { lg.f(it) <= value } + 1
                    T("${Countries.flag(mine.country)} ${mine.name}", Type.section)
                    Row(verticalAlignment = Alignment.Bottom) {
                        T("#$pos", Type.big)
                        T(" of ${list.size}", Type.section, Amp.c.dim, Modifier.padding(bottom = 8.dp))
                    }
                    val below = list.count { lg.f(it) < value }
                    T("Ahead of ${below * 100 / list.size}% of the phones in the list. Tap to find it.", Type.small, Amp.c.dim)
                    if (lg == League.WIRED && d.stats.peakMw > 0) {
                        Spacer(Modifier.height(6.dp))
                        T("Your best so far is ${Fmt.watts(d.stats.peakMw)} W at the cell, ${(d.stats.peakMw / 10f / mine.wiredW).roundToInt()}% of its rating.", Type.small, Amp.c.dim)
                    }
                } else {
                    T("Your phone is not in this list", Type.section)
                    T(if (mine != null) "It has no published figure for this category." else "Ampere has no published charging spec for it yet. Everything else still works.", Type.small, Amp.c.dim)
                }
            }
        }
        item { T("Published specs. The flag is the maker's home country. " + (if (lg == League.RATE) "C is how many full batteries an hour the peak power equals." else ""), Type.small, Amp.c.faint, Modifier.padding(horizontal = 6.dp)) }
        val top = lg.f(list.first())
        itemsIndexed(list, key = { _, p -> p.brand + p.key }) { i, p ->
            val v = lg.f(p)
            val rank = if (i > 0 && lg.f(list[i - 1]) == v) list.indexOfFirst { lg.f(it) == v } + 1 else i + 1
            val me = i == my
            Column(
                Modifier.fillMaxWidth().animateItem().clip(RoundedCornerShape(16.dp)).background(if (me) Amp.c.raised else Amp.c.bg).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("$rank", Type.mono, if (rank <= 3) Amp.c.power else Amp.c.faint, Modifier.width(38.dp))
                    T(Countries.flag(p.country), Type.body, modifier = Modifier.width(32.dp))
                    T(p.name, if (me) Type.strong else Type.body, modifier = Modifier.weight(1f), maxLines = 1)
                    T(fmtLeague(lg, v) + " " + lg.unit, Type.mono, if (me) Amp.c.power else Amp.c.dim)
                }
                Spacer(Modifier.height(6.dp))
                Bar({ v / top }, if (me) Amp.c.power else Amp.c.line, Modifier.padding(start = 38.dp), 3.dp, if (me) Amp.c.line else Amp.c.bg)
            }
        }
    }
}

private fun fmtLeague(l: League, v: Float) = when (l) {
    League.SIZE -> Fmt.int(v.toInt())
    League.RATE -> Fmt.dec(v, 1)
    else -> Fmt.dec(v, if (v % 1f == 0f) 0 else 1)
}

private fun entry(s: Settings, d: Derived, device: String?) = BoardRow(
    0, displayNick(s).takeIf { it.length >= 2 } ?: "Player", s.country, device ?: "", d.game.vp,
    d.stats.bracket(Bracket(0, 100)).bestSec, d.stats.bracket(Bracket(20, 80)).bestSec, d.stats.bracket(Bracket(40, 100)).bestSec,
    d.stats.peakMw, d.stats.cycles,
)

private fun androidx.compose.foundation.lazy.LazyListScope.livePlayers(c: Container, s: Settings, d: Derived, device: String?) {
    item("live") { LiveBoard(c, s, d, device) }
}

@Composable
private fun LiveBoard(c: Container, s: Settings, d: Derived, device: String?) {
    val scope = rememberCoroutineScope()
    var metric by rememberSaveable { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<BoardRow>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val m = BoardMetric.entries[metric]
    LaunchedEffect(metric, tick, s.boardOptIn) {
        rows = null
        err = null
        if (s.boardOptIn && s.boardToken.isNotEmpty()) Board.submit(s.boardToken, entry(s, d, device))
        Board.top(m).onSuccess { rows = it }.onFailure { err = "Could not reach the board. Check your connection." }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            ToggleRow("Join the world board", "Shares your nickname, country, phone model and records. Leaving deletes your entry.", s.boardOptIn) { on ->
                scope.launch {
                    if (on) c.prefs.edit {
                        if ((it[Prefs.K.boardToken] ?: "").isEmpty()) it[Prefs.K.boardToken] = UUID.randomUUID().toString()
                        it[Prefs.K.boardOptIn] = true
                    } else Board.remove(s.boardToken).onSuccess { c.prefs.edit { p -> p[Prefs.K.boardOptIn] = false } }
                        .onFailure { err = "Could not remove your entry. Try again when online." }
                }
            }
        }
        Segmented(BoardMetric.entries.take(3).map { it.label }, if (metric < 3) metric else -1) { metric = it }
        Segmented(BoardMetric.entries.drop(3).map { it.label }, if (metric >= 3) metric - 3 else -1) { metric = it + 3 }
        val r = rows
        Panel(pad = 8.dp) {
            when {
                err != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Empty("Offline", err!!)
                    AmpButton("Try again", primary = false) { tick++ }
                }
                r == null -> Empty("Loading", "Fetching the world board.")
                r.isEmpty() -> Empty("No entries yet", "Be the first.")
                else -> r.forEachIndexed { i, e ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        T("${i + 1}", Type.mono, if (i < 3) Amp.c.power else Amp.c.faint, Modifier.width(34.dp))
                        T(Countries.flag(e.country), Type.body, modifier = Modifier.width(32.dp))
                        Column(Modifier.weight(1f)) {
                            T(e.nick, Type.strong, maxLines = 1)
                            T(e.device, Type.small, Amp.c.dim, maxLines = 1)
                        }
                        T(
                            when (m) {
                                BoardMetric.VP -> "${Fmt.int(e.vp)} VP"
                                BoardMetric.T2080 -> e.t2080?.let { Fmt.split(it) } ?: Fmt.NONE
                                BoardMetric.T0100 -> e.t0100?.let { Fmt.split(it) } ?: Fmt.NONE
                                BoardMetric.T40100 -> e.t40100?.let { Fmt.split(it) } ?: Fmt.NONE
                                BoardMetric.PEAK -> "${Fmt.watts(e.peakMw)} W"
                                BoardMetric.CYCLES -> Fmt.dec(e.cycles, 1)
                            },
                            Type.mono,
                        )
                        IconBtn(Icons.Rounded.Flag, "Report this entry", tint = Amp.c.faint) { scope.launch { Board.report(e.id) } }
                    }
                }
            }
        }
    }
}

// ---- profile ---------------------------------------------------------------------------------------

@Composable
fun ProfileScreen(c: Container, s: Settings, nav: Nav) {
    SubPage("Profile", nav) {
        item { NickField(c, s) }
        item { CountryPicker(c, s) }
    }
}

@Composable
fun NickField(c: Container, s: Settings) {
    val scope = rememberCoroutineScope()
    var t by rememberSaveable { mutableStateOf(s.nick) }
    OutlinedTextField(
        t,
        { v ->
            t = v.filter { it.isLetterOrDigit() && it.code < 128 || it in "_ .-" }.take(18)
            scope.launch { c.prefs.edit { it[Prefs.K.nick] = t.trim() } }
        },
        Modifier.fillMaxWidth(), singleLine = true, textStyle = Type.body, label = { T("Nickname", Type.small, Amp.c.dim) },
        shape = RoundedCornerShape(18.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Amp.c.text, unfocusedBorderColor = Amp.c.line, cursorColor = Amp.c.text),
    )
}

@Composable
fun CountryPicker(c: Container, s: Settings, max: Int = 400) {
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    val list = remember(q) { Countries.all.filter { q.isBlank() || it.name.contains(q, true) || it.code.equals(q, true) } }
    Panel(pad = 8.dp) {
        OutlinedTextField(
            q, { q = it }, Modifier.fillMaxWidth().padding(8.dp), singleLine = true, textStyle = Type.body, shape = RoundedCornerShape(18.dp),
            label = { T(if (s.country.isEmpty()) "Country" else "${Countries.flag(s.country)}  ${Countries.name(s.country)}", Type.small, Amp.c.dim) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Amp.c.text, unfocusedBorderColor = Amp.c.line, cursorColor = Amp.c.text),
        )
        LazyColumn(Modifier.height(max.dp)) {
            items(list, key = { it.code }) { x ->
                val on = x.code == s.country
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) Amp.c.raised else Amp.c.surface)
                        .clickable { scope.launch { c.prefs.edit { it[Prefs.K.country] = x.code } } }.padding(horizontal = 14.dp, vertical = 12.dp),
                    Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
                ) {
                    T(x.flag, Type.section)
                    T(x.name, Type.body, modifier = Modifier.weight(1f), maxLines = 1)
                    if (on) Box(Modifier.size(20.dp).clip(CircleShape).background(Amp.c.good), Alignment.Center) { Icon(Icons.Rounded.Check, "Selected", Modifier.size(14.dp), tint = Amp.c.bg) }
                }
            }
        }
    }
}
