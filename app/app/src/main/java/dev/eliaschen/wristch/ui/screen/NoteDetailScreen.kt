package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.notes.NoteStore
import dev.eliaschen.wristch.notes.Note
import dev.eliaschen.wristch.vibe.VibeStore

@Composable
fun NoteDetailScreen(
    noteId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var note = NoteStore.find(noteId)
    // Keep observing store so edits and deletes reflect immediately
    val notes by NoteStore.notes.collectAsState()
    note = notes.firstOrNull { it.id == noteId }
    val vibes by VibeStore.vibes.collectAsState()

    if (note == null) {
        // Note was deleted, go back
        onBack()
        return
    }

    var text by remember { mutableStateOf(note.text) }
    // Keep local copy in sync with store when it changes externally
    if (text != note.text) text = note.text

    Column(modifier = modifier.fillMaxWidth().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
            }
            Text(text = "Edit note", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                NoteStore.delete(noteId)
                onBack()
            }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete note")
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                text = value
                NoteStore.edit(noteId, value)
            },
            label = { Text("Note") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            minLines = 6,
        )

        Text(text = "Scope (which vibe sees this)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth(), content = {
            item {
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        NoteStore.scope(noteId, null)
                    }
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = note.vibeId == null, onClick = { NoteStore.scope(noteId, null) })
                    Text(text = "All vibes", modifier = Modifier.padding(start = 8.dp))
                }
            }
            items(vibes.size) { index ->
                val v = vibes[index]
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .clickable { NoteStore.scope(noteId, v.id) }
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = note.vibeId == v.id, onClick = { NoteStore.scope(noteId, v.id) })
                    Text(text = v.name.ifBlank { "(unnamed)" }, modifier = Modifier.padding(start = 8.dp))
                }
            }
        })

        Button(onClick = { /* saved automatically via NoteStore.edit */ }, modifier = Modifier.padding(top = 16.dp)) {
            Text("Done")
        }
    }
}
