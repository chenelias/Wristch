package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.BuildConfig
import dev.eliaschen.wristch.accessibility.WristchAccessibilityService
import dev.eliaschen.wristch.computer.ComputerUseAgent
import kotlinx.coroutines.launch

@Composable
fun AgentScreen(modifier: Modifier = Modifier) {
    val isConnected by WristchAccessibilityService.isConnected.collectAsState()
    val service = WristchAccessibilityService.current()
    val scope = rememberCoroutineScope()

    var goal by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val steps: SnapshotStateList<String> = remember { mutableStateListOf() }
    val listState = rememberLazyListState()

    // The agent is rebuilt whenever the service reconnects, since it holds a reference to it.
    val agent = remember(service) {
        service?.takeIf { BuildConfig.GEMINI_API_KEY.isNotBlank() }
            ?.let { ComputerUseAgent(it, BuildConfig.GEMINI_API_KEY) }
    }

    LaunchedEffect(steps.size) {
        if (steps.isNotEmpty()) listState.animateScrollToItem(steps.lastIndex)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Agent", style = MaterialTheme.typography.headlineSmall)

        val blocker = when {
            !isConnected -> "Accessibility service is off - turn it on in the Test tab."
            BuildConfig.GEMINI_API_KEY.isBlank() ->
                "No API key. Add geminiApiKey=... to local.properties and rebuild."
            else -> null
        }
        if (blocker != null) {
            Text(blocker, style = MaterialTheme.typography.bodyMedium)
        }

        OutlinedTextField(
            value = goal,
            onValueChange = { goal = it },
            label = { Text("What should the agent do?") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            trailingIcon = {
                // Only worth the space once there is something to clear, and never while a
                // run is using the text it would erase.
                if (goal.isNotEmpty() && !running) {
                    TextButton(onClick = { goal = "" }) { Text("Clear") }
                }
            },
        )

        Button(
            onClick = {
                val target = agent ?: return@Button
                val prompt = goal
                steps.clear()
                running = true
                scope.launch {
                    try {
                        val outcome = target.run(prompt) { step -> steps += step }
                        steps += outcome
                    } catch (error: Exception) {
                        // A failed request should read as a line in the log, not a crash
                        // in the middle of a run that has already moved the real screen.
                        steps += "error: ${error.message ?: error::class.simpleName}"
                    } finally {
                        running = false
                    }
                }
            },
            enabled = agent != null && goal.isNotBlank() && !running,
        ) {
            Text(if (running) "Running..." else "Send")
        }

        if (running) CircularProgressIndicator()

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(steps) { step ->
                Text(step, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
