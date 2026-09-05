package dev.eliaschen.wristch.context

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Asks for the microphone the moment the user asks for it, not before.
 *
 * Unlike [rememberVibeSourceRequest], the thing gated behind this permission is something
 * the user just tapped a button to start - so, once it lands, the caller is told rather than
 * left to notice on the next tap.
 */
@Composable
fun rememberAudioRecordRequest(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) callback.value() }
    return remember(context, launcher) {
        {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) callback.value() else launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
