package dev.eliaschen.wristch

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import dev.eliaschen.wristch.accessibility.WristchAccessibilityService
import dev.eliaschen.wristch.ui.screen.AccessibilityBlockerScreen
import dev.eliaschen.wristch.ui.screen.AgentScreen
import dev.eliaschen.wristch.ui.screen.HistoryScreen
import dev.eliaschen.wristch.ui.screen.HomeScreen
import dev.eliaschen.wristch.ui.component.WristchToolbar
import dev.eliaschen.wristch.ui.screen.RunDetailScreen
import dev.eliaschen.wristch.ui.screen.SearchScreen
import dev.eliaschen.wristch.ui.screen.SettingsScreen
import dev.eliaschen.wristch.ui.screen.VibeDetailScreen
import dev.eliaschen.wristch.ui.screen.VibeScreen
import dev.eliaschen.wristch.ui.screen.MemoryScreen
import dev.eliaschen.wristch.ui.screen.MemoryDetailScreen
import kotlinx.serialization.Serializable

/**
 * Material 3's emphasized easings, which are what give Google's own apps their motion:
 * the incoming page decelerates into place, the outgoing one accelerates away, and
 * neither uses the symmetric curve that makes a plain slide feel like it is dragging.
 */
private val EMPHASIZED_DECELERATE = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EMPHASIZED_ACCELERATE = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** Long enough to be read as movement, short enough that nobody waits on it. */
private const val SLIDE_MS = 300
private const val FADE_IN_MS = 210
private const val FADE_OUT_MS = 90

/**
 * One page replacing another, along the horizontal axis.
 *
 * A shared-axis transition rather than a full-width slide: the pages move about a tenth
 * of the screen and the fade does the rest of the work, which is why this reads as one
 * surface changing its contents instead of as a card being dealt over another. The
 * outgoing page leaves early and the incoming one fades in behind it, so the two are
 * never both at full strength on top of each other.
 *
 * [forward] is the direction of travel - deeper into the app moves left, back moves right.
 */
private fun pageTransition(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    return (
        slideInHorizontally(tween(SLIDE_MS, easing = EMPHASIZED_DECELERATE)) {
            direction * it / 10
        } + fadeIn(tween(FADE_IN_MS, delayMillis = FADE_OUT_MS))
    ) togetherWith (
        slideOutHorizontally(tween(SLIDE_MS, easing = EMPHASIZED_ACCELERATE)) {
            -direction * it / 10
        } + fadeOut(tween(FADE_OUT_MS))
    )
}

@Serializable
data object Home : NavKey

/** Everything the agent and the user remember - what Home's left-hand button opens. */
@Serializable
data object Memory : NavKey

/** One remembered thing, in full. */
@Serializable
data class MemoryDetail(val memoryId: String) : NavKey

/**
 * The full run list. Home shows the last few runs; this is what the arrow beside them
 * opens, so it lives on Home's stack rather than in the bar.
 */
@Serializable
data object History : NavKey

/** One run's actions. */
@Serializable
data class RunDetail(val runId: String) : NavKey

/** Every vibe, and which one is the default - what the arrow beside Home's row opens. */
@Serializable
data object Vibe : NavKey

/** One vibe's own settings. */
@Serializable
data class VibeDetail(val vibeId: String) : NavKey

/** One field over past runs and memory, opened from the toolbar's search bar. */
@Serializable
data object Search : NavKey

/** App-wide switches - what Home's top-right button opens. */
@Serializable
data object Settings : NavKey

/**
 * Where a run is started and watched - what the toolbar's action button opens.
 *
 * [goal] is what the screen opens with already typed: null from the toolbar, where the
 * sentence is still to be written, and the old goal when a finished run is being sent
 * again from its detail screen.
 */
@Serializable
data class Agent(val goal: String? = null, val vibeId: String? = null) : NavKey

