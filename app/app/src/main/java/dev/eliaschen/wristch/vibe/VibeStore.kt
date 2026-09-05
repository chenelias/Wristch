package dev.eliaschen.wristch.vibe

import android.content.Context
import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The whole file: the vibes, and which of them is in charge when nothing says otherwise. */
@Serializable
private data class VibeBook(
    val vibes: List<Vibe> = emptyList(),
    val defaultId: String? = null,
)

/**
 * Every vibe the user has, in the order they arranged them.
 *
 * Process-wide for the same reason [dev.eliaschen.wristch.history.RunHistory] is: vibes
 * are edited on the Vibe tab and read wherever a run starts - the Agent tab today, a
 * watch gesture later - and neither owns the other's lifetime. Written on every change,
 * because there is no "save" button to hang a write off.
 */
object VibeStore {

    private const val TAG = "WristchVibes"
    private const val FILE_NAME = "vibes.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val writeLock = Mutex()

    private val _vibes = MutableStateFlow<List<Vibe>>(emptyList())
    val vibes: StateFlow<List<Vibe>> = _vibes.asStateFlow()

    private val _defaultId = MutableStateFlow<String?>(null)
    val defaultId: StateFlow<String?> = _defaultId.asStateFlow()

    private var file: File? = null

    /**
     * Points the store at app storage and loads what is there, seeding the starter set the
     * first time - an empty Vibe tab explains nothing, and the three below are the ones
     * the project was described with.
     */
    fun attach(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.filesDir, FILE_NAME)
        file = target
        scope.launch {
            val book = runCatching {
                if (target.exists()) json.decodeFromString<VibeBook>(target.readText())
                else null
            }.getOrElse { error ->
                // A vibe file that cannot be read is not worth taking the app down for;
                // the starter set is a better answer than a blank screen.
                Log.w(TAG, "could not read vibes: ${error.message}")
                null
            } ?: VibeBook(vibes = starterVibes()).let { it.copy(defaultId = it.vibes.first().id) }

            _vibes.value = book.vibes
            _defaultId.value = book.defaultId?.takeIf { id -> book.vibes.any { it.id == id } }
            persist()
        }
    }

    /** Adds an empty vibe and returns its id, for the editor to open on. */
    fun create(): String {
        val vibe = Vibe(
            id = UUID.randomUUID().toString(),
            name = "New vibe",
            accent = _vibes.value.size % ACCENT_COUNT,
            createdAt = System.currentTimeMillis(),
        )
        _vibes.value = _vibes.value + vibe
        if (_defaultId.value == null) _defaultId.value = vibe.id
        save()
        return vibe.id
    }

    fun find(id: String): Vibe? = _vibes.value.firstOrNull { it.id == id }

    fun update(id: String, change: (Vibe) -> Vibe) {
        _vibes.value = _vibes.value.map { if (it.id == id) change(it) else it }
        save()
    }

    /**
     * Forgets a vibe. If it was the default, the first one still switched on takes over -
     * a default pointing at nothing would silently leave watch gestures with no vibe.
     */
    fun delete(id: String) {
        _vibes.value = _vibes.value.filterNot { it.id == id }
        if (_defaultId.value == id) {
            _defaultId.value = _vibes.value.firstOrNull { it.enabled }?.id
        }
        save()
    }

    /** Only a vibe that is switched on can be the default; the radio list shows no others. */
    fun setDefault(id: String) {
        if (_vibes.value.any { it.id == id && it.enabled }) {
            _defaultId.value = id
            save()
        }
    }

    /** The vibe a run starts under when nothing picked one, or null if none is usable. */
    fun default(): Vibe? = _vibes.value.firstOrNull { it.id == _defaultId.value && it.enabled }

    fun setEnabled(id: String, enabled: Boolean) {
        update(id) { it.copy(enabled = enabled) }
        // Switching off the default leaves it pointing at a vibe that will not run, so the
        // choice moves rather than quietly becoming inert.
        if (!enabled && _defaultId.value == id) {
            _defaultId.value = _vibes.value.firstOrNull { it.enabled }?.id
            save()
        }
    }

    private fun save() = scope.launch { persist() }

    private suspend fun persist() {
        val target = file ?: return
        val snapshot = VibeBook(_vibes.value, _defaultId.value)
        writeLock.withLock {
            runCatching { target.writeText(json.encodeToString(snapshot)) }
                .onFailure { Log.w(TAG, "could not write vibes: ${it.message}") }
        }
    }

    /** How many accents the UI palette has; kept here so [create] can cycle through them. */
    internal const val ACCENT_COUNT = 6

    private fun starterVibes(): List<Vibe> {
        val now = System.currentTimeMillis()
        return listOf(
            Vibe(
                id = UUID.randomUUID().toString(),
                name = "School",
                subtitle = "Teachers, classmates, clubs",
                instruction = "Write politely and get to the point. Address teachers by " +
                    "title and surname, propose concrete times rather than asking when " +
                    "they are free, and keep messages to a couple of sentences.",
                sources = setOf(VibeSource.CALENDAR, VibeSource.MESSAGES),
                confirmation = VibeConfirmation.ALWAYS,
                accent = 0,
                createdAt = now,
            ),
            Vibe(
                id = UUID.randomUUID().toString(),
                name = "Family",
                subtitle = "Home, meals, errands",
                instruction = "Talk the way you would at the dinner table - short, warm, " +
                    "no formalities. When a message is about meeting somewhere, include " +
                    "where it is and a map link.",
                sources = setOf(VibeSource.LOCATION, VibeSource.CONTACTS),
                confirmation = VibeConfirmation.RISKY_ONLY,
                accent = 1,
                createdAt = now,
            ),
            Vibe(
                id = UUID.randomUUID().toString(),
                name = "Relationship",
                subtitle = "Just the two of you",
                instruction = "Keep it personal and unhurried. Never make plans on their " +
                    "behalf without saying so, and check the calendar before suggesting a day.",
                sources = setOf(VibeSource.CALENDAR, VibeSource.MESSAGES, VibeSource.NOTES),
                confirmation = VibeConfirmation.ALWAYS,
                accent = 2,
                createdAt = now,
            ),
        )
    }
}
