package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.history.RunHistory
import dev.eliaschen.wristch.history.RunRecord
import dev.eliaschen.wristch.history.RunStatus
import dev.eliaschen.wristch.history.RunStep
import androidx.compose.ui.graphics.vector.ImageVector
import dev.eliaschen.wristch.ui.icon.WristchIcons
import dev.eliaschen.wristch.ui.shape.WristchShapes
import java.time.format.DateTimeFormatter

private val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")
private val STEP_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * One run, action by action.
 *
 * Read from the store by id rather than passed in whole, so a run that is still going
 * keeps filling this screen in as its steps arrive.
 */
@Composable
fun RunDetailScreen(
    runId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val runs by RunHistory.runs.collectAsState()
    val run = runs.firstOrNull { it.id == runId }
    var confirmDelete by remember { mutableStateOf(false) }

    if (run == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("This run is no longer in the history.")
            BackToHistory(onBack)
        }
        return
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this run?",
            body = "\"${run.goal.ifBlank { "(no goal)" }}\" and its " +
                "${run.steps.size} recorded actions will be removed. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                // Back first: the screen reads the run out of the store by id, and the
                // moment it is gone this composition has nothing left to show.
                onBack()
                RunHistory.delete(runId)
            },
            onDismiss = { confirmDelete = false },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(24.dp),
        // No arrangement spacing: the steps have to touch, or the rail running down the
        // timeline would break into pieces at every gap. Each item carries its own.
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackToHistory(onBack)
                Spacer(Modifier.weight(1f))
                // A run still going has nowhere else to report to, so it cannot be
                // deleted out from under itself.
                if (run.status != RunStatus.RUNNING) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete this run",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item { Box(Modifier.padding(bottom = 16.dp)) { Header(run) } }
        item {
            Text(
                text = if (run.steps.isEmpty()) "No actions recorded" else "Actions",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
        }
        itemsIndexed(run.steps) { index, step ->
            TimelineStep(
                step = step,
                isFirst = index == 0,
                isLast = index == run.steps.lastIndex,
            )
        }
    }
}

/**
 * The way back to the list. The arrow carries the meaning and the label repeats it in
 * words, so the icon is not described twice to a screen reader.
 */
@Composable
private fun BackToHistory(onBack: () -> Unit) {
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
            text = "Back to history",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Header(run: RunRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(run.goal.ifBlank { "(no goal)" }, style = MaterialTheme.typography.headlineSmall)

        val statusText = when (run.status) {
            RunStatus.RUNNING -> "Running"
            RunStatus.DONE -> "Done"
            RunStatus.FAILED -> "Failed"
        }
        val duration = run.durationMs?.let { " - took ${formatDuration(it)}" } ?: ""
        Text(
            text = "$statusText - ${STAMP_FORMAT.format(atZone(run.startedAt))}$duration",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (run.outcome.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (run.status == RunStatus.FAILED) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Outcome", style = MaterialTheme.typography.labelMedium)
                    Text(run.outcome, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * One step, as a stop on a line rather than a box of its own.
 *
 * The rail is drawn per row and runs the full height of it, so consecutive rows join into
 * one continuous line - which is why the list above sets no arrangement spacing. The first
 * and last rows stop their segment at the node, so the line begins and ends at a step
 * instead of trailing off into the padding.
 */
@Composable
private fun TimelineStep(step: RunStep, isFirst: Boolean, isLast: Boolean) {
    val rail = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Intrinsic height is what lets the gutter match the text beside it: without
            // it, fillMaxHeight in a Row that sizes itself to its content measures zero.
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(GUTTER)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2f
                    val node = NODE_CENTER.toPx()
                    drawLine(
                        color = rail,
                        start = Offset(x, if (isFirst) node else 0f),
                        end = Offset(x, if (isLast) node else size.height),
                        strokeWidth = RAIL_WIDTH.toPx(),
                        cap = StrokeCap.Round,
                    )
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            val failed = step.failed
            Surface(
                modifier = Modifier
                    .padding(top = NODE_TOP)
                    .size(NODE_SIZE),
                // A step the device pushed back on gets the alert shape as well as the
                // alert colour, the same pairing the history list uses.
                shape = if (failed) WristchShapes.Alert else WristchShapes.Settled,
                color = if (failed) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = iconFor(step),
                        contentDescription = null,
                        modifier = Modifier.size(NODE_ICON),
                        tint = if (failed) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // The model's own reason leads, because it is the part written for a
                // person; the tool name is the supporting detail, not the headline.
                Text(
                    text = step.intent ?: step.action,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (step.at > 0L) {
                    Text(
                        text = STEP_TIME_FORMAT.format(atZone(step.at)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (step.intent != null) {
                Text(
                    text = step.action,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (step.result.isNotBlank()) {
                Text(
                    text = step.result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The glyph for a step, chosen from the tool the model called.
 *
 * The list is deliberately about what the *device* did rather than what the tool was
 * named: several names map to one gesture, and the names change between model versions
 * (see `ActionDispatcher`, which accepts each set of aliases). Anything unrecognised
 * falls back rather than going blank.
 */
private fun iconFor(step: RunStep): ImageVector = when {
    // A failure outranks the action: what matters at a glance is that this step did not
    // land, not which gesture it was.
    step.failed -> Icons.Default.Warning
    else -> when (step.action) {
        "click", "click_at", "left_click", "double_click" -> WristchIcons.Cursor
        "type", "type_text", "type_text_at" -> Icons.Default.Edit
        "scroll", "scroll_at", "scroll_document", "drag", "drag_and_drop" -> WristchIcons.Swipe
        "wait", "wait_5_seconds" -> WristchIcons.Hourglass
        "go_back", "navigate_back" -> Icons.AutoMirrored.Filled.ArrowBack
        "go_home", "home" -> Icons.Default.Home
        "open_app" -> Icons.AutoMirrored.Filled.ExitToApp
        "list_apps" -> Icons.AutoMirrored.Filled.List
        "take_screenshot", "screenshot" -> Icons.Default.Search
        else -> Icons.Default.Info
    }
}

/**
 * Whether the device pushed back on this step.
 *
 * Read out of the result text, because that is all there is: the result is whatever
 * sentence the dispatcher handed the model, and it was written to be read rather than
 * matched on. Matching is therefore best-effort - it decides an icon, nothing more.
 */
private val RunStep.failed: Boolean
    get() = FAILURE_MARKERS.any { result.contains(it, ignoreCase = true) }

private val FAILURE_MARKERS = listOf(
    "Failed",
    "declined",
    "Unsupported",
    "Missing",
    "was not accepted",
    "No launchable app",
    "refused",
    "focused no input field",
)

private val GUTTER = 36.dp
private val NODE_SIZE = 26.dp
private val NODE_TOP = 1.dp
private val NODE_CENTER = NODE_TOP + NODE_SIZE / 2
private val NODE_ICON = 14.dp
private val RAIL_WIDTH = 2.dp
