package dev.eliaschen.wristch.vibe

import kotlinx.serialization.Serializable

/**
 * A piece of the phone a vibe is allowed to pull in before the agent starts working.
 *
 * These are per-vibe rather than per-app on purpose: the whole point of a vibe is that
 * "tell my brother dinner is ready" should quietly carry a map link, while the same
 * sentence sent to a teacher should not carry where you are standing.
 */
enum class VibeSource(
    val label: String,
    val explanation: String,
    /**
     * The same source as the model should hear it, in [Vibe.prompt].
     *
     * Separate from [label] because the two are read by different audiences: the label is
     * a person scanning a settings screen, this is one term in a sentence the model acts
     * on. Translating the screen should not quietly reword the prompt.
     */
    val term: String,
) {
    LOCATION("位置", "手機在哪裡，以及一個地圖連結", "location"),
    CALENDAR("行事曆", "現在前後的行程，含時間與地點", "calendar"),
    MESSAGES("訊息紀錄", "與這個氛圍相關的人的最近對話", "message history"),
    CONTACTS("聯絡人", "任務裡提到的名字是誰", "contacts"),
    MEMORY("記憶", "你和 Agent 雙方記下來的事", "memory"),
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
    ALWAYS("每次都問我", "最安全"),
    RISKY_ONLY("只問風險動作", "打電話、付款、刪除"),
    NEVER("完全自動", "從不詢問"),
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
    val initial: String get() = name.trim().take(1).uppercase().ifBlank { "？" }

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
            // Framed as rules rather than as colour. A vibe is written once and reused
            // for months, so what it says about which app to message in, or how to
            // address someone, is more considered than the sentence typed in a hurry on
            // top of it - and it was being read as a hint and quietly overridden.
            if (instruction.isNotBlank()) {
                add(
                    "Standing instructions for this vibe. Follow them as if they were " +
                        "part of the task itself:\n${instruction.trim()}",
                )
            }
            if (notes.isNotBlank()) {
                add(
                    "Background about the people and things in this vibe. Treat it as " +
                        "true, and prefer it over any assumption of your own:\n" +
                        notes.trim(),
                )
            }
            if (sources.isNotEmpty()) {
                add("You may pull in: " + sources.joinToString { it.term } + ".")
            }
        }
        return if (instruction.isBlank() && notes.isBlank() && sources.isEmpty()) null
        else parts.joinToString("\n\n")
    }
}
