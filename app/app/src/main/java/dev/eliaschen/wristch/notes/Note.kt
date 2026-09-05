package dev.eliaschen.wristch.notes

import kotlinx.serialization.Serializable

/** Who put a note there - the difference matters when one of them is wrong. */
enum class NoteAuthor {
    USER,
    AGENT,
}

/**
 * One thing worth remembering between runs.
 *
 * The notebook is shared: the user writes what the agent should know ("Mr. Lin only reads
 * mail in the morning"), and the agent writes back what it learned while working ("the
 * club form is on the third tab"). Both end up in the same place because both are read by
 * both - a note the agent cannot be corrected on is not worth keeping.
 *
 * [vibeId] scopes a note to one vibe; null means every vibe sees it. Notes are separate
 * from a vibe's own `notes` field, which is standing background typed once - these
 * accumulate.
 */
@Serializable
data class Note(
    val id: String,
    val text: String,
    val author: NoteAuthor = NoteAuthor.USER,
    val vibeId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** The first line, for a list that shows one row per note. */
    val title: String get() = text.trim().lineSequence().firstOrNull()?.take(80).orEmpty()
}
