package dev.ampere.battery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.ampere.battery.Container
import dev.ampere.battery.data.Settings
import dev.ampere.battery.domain.Achievement
import dev.ampere.battery.ui.component.IconBtn
import dev.ampere.battery.ui.component.T
import dev.ampere.battery.ui.screens.AchievementsScreen
import dev.ampere.battery.ui.screens.ArenaScreen
import dev.ampere.battery.ui.screens.DiagnosticsScreen
import dev.ampere.battery.ui.screens.GoalsScreen
import dev.ampere.battery.ui.screens.HealthScreen
import dev.ampere.battery.ui.screens.LadderScreen
import dev.ampere.battery.ui.screens.LiveScreen
import dev.ampere.battery.ui.screens.Onboarding
import dev.ampere.battery.ui.screens.PhoneScreen
import dev.ampere.battery.ui.screens.ProfileScreen
import dev.ampere.battery.ui.screens.SessionScreen
import dev.ampere.battery.ui.screens.SessionsScreen
import dev.ampere.battery.ui.screens.SettingsScreen
import dev.ampere.battery.ui.screens.StatsScreen
import dev.ampere.battery.ui.screens.WorldScreen
import dev.ampere.battery.ui.theme.Amp
import dev.ampere.battery.ui.theme.Motion
import dev.ampere.battery.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Rounded.Bolt),
    HEALTH("Health", Icons.Rounded.FavoriteBorder),
    STATS("Stats", Icons.Rounded.QueryStats),
    ARENA("Arena", Icons.Rounded.EmojiEvents),
    PHONE("Phone", Icons.Rounded.Smartphone),
}

class Nav(private val nc: NavHostController) {
    fun go(route: String) = nc.navigate(route) { launchSingleTop = true }
    fun back() {
        nc.popBackStack()
    }
}

@Composable
fun Root(c: Container, s: Settings) {
    Box(Modifier.fillMaxSize().background(Amp.c.bg)) {
        Crossfade(s.onboarded, animationSpec = tween(500), label = "onb") { done ->
            if (done) Main(c, s) else Onboarding(c, s)
        }
        UnlockToast(c)
    }
}

@Composable
private fun Main(c: Container, s: Settings) {
    val nc = rememberNavController()
    val nav = remember(nc) { Nav(nc) }
    val dur = 320
    NavHost(
        nc, "main", Modifier.fillMaxSize(),
        enterTransition = { slideInHorizontally(tween(dur, easing = FastOutSlowInEasing)) { it / 7 } + fadeIn(tween(dur - 100, 60)) },
        exitTransition = { slideOutHorizontally(tween(dur, easing = FastOutSlowInEasing)) { -it / 12 } + fadeOut(tween(160)) },
        popEnterTransition = { slideInHorizontally(tween(dur, easing = FastOutSlowInEasing)) { -it / 12 } + fadeIn(tween(dur - 100, 60)) },
        popExitTransition = { slideOutHorizontally(tween(dur, easing = FastOutSlowInEasing)) { it / 7 } + fadeOut(tween(160)) },
    ) {
        composable("main") { Tabs(c, s, nav) }
        composable("settings") { SettingsScreen(c, s, nav) }
        composable("diagnostics") { DiagnosticsScreen(c, nav) }
        composable("sessions") { SessionsScreen(c, s, nav) }
        composable("session/{id}", listOf(navArgument("id") { type = NavType.LongType })) { e ->
            SessionScreen(c, s, nav, e.arguments?.getLong("id") ?: 0)
        }
        composable("achievements") { AchievementsScreen(c, nav) }
        composable("ladder") { LadderScreen(c, nav) }
        composable("goals") { GoalsScreen(c, s, nav) }
        composable("world") { WorldScreen(c, s, nav) }
        composable("profile") { ProfileScreen(c, s, nav) }
    }
}

@Composable
private fun Tabs(c: Container, s: Settings, nav: Nav) {
    val pager = rememberPagerState { Tab.entries.size }
    val scope = rememberCoroutineScope()
    BackHandler(pager.currentPage != 0) { scope.launch { pager.animateScrollToPage(0, animationSpec = Motion.move()) } }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { i ->
            when (Tab.entries[i]) {
                Tab.LIVE -> LiveScreen(c, s, nav)
                Tab.HEALTH -> HealthScreen(c, s, nav)
                Tab.STATS -> StatsScreen(c, s, nav)
                Tab.ARENA -> ArenaScreen(c, s, nav)
                Tab.PHONE -> PhoneScreen(c, s, nav)
            }
        }
        BottomBar(
            pos = { pager.currentPage + pager.currentPageOffsetFraction }, selected = pager.targetPage,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { i ->
            scope.launch {
                // Jump most of the way for far tabs so the swipe never races through pages.
                if (kotlin.math.abs(pager.currentPage - i) > 1) pager.scrollToPage(if (i > pager.currentPage) i - 1 else i + 1)
                pager.animateScrollToPage(i, animationSpec = Motion.move())
            }
        }
    }
}

