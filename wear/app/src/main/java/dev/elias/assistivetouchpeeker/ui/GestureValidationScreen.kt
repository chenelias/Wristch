package dev.elias.assistivetouchpeeker.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SuccessConfirmationDialog
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.curvedText
import androidx.wear.input.RemoteInputIntentHelper
import android.app.RemoteInput
import dev.elias.assistivetouchpeeker.GestureViewModel
import dev.elias.assistivetouchpeeker.R
import dev.elias.assistivetouchpeeker.ml.DetectedGesture
import dev.elias.assistivetouchpeeker.ml.GestureClass
import kotlin.math.roundToInt

/**
 * The app's single screen: a [ScalingLazyColumn] listing every gesture - the two built-in
 * ones (Clench, Pinch) plus every enrolled custom gesture - each with a live confidence %
 * on the right, custom ones with a delete action; an "Add gesture" row to enroll a new
 * one; and a [SuccessConfirmationDialog] + vibration whenever [GestureViewModel] reports a
 * detection (built-in or custom).
 */
@Composable
fun GestureValidationScreen(onAddGesture: () -> Unit, viewModel: GestureViewModel = viewModel()) {
    val probabilities by viewModel.probabilities.collectAsState()
    val detectedGesture by viewModel.detectedGesture.collectAsState()
    val customGestures by viewModel.customGestures.collectAsState()
    val customGestureSimilarities by viewModel.customGestureSimilarities.collectAsState()
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var pendingRenameId by remember { mutableStateOf<String?>(null) }
    val listState = rememberScalingLazyListState()

    // Tapping a custom gesture opens the system keyboard/voice input to rename it.
    val renameLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val newName = RemoteInput.getResultsFromIntent(result.data)?.getCharSequence(RENAME_INPUT_KEY)?.toString()
        val target = pendingRenameId
        if (target != null && !newName.isNullOrBlank()) {
            viewModel.renameCustomGesture(target, newName.trim())
        }
        pendingRenameId = null
    }
    val renameLabel = stringResource(R.string.rename_gesture)

    KeepScreenOn()

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onAddGesture) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Text(stringResource(R.string.add_gesture))
            }
        },
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.prompt),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item { GestureRow(stringResource(R.string.class_double_clench), probabilities.doubleClench) }
            item { GestureRow(stringResource(R.string.class_double_pinch), probabilities.doublePinch) }
            items(customGestures, key = { it.id }) { gesture ->
                GestureRow(
                    name = gesture.name,
                    confidence = customGestureSimilarities[gesture.id] ?: 0f,
                    onDeleteClick = { pendingDeleteId = gesture.id },
                    onRenameClick = {
                        pendingRenameId = gesture.id
                        renameLauncher.launch(renameIntent(renameLabel, gesture.name))
                    },
                )
            }
        }
    }

    val deleteTargetId = pendingDeleteId
    AlertDialog(
        visible = deleteTargetId != null,
        onDismissRequest = { pendingDeleteId = null },
        title = { Text(stringResource(R.string.delete_gesture_confirm)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    deleteTargetId?.let(viewModel::deleteCustomGesture)
                    pendingDeleteId = null
                },
            )
        },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { pendingDeleteId = null }) },
    )

    // Named explicitly here (rather than just curvedText, which is small rim text easy to
    // miss) so the detected gesture - built-in or custom - is unmistakable on-screen.
    SuccessConfirmationDialog(
        visible = detectedGesture != null,
        onDismissRequest = viewModel::acknowledgeDetection,
        curvedText = { curvedText(detectedGesture?.displayName().orEmpty()) },
        durationMillis = CONFIRMATION_DURATION_MS,
    )
}

private const val RENAME_INPUT_KEY = "gesture_name"

/** The system keyboard/voice-input intent used to rename a gesture, pre-filled with its current name. */
private fun renameIntent(label: String, currentName: String): Intent =
    RemoteInputIntentHelper.createActionRemoteInputIntent().also { intent ->
        RemoteInputIntentHelper.putRemoteInputsExtra(
            intent,
            listOf(RemoteInput.Builder(RENAME_INPUT_KEY).setLabel(label).build()),
        )
        RemoteInputIntentHelper.putTitleExtra(intent, currentName)
    }

/** One row: a gesture's name, its live confidence % on the right, and optional rename/delete actions. */
@Composable
private fun GestureRow(
    name: String,
    confidence: Float,
    onDeleteClick: (() -> Unit)? = null,
    onRenameClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onRenameClick != null) Modifier.clickable(onClick = onRenameClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        Text(text = "${(confidence * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
        if (onDeleteClick != null) {
            IconButton(onClick = onDeleteClick) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(R.string.delete_gesture_confirm))
            }
        }
    }
}

/** Keeps the screen on for as long as this composable is in the composition. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

fun DetectedGesture.displayName(): String = when (this) {
    is DetectedGesture.Base -> when (gestureClass) {
        GestureClass.DOUBLE_CLENCH -> "Double Clench"
        GestureClass.DOUBLE_PINCH -> "Double Pinch"
        GestureClass.NULL -> ""
    }
    is DetectedGesture.Custom -> name
}
