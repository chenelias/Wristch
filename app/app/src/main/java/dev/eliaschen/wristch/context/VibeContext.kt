package dev.eliaschen.wristch.context

import android.content.Context
import android.util.Log
import dev.eliaschen.wristch.vibe.Vibe
import dev.eliaschen.wristch.vibe.VibeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns the sources a vibe has switched on into the paragraph the model reads first.
 *
 * This is the half of a vibe the user cannot type: "叫哥哥來這裡吃飯" only becomes a message
 * with a restaurant and a map link in it because the phone answered where "here" is. Every
 * source is optional and every source is allowed to fail - a run with no location is still
 * a run, so a provider that is switched off, not granted, slow, or simply empty drops out
 * of the block instead of stopping anything.
 */
object VibeContext {

    private const val TAG = "WristchContext"

    /**
     * How long one source gets. A location fix is the slow one, and the run is already
     * paying for a model round trip in parallel; past this the answer is not worth the wait.
     */
    private const val SOURCE_TIMEOUT_MS = 12_000L

    /**
     * Everything [vibe] is allowed to pull in, or null if it came back with nothing.
     *
     * The sources run together: they touch unrelated content providers, and adding a
     * calendar query to a GPS fix should not add to how long the user waits.
     *
     * [exclude] drops sources the caller does not want in this particular block, even
     * though the vibe has them switched on - a run and a screen can want different halves
     * of the same vibe.
     */
    suspend fun gather(
        context: Context,
        vibe: Vibe,
        goal: String,
        exclude: Set<VibeSource> = emptySet(),
    ): String? = coroutineScope {
        val app = context.applicationContext
        val usable = vibe.sources.filter { it !in exclude && it.isGranted(app) }
        if (usable.isEmpty()) return@coroutineScope null

        val snippets = usable.map { source ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                    runCatching { read(app, source, vibe, goal) }.getOrElse { error ->
                        // A source that throws is a source the phone would not answer for
                        // - a revoked permission, a provider that is not there. The run
                        // carries on without it.
                        Log.w(TAG, "${source.label} failed: ${error.message}")
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()

        if (snippets.isEmpty()) null
        else "Context from this phone, gathered just now:\n\n" + snippets.joinToString("\n\n")
    }

    /**
     * [Vibe.prompt] with that context appended - what a run should be started with.
     */
    suspend fun preface(
        context: Context,
        vibe: Vibe,
        goal: String,
        exclude: Set<VibeSource> = emptySet(),
    ): String? {
        val parts = listOfNotNull(vibe.prompt(), gather(context, vibe, goal, exclude))
        return if (parts.isEmpty()) null else parts.joinToString("\n\n")
    }

    /**
     * The sources this vibe wants but cannot read yet, for a screen that wants to offer
     * the permission prompt rather than let a vibe quietly under-deliver.
     */
    fun blocked(context: Context, vibe: Vibe): List<VibeSource> =
        vibe.sources.filterNot { it.isGranted(context) }

    private suspend fun read(
        context: Context,
        source: VibeSource,
        vibe: Vibe,
        goal: String,
    ): String? =
        when (source) {
            VibeSource.LOCATION -> LocationSource.snippet(context)
            VibeSource.CALENDAR -> withContext(Dispatchers.IO) { CalendarSource.snippet(context) }
            VibeSource.CONTACTS ->
                withContext(Dispatchers.IO) { ContactsSource.snippet(context, goal) }
            VibeSource.MESSAGES -> withContext(Dispatchers.IO) { MessageSource.snippet(context) }
            VibeSource.NOTES -> NotesSource.snippet(vibe)
        }
}
