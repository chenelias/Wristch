package dev.eliaschen.wristch.ui.screen

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.ui.shape.WristchShapes
import dev.eliaschen.wristch.ui.theme.VibeAccents
import dev.eliaschen.wristch.ui.theme.accentOf
import dev.eliaschen.wristch.vibe.Vibe
import dev.eliaschen.wristch.vibe.VibeConfirmation
import dev.eliaschen.wristch.context.rememberVibeSourceRequest
import dev.eliaschen.wristch.vibe.VibeSource
import dev.eliaschen.wristch.vibe.VibeStore

/**
 * One vibe, in full.
 *
 * Every control writes straight through to the store: there is no save button, because a
 * half-edited vibe that was never saved is a worse outcome than one that changed as soon
 * as it was touched, and the same edit has to survive the app being swiped away while the
 * watch is the thing actually using it.
 */
@Composable
fun VibeDetailScreen(
    vibeId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vibes by VibeStore.vibes.collectAsState()
    val vibe = vibes.firstOrNull { it.id == vibeId }
    var confirmDelete by remember { mutableStateOf(false) }

    if (vibe == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("這個氛圍已經不存在了。")
            BackToVibes(onBack)
        }
        return
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "刪除這個氛圍？",
            body = "「${vibe.name.ifBlank { "(未命名)" }}」以及它底下寫的所有內容" +
                "（口氣、背景、可讀取的資料）都會被移除，且無法復原。",
            confirmLabel = "刪除",
            onConfirm = {
                confirmDelete = false
                // Back first: this screen reads the vibe out of the store by id, and the
                // moment it is gone this composition has nothing left to show.
                onBack()
                VibeStore.delete(vibeId)
            },
            onDismiss = { confirmDelete = false },
        )
    }

    val edit: ((Vibe) -> Vibe) -> Unit = { change -> VibeStore.update(vibeId, change) }
    val askFor = rememberVibeSourceRequest()

    LazyColumn(
        // Instructions and notes are the long fields in this app; without this the
        // keyboard covers the one being typed into.
        modifier = modifier.fillMaxSize().imePadding(),
        contentPadding = WindowInsets.safeDrawing
            .add(WindowInsets(left = 24.dp, top = 24.dp, right = 24.dp, bottom = 24.dp))
            .asPaddingValues(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "top") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackToVibes(onBack)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "刪除這個氛圍",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item(key = "identity") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VibeAvatar(vibe, size = 56.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = vibe.name.ifBlank { "(未命名)" },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = if (vibe.enabled) "已開啟" else "已關閉 - 不會被任務選用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = vibe.enabled,
                    onCheckedChange = { VibeStore.setEnabled(vibeId, it) },
                )
            }
        }

        item(key = "name") {
            OutlinedTextField(
                value = vibe.name,
                onValueChange = { name -> edit { it.copy(name = name) } },
                label = { Text("名稱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(key = "subtitle") {
            OutlinedTextField(
                value = vibe.subtitle,
                onValueChange = { subtitle -> edit { it.copy(subtitle = subtitle) } },
                label = { Text("涵蓋哪些人或事") },
                placeholder = { Text("老師、同學、社團") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(key = "accent") {
            Column {
                SectionHeading("顏色")
                AccentPicker(
                    selected = vibe.accent,
                    onSelect = { index -> edit { it.copy(accent = index) } },
                )
            }
        }

        item(key = "instruction") {
            Column {
                SectionHeading("該是什麼口氣")
                Text(
                    text = "寫在這裡的規矩，Agent 每次都會先看過再動手。" +
                        "適合放那些不寫下來、就得每次重打一次的交代。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = vibe.instruction,
                    onValueChange = { text -> edit { it.copy(instruction = text) } },
                    placeholder = { Text("稱呼老師要用姓氏加職稱，話說短一點") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "notes") {
            Column {
                SectionHeading("它已經知道的事")
                Text(
                    text = "關於這些人與事的背景，Agent 會當成事實來用。" +
                        "例如稱呼、習慣、常去的地方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = vibe.notes,
                    onValueChange = { text -> edit { it.copy(notes = text) } },
                    placeholder = { Text("林老師星期四帶羽球練習") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "sources-heading") {
            Column {
                SectionHeading("它可以讀取什麼")
                Text(
                    text = "開啟後，Agent 動手前會先把這些資料讀進來。" +
                        "這裡的設定只影響這個氛圍：「家人」可以帶上你在哪裡，「學校」則不必。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(VibeSource.entries.size, key = { "source-${VibeSource.entries[it].name}" }) { index ->
            val source = VibeSource.entries[index]
            SourceRow(
                source = source,
                accent = accentOf(vibe.accent),
                checked = source in vibe.sources,
                onCheckedChange = { on ->
                    edit {
                        val sources = if (on) it.sources + source else it.sources - source
                        it.copy(sources = sources)
                    }
                    // Asked here, at the moment the switch is flipped: a source switched
                    // on without its permission is a promise the next run cannot keep,
                    // and this is the one point where the person has just said why they
                    // are being asked.
                    if (on) askFor(setOf(source))
                },
            )
        }

        item(key = "confirmation-heading") {
            Column {
                SectionHeading("動手之前")
                Text(
                    text = "使用這個氛圍時，Agent 要先問過你才能動作到什麼程度。" +
                        "不管選哪一個，只要它搞不清楚對方是誰，還是會停下來問。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(
            VibeConfirmation.entries.size,
            key = { "confirm-${VibeConfirmation.entries[it].name}" },
        ) { index ->
            val option = VibeConfirmation.entries[index]
            ConfirmationRow(
                option = option,
                selected = option == vibe.confirmation,
                onSelect = { edit { it.copy(confirmation = option) } },
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/**
 * The row per source: the switch is the whole control, and the row is not clickable.
 *
 * Deliberately unlike the confirmation rows below, where the row itself selects - a
 * mis-tap there picks a neighbouring option, a mis-tap here would hand a vibe your
 * location.
 */
@Composable
private fun SourceRow(
    source: VibeSource,
    accent: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(source.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (checked) source.explanation else "不提供給這個氛圍",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ConfirmationRow(
    option: VibeConfirmation,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(option.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = option.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The palette, as swatches.
 *
 * The chosen one carries a tick rather than only a ring: on a screen full of colour, the
 * ring is the one cue that is itself a colour difference.
 */
@Composable
private fun AccentPicker(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VibeAccents.forEachIndexed { index, color ->
            val isSelected = index == selected.mod(VibeAccents.size)
            Surface(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onSelect(index) }
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = WristchShapes.CookieShape,
                            )
                        } else {
                            Modifier
                        },
                    ),
                shape = WristchShapes.CookieShape,
                color = color,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "已選取",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackToVibes(onBack: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = "返回氛圍列表",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
