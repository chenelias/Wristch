package dev.eliaschen.wristch.history

import kotlinx.serialization.Serializable

/** How a run ended, or that it has not ended yet. */
enum class RunStatus {
    RUNNING,
    DONE,
    FAILED,
}

/**
 * One action the agent took, as it was reported while the run was going.
 *
 * [action] is the tool the model called, [intent] the one-line reason it gave for calling
 * it, and [result] what the device answered. The reason is kept separate from the rest
 * because it is the only part written for a person to read.
 */
@Serializable
data class RunStep(
    val action: String,
    val intent: String? = null,
    val result: String = "",
    val at: Long = 0L,
)

/**
 * A single agent run: what it was asked to do, what it did, and how it ended.
 *
 * [goal] is the sentence that was typed, kept word for word because it is the instruction
 * - it is what a run is sent again with. [title] is the short name the model writes for
 * it, which is what the run is listed under: a typed goal is a paragraph as often as it is
 * a phrase, and a list of paragraphs cannot be scanned. It arrives a moment after the run
 * starts and is blank until then, so read runs through [label] rather than either field.
 */
@Serializable
data class RunRecord(
    val id: String,
    val goal: String,
    val startedAt: Long,
    val title: String = "",
    val endedAt: Long? = null,
    val status: RunStatus = RunStatus.RUNNING,
    val outcome: String = "",
    val steps: List<RunStep> = emptyList(),
) {
    /** Null while the run is still going, so callers cannot show a duration that grows. */
    val durationMs: Long? get() = endedAt?.minus(startedAt)

    /**
     * What to call this run on screen: its title, or the goal until one has been written.
     * A run titled in the second after it starts would otherwise appear in the list with
     * no name at all.
     */
    val label: String get() = title.ifBlank { goal }
}

/**
 * Splits a progress line back into its parts.
 *
 * The agent reports steps as `action (intent) -> result`, which is fine to print and
 * useless to lay out; the detail screen needs the three pieces on their own.
 */
internal fun parseStepLine(line: String, at: Long): RunStep {
    val arrow = line.indexOf(" -> ")
    val head = if (arrow >= 0) line.substring(0, arrow) else line
    val result = if (arrow >= 0) line.substring(arrow + 4) else ""

    val open = head.indexOf(" (")
    return if (open >= 0 && head.endsWith(")")) {
        RunStep(
            action = head.substring(0, open),
            intent = head.substring(open + 2, head.length - 1),
            result = result,
            at = at,
        )
    } else {
        RunStep(action = head, result = result, at = at)
    }
}
