package dev.eliaschen.wristch.vibe

import kotlinx.serialization.Serializable

/**
 * A piece of the phone a vibe is allowed to pull in before the agent starts working.
 *
 * These are per-vibe rather than per-app on purpose: the whole point of a vibe is that
 * "tell my brother dinner is ready" should quietly carry a map link, while the same
 * sentence sent to a teacher should not carry where you are standing.
 */
enum class VibeSource(val label: String, val explanation: String) {
    LOCATION("Location", "Where the phone is, and a Maps link for it"),
    CALENDAR("Calendar", "Events around now, for time and place"),
    MESSAGES("Message history", "Recent threads with the people this vibe is about"),
    CONTACTS("Contacts", "Who the names in a task refer to"),
    NOTES("Notes", "Your own notes, as background the model can quote"),
    ;
}

/**
 * How much the agent has to ask before it acts, while this vibe is the one in charge.
 *
 * A vibe for messaging family can run to the end untouched; one that books things or
 * spends money should stop and show its work first. Kept per vibe rather than app-wide
 * because that difference is exactly what a vibe is for.
 */
enum class VibeConfirmation(val label: String, val explanation: String) {
    ALWAYS("Ask me every time", "Safest"),
    RISKY_ONLY("Only risky actions", "Calling, paying, deleting"),
    NEVER("Fully automatic", "Never asks"),
    ;
}

/**
 * One customised way of working: a person, or a corner of life.
 *
 * [instruction] is the part written for the model - tone, wording, standing rules - and
 * [notes] is background it may quote. They are separate fields because they are separate
 * jobs, and because a person editing "talk to Mr. Lin formally" should not have to scroll
 * past a paragraph of facts about Mr. Lin to find it.
 */
@Serializable
data class Vibe(
    val id: String,
    val name: String,
    val subtitle: String = "",
    val instruction: String = "",
    val notes: String = "",
    val sources: Set<VibeSource> = emptySet(),
    val confirmation: VibeConfirmation = VibeConfirmation.ALWAYS,
    /** Off means it stays here but is not offered to a watch gesture or the Agent tab. */
    val enabled: Boolean = true,
    /** Index into the UI's accent palette - stored as a number so the palette can change. */
    val accent: Int = 0,
    val createdAt: Long = 0L,
) {

    /** The initial the avatar shows; blank names still have to draw something. */
    val initial: String get() = name.trim().take(1).uppercase().ifBlank { "?" }

    /**
     * This vibe as the model should hear it, or null if there is nothing to say.
     *
     * The screen is the only thing that builds vibes today; this is what the agent will
     * prepend to a goal once a run can be started under one.
     */
    fun prompt(): String? {
        val parts = buildList {
            add("You are acting in the \"$name\" vibe.")
            if (subtitle.isNotBlank()) add("It covers: $subtitle.")
            if (instruction.isNotBlank()) add(instruction.trim())
            if (notes.isNotBlank()) add("Background you may use:\n${notes.trim()}")
            if (sources.isNotEmpty()) {
                add("You may pull in: " + sources.joinToString { it.label.lowercase() } + ".")
            }
        }
        return if (instruction.isBlank() && notes.isBlank() && sources.isEmpty()) null
        else parts.joinToString("\n\n")
    }
}
