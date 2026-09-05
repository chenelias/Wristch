package dev.eliaschen.wristch.context

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/**
 * The people a task names, resolved to something the phone can be pointed at.
 *
 * Only names that appear in the goal are looked up. Handing over the whole address book
 * would be both larger than any model wants to read and far more than "message my brother"
 * asked for; matching the words in the task keeps this to the two or three people the run
 * is actually about.
 */
internal object ContactsSource {

    private const val MAX_CONTACTS = 5

    /** Single letters and "the"-sized words match half the address book, so they are dropped. */
    private const val MIN_TOKEN = 2

    private val COLUMNS = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
    )

    @SuppressLint("MissingPermission") // The collector only calls this once granted.
    fun snippet(context: Context, goal: String): String? {
        val found = LinkedHashMap<String, String>() // contact id -> line
        for (token in tokens(goal)) {
            if (found.size >= MAX_CONTACTS) break
            val uri = Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                Uri.encode(token),
            )
            context.contentResolver.query(uri, COLUMNS, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && found.size < MAX_CONTACTS) {
                    val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val number = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    found.putIfAbsent(cursor.getString(2), "- $name: $number")
                }
            }
        }

        if (found.isEmpty()) return null
        return "People named in this task:\n" + found.values.joinToString("\n")
    }

    /**
     * The words of [goal] worth looking up.
     *
     * CJK names run together without spaces, so a Chinese task splits into one long token
     * that matches nothing; the two- and three-character slices cover the names inside it.
     */
    internal fun tokens(goal: String): List<String> {
        val words = goal.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= MIN_TOKEN }
        return words.flatMap { word ->
            if (word.none { it.code > 0x2E80 }) listOf(word)
            else (2..3).flatMap { size -> word.windowed(size, 1, partialWindows = false) }
        }.distinct()
    }
}
