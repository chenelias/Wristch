package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.history.RunHistory
import dev.eliaschen.wristch.history.RunRecord
import dev.eliaschen.wristch.history.RunStatus
import dev.eliaschen.wristch.ui.shape.WristchShapes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The chips across the top: which runs the list is showing.
 *
 * No "Running" chip, because a run in flight is never something to go looking for - it is
 * pinned above the list for as long as it lasts.
 */
private enum class HistoryFilter(val label: String) {
    ALL("All"),
    DONE("Done"),
    FAILED("Failed"),
    ;

    fun accepts(run: RunRecord): Boolean = when (this) {
        ALL -> true
        DONE -> run.status == RunStatus.DONE
        FAILED -> run.status == RunStatus.FAILED
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d")

/**
 * Every run the agent has done, newest first and grouped by the day it started, with
 * anything still going pinned above the rest.
 *
 * Tapping a run opens [RunDetailScreen], which is where the individual actions live: the
 * list answers "what did I ask for and did it work", the detail answers "what did it
 * actually do".
 */
@Composable
fun HistoryScreen(
    onOpenRun: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val runs by RunHistory.runs.collectAsState()
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }

    val visible = runs.filter { filter.accepts(it) }
    // A run in flight is the one thing on this screen that is about now rather than about
    // the past, so it sits above the days instead of inside yesterday. Only under "All":
    // asking for finished runs is asking for the ones that are not this.
    // No pinned "running" item — show history as a single flat list.
    val history = visible

    // No Scaffold here: the nav graph already owns one, and nesting a second would
    // apply the window insets twice.
    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        var confirmClear by remember { mutableStateOf(false) }
        val finished = runs.count { it.status != RunStatus.RUNNING }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp),
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
                text = "History",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            // Nothing to clear is not a disabled button, it is no button: the control
            // appears only when there is finished history behind it.
            if (finished > 0) {
                IconButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete all history")
                }
            }
        }

        if (confirmClear) {
            ConfirmDialog(
                title = "Delete all history?",
                body = if (finished == runs.size) {
                    "This removes all $finished runs. It cannot be undone."
                } else {
                    "This removes $finished finished runs and cannot be undone. " +
                        "Anything still running is kept."
                },
                confirmLabel = "Delete all",
                onConfirm = {
                    RunHistory.clearFinished()
                    confirmClear = false
                },
                onDismiss = { confirmClear = false },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HistoryFilter.entries.forEach { option ->
                FilterChip(
                    selected = option == filter,
                    onClick = { filter = option },
                    label = { Text(option.label) },
                )
            }
        }

        if (visible.isEmpty()) {
            Text(
                text = if (runs.isEmpty()) {
                    "No runs yet. Anything you start from the agent button shows up here."
                } else {
                    "No ${filter.label.lowercase()} runs."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = WindowInsets.safeDrawing
                .add(WindowInsets(left = 24.dp, right = 24.dp, bottom = 24.dp))
                .asPaddingValues(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Grouped by day, with the header carried on the first run of each day rather
            // than as its own item: the list is one flat list of runs, so a sticky-header
            // API would have to be told about a grouping the data does not have.

            itemsIndexed(history, key = { _, run -> run.id }) { index, run ->
                val previous = history.getOrNull(index - 1)
                if (previous == null || dayOf(previous) != dayOf(run)) {
                    Text(
                        text = dayLabel(dayOf(run)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                RunRow(run = run, onClick = { onOpenRun(run.id) })
            }
        }
    }
}

/** Shared with [HomeScreen], which shows the same row for the last few runs. */
@Composable
internal fun RunRow(run: RunRecord, onClick: () -> Unit) {
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
//            Avatar(run)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = run.label.ifBlank { "(no goal)" },
                    style = MaterialTheme.typography.titleSmall,
                    // Two lines, because a name that says which message to whom does not
                    // fit on one - and the half that gets cut is the half that identifies
                    // the run.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summaryOf(run),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(run.status),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = TIME_FORMAT.format(atZone(run.startedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The goal's first letter in a status-coloured badge, so a run is findable at a glance.
 *
 * The badge's *shape* carries the status as well as its colour - a run still going is a
 * restless many-pointed cookie, a finished one has settled into a rounded hexagon, a
 * failed one has corners. Colour alone would leave the three indistinguishable to anyone
 * who cannot separate them.
 */
@Composable
private fun Avatar(run: RunRecord) {
    val background = statusColor(run.status)
    Surface(
        modifier = Modifier.size(36.dp),
        shape = when (run.status) {
            RunStatus.RUNNING -> WristchShapes.Busy
            RunStatus.DONE -> WristchShapes.Settled
            RunStatus.FAILED -> WristchShapes.Alert
        },
        color = background.copy(alpha = 0.18f),
    ) {

    }
}

@Composable
private fun statusColor(status: RunStatus): Color = when (status) {
    RunStatus.RUNNING -> MaterialTheme.colorScheme.primary
    RunStatus.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
    RunStatus.FAILED -> MaterialTheme.colorScheme.error
}

/** The one line under the goal: what happened, and how much work it took. */
private fun summaryOf(run: RunRecord): String {
    val steps = run.steps.size
    val stepText = if (steps == 1) "1 step" else "$steps steps"
    return when (run.status) {
        RunStatus.RUNNING -> "Running - $stepText so far"
        RunStatus.DONE -> {
            val took = run.durationMs?.let { " - ${formatDuration(it)}" } ?: ""
            "Done - $stepText$took"
        }
        RunStatus.FAILED -> "Failed - ${run.outcome.ifBlank { "no outcome reported" }}"
    }
}

/**
 * A duration as a person would say it: "12s", "15min 20s", "1h 5min".
 *
 * Rounded to whole units and never more than two of them - the point is how long a run
 * took, and a third unit only makes that harder to read at a glance. A run of under a
 * second still reads as "1s" rather than "0s", because zero looks like missing data.
 */
/**
 * The one confirmation this app asks for.
 *
 * Deleting history is the only destructive thing either screen can do, and it is
 * unrecoverable - the store keeps no undo and the file is rewritten immediately.
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun formatDuration(millis: Long): String {
    val totalSeconds = ((millis + 500L) / 1000L).coerceAtLeast(1L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}min"
        hours > 0 -> "${hours}h"
        minutes > 0 && seconds > 0 -> "${minutes}min ${seconds}s"
        minutes > 0 -> "${minutes}min"
        else -> "${seconds}s"
    }
}

internal fun atZone(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

internal fun dayOf(run: RunRecord): LocalDate = atZone(run.startedAt).toLocalDate()

/** Shared with [HomeScreen], which heads its list with the day the runs are from. */
internal fun dayLabel(day: LocalDate): String = when (day) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> DATE_FORMAT.format(day)
}
