package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.settings.SettingsStore

/**
 * The switches that apply everywhere rather than to one vibe.
 *
 * Its own screen from the start rather than a menu bolted onto Home - a setting that only
 * ever gets a couple of entries still deserves a stable place to grow the next one into.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val speakOutcome by SettingsStore.speakOutcome.collectAsState()
    val returnOnFinish by SettingsStore.returnOnFinish.collectAsState()

    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader(title = "Settings", onBack = onBack)

        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Speak run outcomes", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Read the result aloud when a run finishes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = speakOutcome,
                        onCheckedChange = SettingsStore::setSpeakOutcome,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Come back to the task", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Open the task in Wristch when its run finishes, so it " +
                                "can be asked about or carried on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = returnOnFinish,
                        onCheckedChange = SettingsStore::setReturnOnFinish,
                    )
                }
            }
        }
    }
}
