package dev.eliaschen.wristch

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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
import dev.eliaschen.wristch.accessibility.WristchAccessibilityService
import dev.eliaschen.wristch.ui.screen.AccessibilityBlockerScreen
import dev.eliaschen.wristch.ui.screen.AgentScreen
import dev.eliaschen.wristch.ui.screen.HistoryScreen
import dev.eliaschen.wristch.ui.screen.RunDetailScreen
import dev.eliaschen.wristch.ui.screen.SettingsScreen
import dev.eliaschen.wristch.ui.screen.VibeScreen
import kotlinx.serialization.Serializable

sealed interface TopLevelRoute : NavKey {
    val label: String
    val icon: ImageVector
}

@Serializable
data object History : TopLevelRoute {
    override val label get() = "History"
    override val icon get() = Icons.AutoMirrored.Default.List
}

/** One run's actions, pushed on top of the History tab's own stack. */
@Serializable
data class RunDetail(val runId: String) : NavKey

@Serializable
data object Vibe : TopLevelRoute {
    override val label get() = "Vibe"
    override val icon get() = Icons.Default.Favorite
}

@Serializable
data object Agent : TopLevelRoute {
    override val label get() = "Agent"
    override val icon get() = Icons.Default.PlayArrow
}

@Serializable
data object Settings : TopLevelRoute {
    override val label get() = "Settings"
    override val icon get() = Icons.Default.Settings
}

private val TOP_LEVEL_ROUTES: List<TopLevelRoute> =
    listOf(History, Vibe, Agent, Settings)

@Composable
fun WristchNavGraph(modifier: Modifier = Modifier) {
    val isConnected by WristchAccessibilityService.isConnected.collectAsState()
    if (!isConnected) {
        AccessibilityBlockerScreen(modifier)
        return
    }

    val navigator = remember { TopLevelBackStack<NavKey>(History) }

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
            entryProvider = entryProvider {
                entry<History> {
                    HistoryScreen(onOpenRun = { navigator.add(RunDetail(it)) })
                }
                entry<RunDetail> { key ->
                    RunDetailScreen(
                        runId = key.runId,
                        onBack = { navigator.removeLast() },
                    )
                }
                entry<Vibe> { VibeScreen() }
                entry<Agent> { AgentScreen() }
                entry<Settings> { SettingsScreen() }
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
