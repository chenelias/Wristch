package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.ui.component.ScreenGutter
import dev.eliaschen.wristch.ui.component.ScreenHeader
import dev.eliaschen.wristch.ui.component.screenListPadding
import dev.eliaschen.wristch.ui.shape.WristchShapes
import dev.eliaschen.wristch.ui.theme.accentOf
import dev.eliaschen.wristch.vibe.Vibe
import dev.eliaschen.wristch.vibe.VibeStore

/**
 * Every vibe the user keeps, and which one runs when nothing picked another.
 *
 * The list is the whole screen: a vibe is a name, a colour and a switch here, and
 * everything that makes it different from the next one - the wording rules, the notes,
 * what it may read - lives one tap deeper in [VibeDetailScreen]. Splitting it that way is
 * what keeps this readable as "who am I talking to" rather than as a wall of prompts.
 */
@Composable
fun VibeScreen(
    onOpenVibe: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vibes by VibeStore.vibes.collectAsState()
    val defaultId by VibeStore.defaultId.collectAsState()
    val selectable = vibes.filter { it.enabled }

    // No Scaffold here: the nav graph already owns one, and nesting a second would apply
    // the window insets twice.
    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader(title = "氛圍", onBack = onBack) {
            IconButton(onClick = { onOpenVibe(VibeStore.create()) }) {
                Icon(Icons.Default.Add, contentDescription = "新增氛圍")
            }
        }

        Text(
            text = "一個氛圍就是一種說話方式：訊息要用什麼口氣、Agent 事先知道哪些事，" +
                "以及它能自己做主到什麼程度。跟家人和跟老師說話，自然不是同一種。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 8.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = screenListPadding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vibes.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "還沒有任何氛圍。先為一個人或生活中的一塊新增一個" +
                            "（例如學校、家裡、工作），之後選用它的任務，都會照這個方式來寫。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@LazyColumn
            }

            item(key = "vibes-header") { SectionLabel("你的氛圍") }

            items(vibes, key = { it.id }) { vibe ->
                VibeRow(
                    vibe = vibe,
                    onClick = { onOpenVibe(vibe.id) },
                    onEnabledChange = { VibeStore.setEnabled(vibe.id, it) },
                )
            }

            item(key = "default-header") {
                Column {
                    SectionLabel("預設氛圍")
                    Text(
                        text = "任務沒有指定氛圍時，就用這一個。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }

            if (selectable.isEmpty()) {
                item(key = "default-none") {
                    Text(
                        text = "所有氛圍都關起來了，任務會在沒有氛圍的情況下執行。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                items(selectable, key = { "default-${it.id}" }) { vibe ->
                    DefaultRow(
                        vibe = vibe,
                        selected = vibe.id == defaultId,
                        onSelect = { VibeStore.setDefault(vibe.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun VibeRow(
    vibe: Vibe,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VibeAvatar(vibe)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = vibe.name.ifBlank { "(未命名)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summaryOf(vibe),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = vibe.enabled,
                onCheckedChange = onEnabledChange,
            )
        }
    }
}

@Composable
private fun DefaultRow(vibe: Vibe, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(vibe.name.ifBlank { "(未命名)" }, style = MaterialTheme.typography.bodyLarge)
            if (vibe.subtitle.isNotBlank()) {
                Text(
                    text = vibe.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The vibe's initial on its own colour.
 *
 * A switched-off vibe is drawn flat in the surface colour rather than greyed by opacity:
 * the row keeps its full contrast, and the colour - the thing that identifies it - is
 * what goes away.
 */
@Composable
internal fun VibeAvatar(vibe: Vibe, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val accent = accentOf(vibe.accent)
    Surface(
        modifier = Modifier.size(size),
        shape = WristchShapes.CookieShape,
        color = if (vibe.enabled) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = vibe.initial,
                style = MaterialTheme.typography.titleMedium,
                color = if (vibe.enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The one line under the name: what this vibe carries, and how freely it acts. */
private fun summaryOf(vibe: Vibe): String {
    if (!vibe.enabled) return "已關閉 - 不會被任務選用"
    val sources = when (vibe.sources.size) {
        0 -> "沒有額外資料"
        else -> vibe.sources.joinToString("、") { it.label }
    }
    return "${vibe.confirmation.label} - 會帶上$sources"
}
