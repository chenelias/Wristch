package dev.eliaschen.wristch.ui.screen

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.memory.MemoryAuthor
import dev.eliaschen.wristch.memory.MemoryStore
import dev.eliaschen.wristch.ui.component.ScreenHeader
import dev.eliaschen.wristch.vibe.VibeStore

/**
 * One memory, in full: its text, who put it there, and which vibe it is scoped to.
 *
 * There is no save button - every keystroke writes straight through to [MemoryStore], the
 * same way a vibe's fields do in [VibeDetailScreen], because a memory left half-typed when
 * the app is swiped away is a worse outcome than one that changed as it was touched.
 *
 * An agent-written memory is editable here on the same terms as the user's own. That is
 * the point of the screen: the agent gets to write what it thinks it learned, and the
 * person it learned it about gets the last word on whether it stays.
 */
@Composable
fun MemoryDetailScreen(
    memoryId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Kept live so an edit or delete from elsewhere - or the delete button below - is
    // reflected immediately rather than leaving a stale screen behind.
    val memories by MemoryStore.memories.collectAsState()
    val memory = memories.firstOrNull { it.id == memoryId }
    val vibes by VibeStore.vibes.collectAsState()

    if (memory == null) {
        // Deleted from under us; there is nothing left here to show.
        onBack()
        return
    }

    var text by remember(memoryId) { mutableStateOf(memory.text) }
    if (text != memory.text) text = memory.text

    // The keyboard takes the bottom of the screen, so the screen ends above it and the
    // field being written in is scrolled into what is left.
    Column(modifier = modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        ScreenHeader(title = "Memory", onBack = onBack) {
            IconButton(onClick = {
                MemoryStore.delete(memoryId)
                onBack()
            }) {
                Icon(Icons.Default.Delete, contentDescription = "Forget this")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.safeDrawing
                .add(WindowInsets(left = 24.dp, right = 24.dp, bottom = 24.dp))
                .asPaddingValues(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "origin") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AuthorBadge(memory.author)
                    Text(
                        text = origin(memory.author, memory.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "text") {
                OutlinedTextField(
                    value = text,
                    onValueChange = { value ->
                        text = value
                        MemoryStore.edit(memoryId, value)
                    },
                    label = { Text("Remembered") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                )
            }

            item(key = "scope-header") {
                Text(
                    text = "Scope",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            item(key = "scope-hint") {
                Text(
                    text = "Which vibe sees this - the rest never do.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item(key = "scope-all") {
                ScopeRow(
                    label = "All vibes",
                    selected = memory.vibeId == null,
                    onClick = { MemoryStore.scope(memoryId, null) },
                )
            }
            items(vibes, key = { it.id }) { vibe ->
                ScopeRow(
                    label = vibe.name.ifBlank { "(unnamed)" },
                    selected = memory.vibeId == vibe.id,
                    onClick = { MemoryStore.scope(memoryId, vibe.id) },
                )
            }
        }
    }
}

/** Where this came from, in one line: who wrote it and roughly when. */
private fun origin(author: MemoryAuthor, createdAt: Long): String {
    val who = when (author) {
        MemoryAuthor.USER -> "You wrote this"
        MemoryAuthor.AGENT -> "The agent learned this"
    }
    if (createdAt <= 0L) return who
    val when_ = DateUtils.getRelativeTimeSpanString(
        createdAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    )
    return "$who $when_"
}

@Composable
private fun ScopeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, modifier = Modifier.padding(start = 8.dp))
    }
}
