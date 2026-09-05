package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.memory.Memory
import dev.eliaschen.wristch.memory.MemoryAuthor
import dev.eliaschen.wristch.memory.MemoryStore
import dev.eliaschen.wristch.ui.component.ScreenHeader
import dev.eliaschen.wristch.ui.component.screenListPadding
import dev.eliaschen.wristch.ui.icon.WristchIcons
import dev.eliaschen.wristch.vibe.VibeStore

/**
 * What the agent remembers, newest first: what the user told it to know, and what it
 * wrote down itself while working.
 *
 * A row is the memory's first line and who put it there. Who matters more here than on
 * any other list in the app - an agent-written line is something the phone decided about
 * the person using it, and this screen is where they find out, and disagree.
 */
@Composable
fun MemoryScreen(
    onOpenMemory: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val memories by MemoryStore.memories.collectAsState()
    val vibes by VibeStore.vibes.collectAsState()

    // No Scaffold here: the nav graph already owns one, and nesting a second would apply
    // the window insets twice.
    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader(title = "Memory", onBack = onBack) {
            IconButton(onClick = { onOpenMemory(MemoryStore.add(author = MemoryAuthor.USER)) }) {
                Icon(Icons.Default.Add, contentDescription = "Remember something")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenListPadding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (memories.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "Nothing remembered yet. Tap + to write something the " +
                            "agent should know, or let it write down what it learns " +
                            "while it works.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@LazyColumn
            }

            items(memories, key = { it.id }) { memory: Memory ->
                MemoryRow(
                    memory = memory,
                    vibeName = memory.vibeId?.let { id -> vibes.firstOrNull { it.id == id }?.name },
                    onClick = { onOpenMemory(memory.id) },
                )
            }
        }
    }
}

/**
 * One memory in the list.
 *
 * A memory with nothing in it still gets a row: it is the one the + button just made, and
 * a blank card would look like a rendering fault rather than like somewhere to type.
 */
@Composable
private fun MemoryRow(memory: Memory, vibeName: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = memory.title.ifBlank { "Empty memory" },
                style = MaterialTheme.typography.titleMedium,
                color = if (memory.title.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AuthorBadge(memory.author)
                if (vibeName != null) {
                    Text(
                        text = "for $vibeName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Who wrote this one, as a badge rather than a word in a line of grey text.
 *
 * The agent's own memories are the ones worth stopping on - they are inferences, and they
 * can be wrong - so they carry the mark and the colour, and the user's read as the quiet
 * default.
 */
@Composable
internal fun AuthorBadge(author: MemoryAuthor) {
    val agent = author == MemoryAuthor.AGENT
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (agent) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (agent) {
                Icon(
                    imageVector = WristchIcons.Sparkle,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = if (agent) "Agent" else "You",
                style = MaterialTheme.typography.labelSmall,
                color = if (agent) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
