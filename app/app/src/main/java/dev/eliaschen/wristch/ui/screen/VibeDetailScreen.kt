package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.ui.shape.WristchShapes
import dev.eliaschen.wristch.ui.theme.VibeAccents
import dev.eliaschen.wristch.ui.theme.accentOf
import dev.eliaschen.wristch.vibe.Vibe
import dev.eliaschen.wristch.vibe.VibeConfirmation
import dev.eliaschen.wristch.vibe.VibeSource
import dev.eliaschen.wristch.vibe.VibeStore

/**
 * One vibe, in full.
 *
 * Every control writes straight through to the store: there is no save button, because a
 * half-edited vibe that was never saved is a worse outcome than one that changed as soon
 * as it was touched, and the same edit has to survive the app being swiped away while the
 * watch is the thing actually using it.
 */
@Composable
fun VibeDetailScreen(
    vibeId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vibes by VibeStore.vibes.collectAsState()
    val vibe = vibes.firstOrNull { it.id == vibeId }
    var confirmDelete by remember { mutableStateOf(false) }

    if (vibe == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("This vibe no longer exists.")
            BackToVibes(onBack)
        }
        return
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this vibe?",
            body = "\"${vibe.name.ifBlank { "(unnamed)" }}\" and everything written under " +
                "it - wording, notes, what it may read - will be removed. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                // Back first: this screen reads the vibe out of the store by id, and the
                // moment it is gone this composition has nothing left to show.
                onBack()
                VibeStore.delete(vibeId)
            },
            onDismiss = { confirmDelete = false },
        )
    }

    val edit: ((Vibe) -> Vibe) -> Unit = { change -> VibeStore.update(vibeId, change) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "top") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackToVibes(onBack)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete this vibe",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item(key = "identity") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VibeAvatar(vibe, size = 56.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = vibe.name.ifBlank { "(unnamed)" },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = if (vibe.enabled) "On" else "Off - not offered to the watch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = vibe.enabled,
                    onCheckedChange = { VibeStore.setEnabled(vibeId, it) },
                )
            }
        }

        item(key = "name") {
            OutlinedTextField(
                value = vibe.name,
                onValueChange = { name -> edit { it.copy(name = name) } },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(key = "subtitle") {
            OutlinedTextField(
                value = vibe.subtitle,
                onValueChange = { subtitle -> edit { it.copy(subtitle = subtitle) } },
                label = { Text("Who or what it covers") },
                placeholder = { Text("Teachers, classmates, clubs") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(key = "accent") {
            Column {
                SectionHeading("Colour")
                AccentPicker(
                    selected = vibe.accent,
                    onSelect = { index -> edit { it.copy(accent = index) } },
                )
            }
        }

        item(key = "instruction") {
            Column {
                SectionHeading("How it should sound")
                Text(
                    text = "Given to the model before your task. Tone, wording, standing " +
                        "rules - anything that would otherwise have to be typed every time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = vibe.instruction,
                    onValueChange = { text -> edit { it.copy(instruction = text) } },
                    placeholder = { Text("Address teachers by title and surname, keep it short") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "notes") {
            Column {
                SectionHeading("What it already knows")
                Text(
                    text = "Background the model may use and quote - names, habits, the " +
                        "usual meeting place.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = vibe.notes,
                    onValueChange = { text -> edit { it.copy(notes = text) } },
                    placeholder = { Text("Mr. Lin runs badminton practice on Thursdays") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "sources-heading") {
            Column {
                SectionHeading("What it may pull in")
                Text(
                    text = "Only under this vibe. Family can carry where you are; school " +
                        "does not have to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(VibeSource.entries.size, key = { "source-${VibeSource.entries[it].name}" }) { index ->
            val source = VibeSource.entries[index]
            SourceRow(
                source = source,
                accent = accentOf(vibe.accent),
                checked = source in vibe.sources,
                onCheckedChange = { on ->
                    edit {
                        val sources = if (on) it.sources + source else it.sources - source
                        it.copy(sources = sources)
                    }
                },
            )
        }

        item(key = "confirmation-heading") {
            Column {
                SectionHeading("Before it acts")
                Text(
                    text = "How much the agent asks while this vibe is in charge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(
            VibeConfirmation.entries.size,
            key = { "confirm-${VibeConfirmation.entries[it].name}" },
        ) { index ->
            val option = VibeConfirmation.entries[index]
            ConfirmationRow(
                option = option,
                selected = option == vibe.confirmation,
                onSelect = { edit { it.copy(confirmation = option) } },
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/**
 * The row per source: the switch is the whole control, and the row is not clickable.
 *
 * Deliberately unlike the confirmation rows below, where the row itself selects - a
 * mis-tap there picks a neighbouring option, a mis-tap here would hand a vibe your
 * location.
 */
@Composable
private fun SourceRow(
    source: VibeSource,
    accent: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(source.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (checked) source.explanation else "Not shared with this vibe",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ConfirmationRow(
    option: VibeConfirmation,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(option.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = option.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The palette, as swatches.
 *
 * The chosen one carries a tick rather than only a ring: on a screen full of colour, the
 * ring is the one cue that is itself a colour difference.
 */
@Composable
private fun AccentPicker(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VibeAccents.forEachIndexed { index, color ->
            val isSelected = index == selected.mod(VibeAccents.size)
            Surface(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onSelect(index) }
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = WristchShapes.CookieShape,
                            )
                        } else {
                            Modifier
                        },
                    ),
                shape = WristchShapes.CookieShape,
                color = color,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackToVibes(onBack: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = "Back to vibes",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
