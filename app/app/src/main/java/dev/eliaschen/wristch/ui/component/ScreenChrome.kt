package dev.eliaschen.wristch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The gutter every screen's content sits in, so a title, a paragraph and a list all line
 * up on the same left edge.
 */
val ScreenGutter = 24.dp

/**
 * The one header every non-home screen wears: back, title, and whatever that screen can
 * do to what is under it.
 *
 * Each screen used to space this by hand, which is why no two of them started at the same
 * height. The caller supplies only the parts that differ.
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to home",
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/**
 * Content padding for a screen's main list.
 *
 * Only the *bottom* window inset belongs here: the screen already carries the status bar
 * on its outer column, and taking `safeDrawing` whole - as every list on this screen once
 * did - pays for that inset a second time, which is where the empty band between the
 * chips and the first run came from.
 */
@Composable
fun screenListPadding(top: Dp = 0.dp): PaddingValues =
    WindowInsets.safeDrawing
        .only(WindowInsetsSides.Bottom)
        .add(
            WindowInsets(
                left = ScreenGutter,
                right = ScreenGutter,
                top = top,
                bottom = ScreenGutter,
            )
        )
        .asPaddingValues()

/**
 * A short fade from the background down to nothing, drawn over the top of a scrolling
 * list.
 *
 * It gives the list somewhere to go as it passes under whatever is pinned above it -
 * chips, a header - instead of rows appearing to be cut off against a hard edge. Place it
 * as the last child of a [Box] holding the list.
 */
@Composable
fun TopScrollFade(modifier: Modifier = Modifier, height: Dp = 16.dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.background, Color.Transparent),
                )
            )
    )
}