@Composable
fun WristchNavGraph(modifier: Modifier = Modifier) {
    val isConnected by WristchAccessibilityService.isConnected.collectAsState()
    if (!isConnected) {
        AccessibilityBlockerScreen(modifier)
        return
    }

    // One stack, because there is one place to come back to. The app used to have a tab
    // bar and a stack per tab; the toolbar replaced it, and Agent became somewhere you go
    // and return from rather than somewhere you live.
    val backStack = remember { mutableStateListOf<NavKey>(Home) }
    // The toolbar belongs to home: it is the bar you search and start runs from, and on a
    // screen that is itself a search or a run it would only offer you where you already are.
    val showToolbar by remember { derivedStateOf { backStack.lastOrNull() == Home } }

    // A run that has finished asks to be shown, from outside any composition - see
    // [TaskRoute]. It lands on top of whatever is open, except the composer that started
    // it, which is replaced: stepping back from a finished run should reach the list, not
    // the empty box the run was typed into.
    val pendingRun by TaskRoute.pending.collectAsState()
    LaunchedEffect(pendingRun) {
        val id = pendingRun ?: return@LaunchedEffect
        val top = backStack.lastOrNull()
        when {
            top == RunDetail(id) -> Unit
            top is Agent || top is RunDetail -> {
                backStack.removeLastOrNull()
                backStack.add(RunDetail(id))
            }
            else -> backStack.add(RunDetail(id))
        }
        TaskRoute.consume(id)
    }

    // The Scaffold holds back none of the window: every screen draws to all four edges and
    // pads itself where its own content would otherwise sit under a system bar. Padding the
    // whole app here instead would leave a band of background above and below it.
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { pageTransition(forward = true) },
                popTransitionSpec = { pageTransition(forward = false) },
                // The edge the swipe came from decides which way the pages move, so a
                // gesture from the right runs the animation the other way round.
                predictivePopTransitionSpec = { edge ->
                    pageTransition(forward = edge == NavigationEvent.EDGE_RIGHT)
                },
                entryProvider = entryProvider {
                    entry<Home> {
                        HomeScreen(
                            onOpenRun = { backStack.add(RunDetail(it)) },
                            onOpenHistory = { backStack.add(History) },
                            onOpenVibe = { backStack.add(VibeDetail(it)) },
                            onOpenVibes = { backStack.add(Vibe) },
                            onOpenMemory = { backStack.add(Memory) },
                            onOpenSettings = { backStack.add(Settings) },
                        )
                    }
                    entry<Settings> {
                        SettingsScreen(onBack = { backStack.removeLastOrNull() })
                    }
                    entry<History> {
                        HistoryScreen(
                            onOpenRun = { backStack.add(RunDetail(it)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<RunDetail> { key ->
                        RunDetailScreen(
                            runId = key.runId,
                            onBack = { backStack.removeLastOrNull() },
                            onRunAgain = { goal, vibeId ->
                                // Replace rather than stack: coming back from the run the
                                // detail screen just launched should land on history, not
                                // on the record of the run that was copied.
                                backStack.removeLastOrNull()
                                backStack.add(Agent(goal, vibeId))
                            },
                            onOpenRun = { backStack.add(RunDetail(it)) },
                            onContinue = { newRunId ->
                                // Same replace as above: a follow-up asked for from the
                                // finish dialog is a new run, and stepping back from it
                                // should land on history rather than replay the dialog.
                                backStack.removeLastOrNull()
                                backStack.add(RunDetail(newRunId))
                            },
                        )
                    }
                    entry<Vibe> {
                        VibeScreen(
                            onOpenVibe = { backStack.add(VibeDetail(it)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<VibeDetail> { key ->
                        VibeDetailScreen(
                            vibeId = key.vibeId,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<Search> {
                        SearchScreen(
                            onOpenRun = { backStack.add(RunDetail(it)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<Memory> {
                        MemoryScreen(
                            onOpenMemory = { backStack.add(MemoryDetail(it)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<MemoryDetail> { key ->
                        MemoryDetailScreen(
                            memoryId = key.memoryId,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<Agent> { key ->
                        AgentScreen(
                            initialGoal = key.goal,
                            initialVibeId = key.vibeId,
                            onBack = { backStack.removeLastOrNull() },
                            onRunStarted = { runId ->
                                // Replace rather than stack: coming back from the run this
                                // screen just started should land on history, not on an
                                // empty composer.
                                backStack.removeLastOrNull()
                                backStack.add(RunDetail(runId))
                            },
                        )
                    }
                },
            )

            if (showToolbar) {
                WristchToolbar(
                    onSearch = { backStack.add(Search) },
                    onAgent = { backStack.add(Agent()) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
