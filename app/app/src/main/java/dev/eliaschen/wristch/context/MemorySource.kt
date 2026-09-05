package dev.eliaschen.wristch.context

import dev.eliaschen.wristch.memory.Memory
import dev.eliaschen.wristch.memory.MemoryAuthor
import dev.eliaschen.wristch.memory.MemoryStore
import dev.eliaschen.wristch.vibe.Vibe

/**
 * What the agent and the user both remember, as the model should hear it.
 *
 * Unlike the other sources this one reads nothing outside the app - memories are written
 * in Wristch, by the user and by the agent - so it needs no permission and always answers
 * immediately.
 *
 * Who wrote a memory is passed through rather than flattened away: a line the user wrote
 * is an instruction, and a line the agent left itself last time is a guess that may since
 * have gone stale, and the model should not weigh them the same.
 */
internal object MemorySource {

    /** Enough to carry standing rules without crowding out the rest of the block. */
    private const val MAX_MEMORIES = 20

    fun snippet(vibe: Vibe): String? {
        val memories = MemoryStore.forVibe(vibe.id).take(MAX_MEMORIES)
        if (memories.isEmpty()) return null
        return "Remembered in Wristch, newest first. A line from the user is an " +
            "instruction; a line the agent left itself is a guess that may have gone " +
            "stale, so prefer the user's where the two disagree:\n" +
            memories.joinToString("\n") { line(it) }
    }

    private fun line(memory: Memory): String {
        val who = when (memory.author) {
            MemoryAuthor.USER -> "from you"
            MemoryAuthor.AGENT -> "noted by the agent on an earlier run"
        }
        // Multi-line memories are indented so a paragraph cannot be read as several.
        val body = memory.text.trim().replace("\n", "\n  ")
        return "- ($who) $body"
    }
}
