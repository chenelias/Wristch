package dev.eliaschen.wristch.context

import android.annotation.SuppressLint
import android.content.Context
import android.provider.CalendarContract
import android.text.format.DateUtils
import java.util.concurrent.TimeUnit

/**
 * What the calendar says about the next few days.
 *
 * Read through [CalendarContract.Instances] rather than the events table so a weekly class
 * arrives as the three occurrences that are actually coming up, which is what "propose a
 * time" needs, instead of one recurrence rule the model has to unroll itself.
 */
internal object CalendarSource {

    /** Far enough to answer "when are you free this week", short enough to stay readable. */
    private val WINDOW_MS = TimeUnit.DAYS.toMillis(7)

    /** A little before now, so a meeting already under way still counts as context. */
    private val LOOKBACK_MS = TimeUnit.HOURS.toMillis(2)

    private const val MAX_EVENTS = 20

    private val COLUMNS = arrayOf(
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.EVENT_LOCATION,
    )

    @SuppressLint("MissingPermission") // The collector only calls this once granted.
    fun snippet(context: Context, now: Long = System.currentTimeMillis()): String? {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath((now - LOOKBACK_MS).toString())
            .appendPath((now + WINDOW_MS).toString())
            .build()

        val lines = context.contentResolver.query(
            uri,
            COLUMNS,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < MAX_EVENTS) {
                    val title = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "(untitled)"
                    val begin = cursor.getLong(1)
                    val end = cursor.getLong(2)
                    val allDay = cursor.getInt(3) == 1
                    val place = cursor.getString(4)?.takeIf { it.isNotBlank() }
                    add(
                        buildString {
                            append("- ")
                            append(title)
                            append(": ")
                            append(when {
                                allDay -> DateUtils.formatDateTime(
                                    context,
                                    begin,
                                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY,
                                ) + " (all day)"
                                else -> DateUtils.formatDateRange(
                                    context,
                                    begin,
                                    end,
                                    DateUtils.FORMAT_SHOW_TIME or
                                        DateUtils.FORMAT_SHOW_DATE or
                                        DateUtils.FORMAT_SHOW_WEEKDAY,
                                )
                            })
                            if (place != null) append(" at $place")
                        },
                    )
                }
            }
        }.orEmpty()

        if (lines.isEmpty()) return null
        return "Calendar for the next week:\n" + lines.joinToString("\n")
    }
}
