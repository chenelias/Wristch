package dev.eliaschen.wristch.context

import dev.eliaschen.wristch.notes.Note
import dev.eliaschen.wristch.notes.NoteAuthor
import dev.eliaschen.wristch.notes.NoteStore
import dev.eliaschen.wristch.vibe.Vibe

/**
 * The shared notebook, as the model should hear it.
 *
 * Unlike the other sources this one reads nothing outside the app - notes are written in
 * Wristch, by the user and by the agent - so it needs no permission and always answers
 * immediately.
 *
 * Who wrote a note is passed through rather than flattened away: a line the user wrote is
 * an instruction, and a line the agent left itself last time is a guess that may since
 * have gone stale, and the model should not weigh them the same.
 */
internal object NotesSource {

    /** Enough to carry standing rules without crowding out the rest of the block. */
    private const val MAX_NOTES = 20

    fun snippet(vibe: Vibe): String? {
        val notes = NoteStore.forVibe(vibe.id).take(MAX_NOTES)
        if (notes.isEmpty()) return null
        return "Notes kept in Wristch:\n" + notes.joinToString("\n") { line(it) }
    }

    private fun line(note: Note): String {
        val who = when (note.author) {
            NoteAuthor.USER -> "from you"
            NoteAuthor.AGENT -> "noted by the agent on an earlier run"
        }
        // Multi-line notes are indented so a paragraph cannot be read as several notes.
        val body = note.text.trim().replace("\n", "\n  ")
        return "- ($who) $body"
    }
}
