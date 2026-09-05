package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.ui.shape.WristchShapes
import dev.eliaschen.wristch.ui.theme.accentOf
import dev.eliaschen.wristch.vibe.Vibe
import dev.eliaschen.wristch.vibe.VibeStore

/**
 * Every vibe the user keeps, and which one runs when nothing picked another.
 *
 * The list is the whole screen: a vibe is a name, a colour and a switch here, and
 * everything that makes it different from the next one - the wording rules, the notes,
 * what it may read - lives one tap deeper in [VibeDetailScreen]. Splitting it that way is
 * what keeps this readable as "who am I talking to" rather than as a wall of prompts.
 */
@Composable
fun VibeScreen(
    onOpenVibe: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vibes by VibeStore.vibes.collectAsState()
    val defaultId by VibeStore.defaultId.collectAsState()
    val selectable = vibes.filter { it.enabled }

    // No Scaffold here: the nav graph already owns one, and nesting a second would apply
    // the window insets twice.
    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to home",
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = "Vibe",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onOpenVibe(VibeStore.create()) }) {
                Icon(Icons.Default.Add, contentDescription = "Add a vibe")
            }
        }

        Text(
            text = "A vibe is who you are talking to: how a message should sound, what " +
                "the agent already knows, and how much it may do on its own.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.safeDrawing
                .add(WindowInsets(left = 24.dp, right = 24.dp, bottom = 24.dp))
                .asPaddingValues(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vibes.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "No vibes yet. Add one for a person or a part of your life " +
                            "- school, home, work - and tasks sent under it are written " +
                            "that way.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@LazyColumn
            }

            item(key = "vibes-header") { SectionLabel("Your vibes") }

            items(vibes, key = { it.id }) { vibe ->
                VibeRow(
                    vibe = vibe,
                    onClick = { onOpenVibe(vibe.id) },
                    onEnabledChange = { VibeStore.setEnabled(vibe.id, it) },
                )
            }

            item(key = "default-header") {
                Column {
                    SectionLabel("Default vibe")
                    Text(
                        text = "Used when a task arrives without one - the phone widget, " +
                            "or a watch gesture with no vibe of its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }

            if (selectable.isEmpty()) {
                item(key = "default-none") {
                    Text(
                        text = "Every vibe is switched off, so tasks run with no vibe at all.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                items(selectable, key = { "default-${it.id}" }) { vibe ->
                    DefaultRow(
                        vibe = vibe,
                        selected = vibe.id == defaultId,
                        onSelect = { VibeStore.setDefault(vibe.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun VibeRow(
    vibe: Vibe,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VibeAvatar(vibe)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = vibe.name.ifBlank { "(unnamed)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summaryOf(vibe),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = vibe.enabled,
                onCheckedChange = onEnabledChange,
            )
        }
    }
}

@Composable
private fun DefaultRow(vibe: Vibe, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(vibe.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.bodyLarge)
            if (vibe.subtitle.isNotBlank()) {
                Text(
                    text = vibe.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The vibe's initial on its own colour.
 *
 * A switched-off vibe is drawn flat in the surface colour rather than greyed by opacity:
 * the row keeps its full contrast, and the colour - the thing that identifies it - is
 * what goes away.
 */
@Composable
internal fun VibeAvatar(vibe: Vibe, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val accent = accentOf(vibe.accent)
    Surface(
        modifier = Modifier.size(size),
        shape = WristchShapes.CookieShape,
        color = if (vibe.enabled) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = vibe.initial,
                style = MaterialTheme.typography.titleMedium,
                color = if (vibe.enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The one line under the name: what this vibe carries, and how freely it acts. */
private fun summaryOf(vibe: Vibe): String {
    if (!vibe.enabled) return "Off - not offered to the watch or the widget"
    val sources = when (vibe.sources.size) {
        0 -> "nothing extra"
        else -> vibe.sources.joinToString { it.label.lowercase() }
    }
    return "${vibe.confirmation.label} - carries $sources"
}
