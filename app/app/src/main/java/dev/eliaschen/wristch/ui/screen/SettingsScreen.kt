package dev.eliaschen.wristch.ui.screen

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.BuildConfig
import dev.eliaschen.wristch.accessibility.WristchAccessibilityService

/**
 * One thing that has to be true before the agent can run, and - where the person can do
 * something about it from here - the way to make it true.
 */
private data class Requirement(
    val title: String,
    val granted: Boolean,
    val explanation: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/**
 * The single place that answers "why won't it run?".
 *
 * Status is read live rather than cached, so coming back from the system settings app
 * shows the new state without a manual refresh.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val isConnected by WristchAccessibilityService.isConnected.collectAsState()
    val context = LocalContext.current

    val requirements = listOf(
        Requirement(
            title = "Accessibility service",
            granted = isConnected,
            explanation = "Lets Wristch read the screen and tap, type and scroll on your " +
                "behalf. Every action the agent takes goes through it. Its overlays - the " +
                "status strip and the approval prompt - are granted along with it, so " +
                "there is no separate \"draw over other apps\" step.",
            actionLabel = "Open accessibility settings",
            onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        ),
        Requirement(
            title = "Gemini API key",
            granted = BuildConfig.GEMINI_API_KEY.isNotBlank(),
            explanation = "Read from local.properties at build time. Add geminiApiKey=... " +
                "there and rebuild. There is deliberately no field for it here - that is " +
                "what keeps the key out of the repo and off the device's app storage.",
        ),
    )

    val outstanding = requirements.count { !it.granted }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = when (outstanding) {
                0 -> "Everything is granted - Wristch is ready to run tasks."
                1 -> "1 step still needs you before the agent can run."
                else -> "$outstanding steps still need you before the agent can run."
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        requirements.forEach { requirement ->
            RequirementCard(requirement)
        }
    }
}

@Composable
private fun RequirementCard(requirement: Requirement) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (requirement.granted) {
                        Icons.Default.CheckCircle
                    } else {
                        Icons.Default.Warning
                    },
                    // Spelled out rather than left null: the status is the point of the row,
                    // and it is carried by colour alone otherwise.
                    contentDescription = if (requirement.granted) "Granted" else "Not granted",
                    tint = if (requirement.granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(requirement.title, style = MaterialTheme.typography.titleMedium)
            }

            Text(requirement.explanation, style = MaterialTheme.typography.bodySmall)

            val action = requirement.onAction
            val actionLabel = requirement.actionLabel
            if (!requirement.granted && action != null && actionLabel != null) {
                Button(onClick = action) { Text(actionLabel) }
            }
        }
    }
}
