package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import dev.eliaschen.wristch.history.RunHistory
import dev.eliaschen.wristch.history.RunRecord
import dev.eliaschen.wristch.memory.Memory
import dev.eliaschen.wristch.memory.MemoryAuthor
import dev.eliaschen.wristch.memory.MemoryStore
import dev.eliaschen.wristch.ui.component.ScreenGutter
import dev.eliaschen.wristch.ui.component.screenListPadding
import dev.eliaschen.wristch.vibe.VibeStore

/** Below this a query matches too much to be worth listing. */
private const val MIN_QUERY = 2

/**
 * One field over everything the app remembers: past runs, and the shared notebook.
 *
 * The two are searched together rather than behind a filter, because the thing being
 * looked for is usually a fact - "what was that form called" - and the person looking does
 * not know whether they wrote it down or the agent did.
 *
 * A run matches on its title, its goal, its outcome and the steps it took, so it is
 * findable by what it did as well as by what it was asked for.
 */
@Composable
fun SearchScreen(
    onOpenRun: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val runs by RunHistory.runs.collectAsState()
    val memories by MemoryStore.memories.collectAsState()
    var query by remember { mutableStateOf("") }

    val needle = query.trim().lowercase()
    val ready = needle.length >= MIN_QUERY
    val runHits = if (!ready) emptyList() else runs.filter { it.matches(needle) }
    val memoryHits =
        if (!ready) emptyList() else memories.filter { it.text.lowercase().contains(needle) }

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Opened from a bar that looks like a field, so the caret has to be where the finger
    // already was - anything else reads as a tap that did not take.
    LaunchedEffect(Unit) { focus.requestFocus() }

    // No Scaffold here: the nav graph already owns one, and nesting a second would apply
    // the window insets twice.
    // Results end where the keyboard begins: a match hidden behind it is a match the
    // person has to dismiss the keyboard to find.
    Column(modifier = modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回上一頁",
                    modifier = Modifier.size(18.dp),
                )
            }
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                placeholder = { Text("搜尋紀錄與記憶") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "清除搜尋")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                // The pill from the toolbar, carried into the screen it opened: an
                // underline here would make it look like a different control.
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
        }

        if (!ready) {
            Hint(
                if (query.isBlank()) "搜尋過去的執行紀錄，以及所有記憶。"
                else "再多打幾個字，目前的關鍵字太短了。",
            )
            return@Column
        }

        if (runHits.isEmpty() && memoryHits.isEmpty()) {
            Hint("找不到符合「${query.trim()}」的內容。")
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenListPadding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (runHits.isNotEmpty()) {
                item(key = "runs-header") { SearchHeader("${runHits.size} 筆紀錄") }
                items(runHits, key = { it.id }) { run ->
                    RunRow(run = run, onClick = { onOpenRun(run.id) })
                }
            }
            if (memoryHits.isNotEmpty()) {
                item(key = "memory-header") { SearchHeader("${memoryHits.size} 筆記憶") }
                items(memoryHits, key = { it.id }) { memory -> MemoryRow(memory) }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 8.dp),
    )
}

@Composable
private fun SearchHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/**
 * A memory as a result: the text itself, over who wrote it and which vibe can see it.
 *
 * Not clickable, because there is nowhere deeper for it to go - the whole of it is
 * already here.
 */
@Composable
private fun MemoryRow(memory: Memory) {
    val vibes by VibeStore.vibes.collectAsState()
    val scope = memory.vibeId?.let { id -> vibes.firstOrNull { it.id == id }?.name } ?: "所有氛圍"
    val author = if (memory.author == MemoryAuthor.AGENT) "Agent" else "你"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = memory.text.trim(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$author - $scope",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun RunRecord.matches(needle: String): Boolean =
    title.lowercase().contains(needle) ||
    goal.lowercase().contains(needle) ||
        outcome.lowercase().contains(needle) ||
        steps.any {
            it.action.lowercase().contains(needle) ||
                it.intent?.lowercase()?.contains(needle) == true ||
                it.result.lowercase().contains(needle)
        }
