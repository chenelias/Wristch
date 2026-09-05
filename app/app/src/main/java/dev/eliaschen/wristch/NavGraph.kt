package dev.eliaschen.wristch

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import dev.eliaschen.wristch.accessibility.WristchAccessibilityService
import dev.eliaschen.wristch.ui.screen.AccessibilityBlockerScreen
import dev.eliaschen.wristch.ui.screen.AgentScreen
import dev.eliaschen.wristch.ui.screen.HistoryScreen
import dev.eliaschen.wristch.ui.screen.HomeScreen
import dev.eliaschen.wristch.ui.screen.RunDetailScreen
import dev.eliaschen.wristch.ui.screen.VibeDetailScreen
import dev.eliaschen.wristch.ui.screen.VibeScreen
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

sealed interface TopLevelRoute : NavKey {
    val label: String
    val icon: ImageVector
}

@Serializable
data object Home : TopLevelRoute {
    override val label get() = "Home"
    override val icon get() = Icons.Default.Home
}

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

@Serializable
data object Agent : TopLevelRoute {
    override val label get() = "Agent"
    override val icon get() = Icons.Default.PlayArrow
}

private val TOP_LEVEL_ROUTES: List<TopLevelRoute> =
    listOf(Home, Agent)

@Composable
fun WristchNavGraph(modifier: Modifier = Modifier) {
    val isConnected by WristchAccessibilityService.isConnected.collectAsState()
    if (!isConnected) {
        AccessibilityBlockerScreen(modifier)
        return
    }

    val navigator = remember { TopLevelBackStack<NavKey>(Home) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                TOP_LEVEL_ROUTES.forEach { route ->
                    NavigationBarItem(
                        selected = route == navigator.topLevelKey,
                        onClick = { navigator.addTopLevel(route) },
                        icon = {
                            Icon(
                                imageVector = route.icon,
                                contentDescription = route.label,
                            )
                        },
                        label = { Text(route.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavDisplay(
            backStack = navigator.backStack,
            onBack = { navigator.removeLast() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
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
                        onOpenRun = { navigator.add(RunDetail(it)) },
                        onOpenHistory = { navigator.add(History) },
                        onOpenVibe = { navigator.add(VibeDetail(it)) },
                        onOpenVibes = { navigator.add(Vibe) },
                    )
                }
                entry<History> {
                    HistoryScreen(
                        onOpenRun = { navigator.add(RunDetail(it)) },
                        onBack = { navigator.removeLast() },
                    )
                }
                entry<RunDetail> { key ->
                    RunDetailScreen(
                        runId = key.runId,
                        onBack = { navigator.removeLast() },
                    )
                }
                entry<Vibe> {
                    VibeScreen(
                        onOpenVibe = { navigator.add(VibeDetail(it)) },
                        onBack = { navigator.removeLast() },
                    )
                }
                entry<VibeDetail> { key ->
                    VibeDetailScreen(
                        vibeId = key.vibeId,
                        onBack = { navigator.removeLast() },
                    )
                }
                entry<Agent> { AgentScreen() }
            },
        )
    }
}

class TopLevelBackStack<T : Any>(startKey: T) {

    private val topLevelStacks: LinkedHashMap<T, SnapshotStateList<T>> = linkedMapOf(
        startKey to mutableStateListOf(startKey),
    )

    var topLevelKey by mutableStateOf(startKey)
        private set

    val backStack = mutableStateListOf(startKey)

    private fun updateBackStack() = backStack.apply {
        clear()
        addAll(topLevelStacks.flatMap { it.value })
    }

    fun addTopLevel(key: T) {
        if (topLevelStacks[key] == null) {
            topLevelStacks.put(key, mutableStateListOf(key))
        } else {
            topLevelStacks.apply { remove(key)?.let { put(key, it) } }
        }
        topLevelKey = key
        updateBackStack()
    }

    fun add(key: T) {
        topLevelStacks[topLevelKey]?.add(key)
        updateBackStack()
    }

    fun removeLast() {
        val removedKey = topLevelStacks[topLevelKey]?.removeLastOrNull()
        topLevelStacks.remove(removedKey)
        topLevelKey = topLevelStacks.keys.last()
        updateBackStack()
    }
}