@Composable
private fun BottomBar(pos: () -> Float, selected: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    val hap = LocalHapticFeedback.current
    val bg = Amp.c.bg
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.fillMaxWidth().height(28.dp).drawBehind { drawRect(Brush.verticalGradient(listOf(bg.copy(alpha = 0f), bg))) })
        Box(Modifier.fillMaxWidth().background(bg).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 6.dp), Alignment.Center) {
            val pill = Amp.c.raised
            Row(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 14.dp).height(60.dp).drawBehind {
                    val w = size.width / Tab.entries.size
                    val pw = w - 6.dp.toPx()
                    drawRoundRect(pill, Offset(w * pos() + 3.dp.toPx(), 0f), Size(pw, size.height), CornerRadius(22.dp.toPx()))
                },
            ) {
                Tab.entries.forEachIndexed { i, t ->
                    val on = selected == i
                    val col by animateColorAsState(if (on) Amp.c.text else Amp.c.faint, label = "tab")
                    val sc by animateFloatAsState(if (on) 1f else 0.94f, Motion.snap(), label = "tabs")
                    Column(
                        Modifier.weight(1f).fillMaxSize()
                            .clickable(remember { MutableInteractionSource() }, null) {
                                if (!on) hap.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onPick(i)
                            }
                            .semantics {
                                role = Role.Tab
                                this.selected = on
                                contentDescription = t.label
                            }
                            .graphicsLayer {
                                scaleX = sc
                                scaleY = sc
                            },
                        Arrangement.Center, Alignment.CenterHorizontally,
                    ) {
                        Icon(t.icon, null, Modifier.size(23.dp), tint = col)
                        T(t.label, Type.tiny, col, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Tab page: large title that scrolls away, constrained width on tablets. */
@Composable
fun TabPage(title: String, nav: Nav, sub: String? = null, actions: @Composable () -> Unit = {}, content: LazyListScope.() -> Unit) {
    val top = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    val bottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    Box(Modifier.fillMaxSize().background(Amp.c.bg), Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 620.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 6.dp, bottom = bottom + 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(title, Type.title, maxLines = 1)
                        if (sub != null) T(sub, Type.small, Amp.c.dim, maxLines = 1)
                    }
                    actions()
                    IconBtn(Icons.Rounded.Tune, "Settings", tint = Amp.c.dim) { nav.go("settings") }
                }
            }
            content()
        }
        StatusScrim(Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun StatusScrim(modifier: Modifier) {
    val bg = Amp.c.bg
    val h = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    Spacer(modifier.fillMaxWidth().height(h).background(bg.copy(alpha = 0.92f)))
}

@Composable
fun SubPage(title: String, nav: Nav, st: LazyListState = rememberLazyListState(), actions: @Composable () -> Unit = {}, content: LazyListScope.() -> Unit) {
    val bottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val line = Amp.c.line
    val bg = Amp.c.bg
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.widthIn(max = 620.dp).fillMaxWidth().drawWithContent {
                drawContent()
                val a = if (st.firstVisibleItemIndex > 0) 1f else (st.firstVisibleItemScrollOffset / 60f).coerceIn(0f, 1f)
                drawLine(line.copy(alpha = a), Offset(0f, size.height), Offset(size.width, size.height), 1f)
            }.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Back") { nav.back() }
            T(title, Type.section.copy(fontSize = Type.title.fontSize * 0.75f), modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1)
            actions()
        }
        LazyColumn(
            Modifier.widthIn(max = 620.dp).fillMaxSize(), st,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
        )
    }
    // Keeps rows legible behind three-button navigation.
    Spacer(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom).background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.6f), bg))))
    }
}

/** A quiet celebration: one unlocked achievement at a time, from the top. */
@Composable
private fun UnlockToast(c: Container) {
    var show by remember { mutableStateOf<Achievement?>(null) }
    var last by remember { mutableStateOf<Achievement?>(null) }
    val hap = LocalHapticFeedback.current
    // Subscribing here also keeps history warm while the app is open.
    c.derived.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        c.newUnlocks.collect {
            last = it
            show = it
            hap.performHapticFeedback(HapticFeedbackType.Confirm)
            delay(3200)
            show = null
            delay(450)
        }
    }
    AnimatedVisibility(
        show != null, Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
        enter = slideInVertically(Motion.move()) { -it * 2 } + fadeIn(), exit = slideOutVertically(tween(300)) { -it * 2 } + fadeOut(),
    ) {
        Box(Modifier.fillMaxWidth(), Alignment.Center) {
            last?.let { a ->
                Row(
                    Modifier.clip(CircleShape).background(Amp.c.text).padding(horizontal = 20.dp, vertical = 12.dp),
                    Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.EmojiEvents, null, Modifier.size(22.dp), tint = Amp.c.bg)
                    Column {
                        T(a.title, Type.strong, Amp.c.bg)
                        T("${a.text}. +${a.vp} VP", Type.small, Amp.c.bg.copy(alpha = 0.72f))
                    }
                }
            }
        }
    }
}

val Transparent = Color.Transparent
