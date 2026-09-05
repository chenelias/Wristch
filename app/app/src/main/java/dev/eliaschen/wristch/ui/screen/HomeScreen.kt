package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.history.RunHistory
import dev.eliaschen.wristch.history.RunRecord
import dev.eliaschen.wristch.history.RunStatus
import dev.eliaschen.wristch.ui.component.WristchToolbarDefaults
import dev.eliaschen.wristch.vibe.Vibe
import dev.eliaschen.wristch.vibe.VibeStore

/** The width of a vibe card - the card is square, so this is its height too. */
private val VIBE_CARD = 132.dp

/**
 * The first thing the app opens on: who you can talk as, and what you asked for lately.
 *
 * The runs are one day's, not a fixed number of the newest: what the home screen answers
 * is "what have I had it do", and a count would either cut that off on a busy day or pad
 * it with last week on a quiet one. Today, when there is a today - otherwise the last day
 * that has anything, headed by its own name, so a phone picked up on Monday opens on
 * Friday's work instead of on an empty list. Everything older is behind the arrow.
 */
@Composable
fun HomeScreen(
    onOpenRun: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenVibe: (String) -> Unit,
    onOpenVibes: () -> Unit,
    onOpenNotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val runs by RunHistory.runs.collectAsState()
    val vibes by VibeStore.vibes.collectAsState()
    val defaultId by VibeStore.defaultId.collectAsState()

    // The newest day that has runs at all, which is today whenever today has any.
    val day = runs.maxOfOrNull { dayOf(it) }
    // A run in flight is the one thing here that is about now rather than about the past,
    // so it comes first however recent the runs behind it are.
    val recent = runs
        .filter { dayOf(it) == day }
        .sortedWith(
            compareByDescending<RunRecord> { it.status == RunStatus.RUNNING }
                .thenByDescending { it.startedAt },
        )

    // No Scaffold here: the nav graph already owns one, and nesting a second would apply
    // the window insets twice.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Inside the scroll, so the list passes behind the gesture bar on its way up
            // and only comes to rest clear of it.
            .navigationBarsPadding()
            // The toolbar floats over this screen rather than sitting under it, so the
            // content has to stop short of where it lands - otherwise the last run of the
            // day is permanently behind the search bar.
            .padding(bottom = WristchToolbarDefaults.ContentPadding),
    ) {
        // Centred, because there is nothing beside it to balance against: the app's name
        // is the whole of this bar, and left-aligning it only reads as a title when there
        // is a back arrow or an action sharing the row.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Left-side management entry: open Notes
            androidx.compose.material3.IconButton(onClick = onOpenNotes, modifier = Modifier.align(Alignment.CenterStart)) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.List,
                    contentDescription = "Notes",
                )
            }
            Text(text = "Wristch", style = MaterialTheme.typography.headlineSmall)
        }

        SectionHeader(
            title = "Vibes",
            arrowDescription = "See all vibes",
            onArrow = onOpenVibes,
        )

        // Horizontal, because a vibe is picked by recognising it rather than by reading
        // it - a row of faces answers "which one of you" in a glance a stack cannot.
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(vibes, key = { it.id }) { vibe ->
                VibeCard(
                    vibe = vibe,
                    isDefault = vibe.id == defaultId,
                    onClick = { onOpenVibe(vibe.id) },
                )
            }
            item(key = "add-vibe") {
                AddVibeCard(onClick = { onOpenVibe(VibeStore.create()) })
            }
        }

        SectionHeader(
            title = day?.let { dayLabel(it) } ?: "Today",
            arrowDescription = "See all history",
            onArrow = onOpenHistory,
        )

        if (recent.isEmpty()) {
            Text(
                text = "No runs yet. Anything you start from the button below shows up here.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            return@Column
        }

        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recent.forEach { run ->
                RunRow(run = run, onClick = { onOpenRun(run.id) })
            }
        }
    }
}

/** A heading with the way out of it: the arrow opens the full list this section samples. */
@Composable
private fun SectionHeader(
    title: String,
    arrowDescription: String,
    onArrow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = arrowDescription,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * One vibe as a square tile: the badge, the name, and what it covers.
 *
 * Square on purpose - the tiles read as a set of faces, and a card that grew with the
 * length of its subtitle would make the row look like a ragged list instead.
 */
@Composable
private fun VibeCard(vibe: Vibe, isDefault: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(VIBE_CARD)
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // The badge hangs off the corner of the cover rather than sitting beside it
            // as a word: the tile is small, and the name below it needs the width more
            // than "Default" does.
            Box {
                VibeAvatar(vibe, size = 44.dp)
                if (isDefault) DefaultBadge(Modifier.align(Alignment.BottomEnd))
            }
            Text(
                text = vibe.name.ifBlank { "(unnamed)" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = when {
                    !vibe.enabled -> "Off"
                    vibe.subtitle.isNotBlank() -> vibe.subtitle
                    else -> vibe.confirmation.label
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The tick that marks the default vibe, notched into the corner of its cover.
 *
 * A circle, not one of [dev.eliaschen.wristch.ui.shape.WristchShapes] - at 18dp a lobed
 * outline turns to mush, and the ring of card colour around it is what lifts it off the
 * cover it overlaps. It carries the description, since the word "Default" is gone.
 */
@Composable
private fun DefaultBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(18.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Default vibe",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

@Composable
private fun AddVibeCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(VIBE_CARD)
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add a vibe",
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "New vibe",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
