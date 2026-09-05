package dev.eliaschen.wristch.memory

import kotlinx.serialization.Serializable

/** Who put a memory there - the difference matters when one of them is wrong. */
enum class MemoryAuthor {
    USER,
    AGENT,
}

/**
 * One thing worth remembering between runs.
 *
 * Memory is shared: the user writes what the agent should know ("Mr. Lin only reads mail
 * in the morning"), and the agent writes back what it learned while working ("the club
 * form is on the third tab"). Both end up in the same place because both are read by both
 * - a memory the agent cannot be corrected on is not worth keeping.
 *
 * What belongs here is what is still true next week. A run's own blow-by-blow lives in
 * [dev.eliaschen.wristch.history.RunHistory]; folding it in here too would cost every
 * later run the tokens to re-read it, and crowd out the facts that earn their place.
 *
 * [vibeId] scopes a memory to one vibe; null means every vibe sees it. These accumulate,
 * unlike a vibe's own `notes` field, which is standing background typed once.
 */
@Serializable
data class Memory(
    val id: String,
    val text: String,
    val author: MemoryAuthor = MemoryAuthor.USER,
    val vibeId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** The first line, for a list that shows one row per memory. */
    val title: String get() = text.trim().lineSequence().firstOrNull()?.take(80).orEmpty()
}
