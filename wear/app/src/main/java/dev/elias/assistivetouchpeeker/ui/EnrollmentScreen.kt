package dev.elias.assistivetouchpeeker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SuccessConfirmationDialog
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.curvedText
import dev.elias.assistivetouchpeeker.GestureViewModel
import dev.elias.assistivetouchpeeker.R
import dev.elias.assistivetouchpeeker.detection.EnrollmentConfig
import dev.elias.assistivetouchpeeker.detection.EnrollmentOutcome
import kotlinx.coroutines.delay

private const val COUNTDOWN_MS = 1_500L
internal const val CONFIRMATION_DURATION_MS = 1_000L

private sealed interface EnrollmentUiState {
    data class Recording(val repsCaptured: Int, val countingDown: Boolean) : EnrollmentUiState
    data object Processing : EnrollmentUiState
    data object Success : EnrollmentUiState
    data class Failed(val outcome: EnrollmentOutcome) : EnrollmentUiState
}

/**
 * Drives an [dev.elias.assistivetouchpeeker.detection.EnrollmentSession] through
 * [EnrollmentConfig.REPS_REQUIRED] reps - a countdown then an auto-capture per rep, no
 * manual start/stop - then shows the result. Pauses the continuous base detector for as
 * long as this screen is on-screen, so it can't fire (vibrate/dialog) over enrollment.
 */
@Composable
fun EnrollmentScreen(onDone: () -> Unit, viewModel: GestureViewModel = viewModel()) {
    DisposableEffect(Unit) {
        viewModel.pauseDetection()
        onDispose { viewModel.resumeDetection() }
    }

    val session = remember { viewModel.startEnrollment() }
    var uiState by remember { mutableStateOf<EnrollmentUiState>(EnrollmentUiState.Recording(0, countingDown = true)) }

    LaunchedEffect(Unit) {
        repeat(EnrollmentConfig.REPS_REQUIRED) {
            uiState = EnrollmentUiState.Recording(session.capturedReps, countingDown = true)
            delay(COUNTDOWN_MS)

            // Double pulse = start performing now; long pulse = window closed, stop.
            uiState = EnrollmentUiState.Recording(session.capturedReps, countingDown = false)
            viewModel.vibrateRecordingStart()
            delay(EnrollmentConfig.CAPTURE_WINDOW_MS)
            viewModel.vibrateRecordingStop()

            val captured = session.captureRep()
            if (!captured) {
                uiState = EnrollmentUiState.Failed(EnrollmentOutcome.InsufficientData)
                return@LaunchedEffect
            }
        }
        uiState = EnrollmentUiState.Processing
        val outcome = session.finish()
        uiState = if (outcome is EnrollmentOutcome.Success) {
            viewModel.saveCustomGesture(viewModel.nextAutoGestureName(), outcome.head)
            EnrollmentUiState.Success
        } else {
            EnrollmentUiState.Failed(outcome)
        }
    }

    ScreenScaffold { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (val state = uiState) {
                is EnrollmentUiState.Recording -> {
                    Text(
                        text = stringResource(if (state.countingDown) R.string.enroll_get_ready else R.string.enroll_go),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(stringResource(R.string.enroll_rep_progress, state.repsCaptured + 1, EnrollmentConfig.REPS_REQUIRED))
                }
                EnrollmentUiState.Processing -> Text(stringResource(R.string.enroll_go))
                EnrollmentUiState.Success -> Unit
                is EnrollmentUiState.Failed -> {
                    Text(text = failureMessage(state.outcome), textAlign = TextAlign.Center)
                    Button(onClick = onDone) { Text(stringResource(R.string.back)) }
                }
            }
        }
    }

    val savedText = stringResource(R.string.enroll_saved)
    SuccessConfirmationDialog(
        visible = uiState == EnrollmentUiState.Success,
        onDismissRequest = onDone,
        curvedText = { curvedText(savedText) },
        durationMillis = CONFIRMATION_DURATION_MS,
    )
}

@Composable
private fun failureMessage(outcome: EnrollmentOutcome): String = when (outcome) {
    EnrollmentOutcome.InsufficientData -> stringResource(R.string.enroll_failed_insufficient_data)
    EnrollmentOutcome.TooInconsistent -> stringResource(R.string.enroll_failed_too_inconsistent)
    EnrollmentOutcome.ConfusedWithDailyActivity -> stringResource(R.string.enroll_failed_confused_with_daily_activity)
    is EnrollmentOutcome.SimilarToExisting -> stringResource(R.string.enroll_failed_similar_to_existing)
    is EnrollmentOutcome.Success -> ""
}
