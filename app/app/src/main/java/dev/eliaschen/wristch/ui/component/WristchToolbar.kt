package dev.eliaschen.wristch.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsEndWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.ui.icon.WristchIcons
import dev.eliaschen.wristch.ui.shape.WristchShapes

/** Tall enough to be hit without aiming, short enough to leave the list visible behind it. */
private val BAR_HEIGHT = 50.dp

/** The action button is square and a little taller than the bar, so it reads as the primary one. */
private val ACTION_SIZE = 60.dp

/**
 * The bar that floats over the bottom of the home screen: search on the left, the agent on
 * the right.
 *
 * It replaces the navigation bar rather than sitting under one. A two-tab bar spent the
 * whole width of the screen on a choice between "the list" and "the thing that fills the
 * list", and left the app's one real action looking like a peer of the screen you read
 * results on. Here the action is a button the size of its importance, and the space the
 * second tab used to take is search instead.
 *
 * Floating - over the content, not below it - so the list runs to the bottom of the screen
 * and the bar stays reachable at the end of a thumb. Callers pad their own content by
 * [WristchToolbarDefaults.ContentPadding] so nothing ends up permanently underneath it.
 */
@Composable
fun WristchToolbar(
    onSearch: () -> Unit,
    onAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Floating over an edge-to-edge window, so the bar lifts itself clear of the
            // gesture bar - the content behind it still runs to the bottom of the screen.
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchBar(onSearch = onSearch, modifier = Modifier.weight(1f))
        AgentButton(onAgent)
    }
}

/**
 * The search field as a pill.
 *
 * A button dressed as a field, not a field: typing happens on [dev.eliaschen.wristch.ui
 * .screen.SearchScreen], where the results have room. Tapping here opens that screen with
 * the caret already in it, so the difference never shows.
 */
@Composable
private fun SearchBar(onSearch: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        onClick = {
            onSearch()
        },
        shape = CircleShape,
        elevation = CardDefaults.cardElevation(3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .height(BAR_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Search history and notes",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The one big button: a new run.
 *
 * A squircle rather than a circle, because the app's shape vocabulary already reads a
 * settled four-sided shape as "held, nothing in flight" - which is exactly what a button
 * that has not been pressed yet is.
 */
@Composable
private fun AgentButton(onAgent: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .size(ACTION_SIZE),
        shape = RoundedCornerShape(20.dp),
        onClick = {
            onAgent()
        },
        elevation = CardDefaults.cardElevation(3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Box(contentAlignment = Alignment.Center,modifier= Modifier.fillMaxSize()) {
            Icon(
                imageVector = WristchIcons.Sparkle,
                contentDescription = "Ask the agent to do something",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

object WristchToolbarDefaults {

    /**
     * What a screen under the toolbar has to leave free at the bottom of its own content,
     * so its last row is not stranded behind the bar.
     */
    val ContentPadding = ACTION_SIZE + 40.dp
}
