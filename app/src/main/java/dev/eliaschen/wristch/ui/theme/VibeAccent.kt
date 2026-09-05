package dev.eliaschen.wristch.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The colours a vibe can be given, so a person can be found in the list by colour before
 * the name is read.
 *
 * Fixed rather than taken from the theme: these have to stay apart from each other and
 * from the wallpaper-derived scheme, and a dynamic palette gives no promise about either.
 * They are only ever drawn as a tinted badge behind an initial, so they are chosen for
 * separation at small size, not for contrast against text.
 */
val VibeAccents: List<Color> = listOf(
    Color(0xFF7C4DFF), // violet
    Color(0xFFE91E8C), // magenta
    Color(0xFFE0611A), // amber
    Color(0xFF13876B), // green
    Color(0xFF1976D2), // blue
    Color(0xFF6D4C41), // brown
)

/** Accents are stored as an index, and an index from an older palette may not exist. */
fun accentOf(index: Int): Color = VibeAccents[index.mod(VibeAccents.size)]
