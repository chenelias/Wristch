package dev.eliaschen.wristch.context

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.eliaschen.wristch.vibe.VibeSource

/**
 * Asks for whatever [VibeSource]s are handed to it and are not granted yet.
 *
 * Permissions are asked for on the screen where the source is switched on, not at startup:
 * a person toggling "Location" on a family vibe has just said why they are being asked,
 * which is the only moment the dialog makes sense.
 *
 * Nothing is reported back - a granted source starts answering on the next run, and a
 * refused one drops out of the context block the same way an empty one does.
 */
@Composable
fun rememberVibeSourceRequest(): (Set<VibeSource>) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    return remember(context, launcher) {
        { sources ->
            // Launching with an empty array throws, and there is nothing to ask anyway.
            val missing = missingPermissions(context, sources)
            if (missing.isNotEmpty()) launcher.launch(missing)
        }
    }
}
