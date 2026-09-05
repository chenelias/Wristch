package dev.eliaschen.wristch.context

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.eliaschen.wristch.vibe.VibeSource

/**
 * The runtime permissions a source needs before it can answer.
 *
 * Location asks for both accuracies because a vibe only ever wants "which restaurant is
 * this" - coarse is enough, and a user who granted only that should still get a snippet.
 * [VibeSource.NOTES] is missing on purpose: notes live in the vibe itself, so there is no
 * device interface, and nothing to ask for.
 */
val VibeSource.permissions: List<String>
    get() = when (this) {
        VibeSource.LOCATION -> listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        VibeSource.CALENDAR -> listOf(Manifest.permission.READ_CALENDAR)
        VibeSource.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
        VibeSource.MESSAGES -> listOf(Manifest.permission.READ_SMS)
        VibeSource.NOTES -> emptyList()
    }

/**
 * Whether this source can be read right now.
 *
 * Any one of [permissions] is enough: the list is alternatives, not a set to collect.
 */
fun VibeSource.isGranted(context: Context): Boolean {
    val needed = permissions
    if (needed.isEmpty()) return true
    return needed.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Everything still missing for [sources], flattened for a single permission request.
 *
 * A source that is already granted contributes nothing, so switching one source on does
 * not re-ask for the others.
 */
fun missingPermissions(context: Context, sources: Set<VibeSource>): Array<String> =
    sources.filterNot { it.isGranted(context) }
        .flatMap { it.permissions }
        .distinct()
        .toTypedArray()
