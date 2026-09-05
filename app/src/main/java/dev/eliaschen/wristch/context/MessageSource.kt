package dev.eliaschen.wristch.context

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Telephony
import android.text.format.DateUtils

/**
 * The tail of the recent SMS threads, so a reply can sound like it belongs to one.
 *
 * Reading messages is the most intrusive thing a vibe can do, so this stays deliberately
 * shallow: a handful of threads, a handful of lines each, newest last so the model reads
 * them in the order they were said.
 *
 * Only the platform SMS store is available to a normal app - chat apps keep their history
 * to themselves - so a vibe whose people are on another messenger gets nothing here, and
 * the agent falls back to reading that app's screen.
 */
internal object MessageSource {

    private const val MAX_THREADS = 4
    private const val MAX_PER_THREAD = 6

    /** Enough rows to fill [MAX_THREADS] even when one conversation dominates the inbox. */
    private const val SCAN_LIMIT = 120

    private val COLUMNS = arrayOf(
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
    )

    @SuppressLint("MissingPermission") // The collector only calls this once granted.
    fun snippet(context: Context): String? {
        val threads = LinkedHashMap<String, MutableList<String>>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            COLUMNS,
            null,
            null,
            "${Telephony.Sms.DATE} DESC LIMIT $SCAN_LIMIT",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val address = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                val body = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val date = cursor.getLong(2)
                val outgoing = cursor.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT
                if (address !in threads && threads.size >= MAX_THREADS) continue
                val lines = threads.getOrPut(address) { mutableListOf() }
                if (lines.size >= MAX_PER_THREAD) continue
                val when_ = DateUtils.getRelativeTimeSpanString(
                    date,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                )
                lines.add("  ${if (outgoing) "you" else address} ($when_): ${body.trim()}")
            }
        }

        if (threads.isEmpty()) return null
        return buildString {
            append("Recent text messages:")
            threads.forEach { (address, lines) ->
                append("\n- with $address:\n")
                // The scan walked backwards through time; a conversation reads forwards.
                append(lines.asReversed().joinToString("\n"))
            }
        }
    }
}
