package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.notes.NoteStore
import dev.eliaschen.wristch.notes.Note
import dev.eliaschen.wristch.vibe.VibeStore

@Composable
fun NotesScreen(
    onOpenNote: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notes by NoteStore.notes.collectAsState()
    val vibes by VibeStore.vibes.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to home", modifier = Modifier.size(18.dp))
            }
            Text(text = "Notes", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val id = NoteStore.add(text = "", author = dev.eliaschen.wristch.notes.NoteAuthor.USER)
                onOpenNote(id)
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add note")
            }
        }

        if (notes.isEmpty()) {
            Text(
                text = "No notes yet. Tap + to add one, or let the agent record things during a run.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp)) {
            items(notes, key = { it.id }) { note: Note ->
                Card(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { onOpenNote(note.id) }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = note.title, style = MaterialTheme.typography.titleMedium)
                        val vibeName = note.vibeId?.let { id -> vibes.firstOrNull { it.id == id }?.name }
                        val meta = listOfNotNull(
                            note.author.name.lowercase().replaceFirstChar { it.uppercase() },
                            vibeName?.let { "for $it" },
                        ).joinToString(" • ")
                        Text(text = meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
