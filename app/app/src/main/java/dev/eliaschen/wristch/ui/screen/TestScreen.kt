package dev.eliaschen.wristch.ui.screen

import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.accessibility.WristchAccessibilityService
import kotlinx.coroutines.launch

@Composable
fun TestScreen(modifier: Modifier = Modifier) {
    val isConnected by WristchAccessibilityService.isConnected.collectAsState()
    val context = LocalContext.current
    val service = WristchAccessibilityService.current()

    val scope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }
    var lastResult by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = if (isConnected) "Accessibility service is on" else "Accessibility service is off",
            style = MaterialTheme.typography.headlineSmall,
        )
        if (!isConnected) {
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text("Open accessibility settings")
            }
        }
        Card() {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({}) { Text("Capture screenshot") }
                Button({ service?.backToHome() }) { Text("Go Home") }
                Button({ service?.tapTestButton()}) { Text("Tap Test Button") }
            }
        }

        // setText() target: the service writes into this field from the outside.
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("setText() target") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth(),
        )
        Button({ scope.launch { lastResult = typeIntoInput(service) } }) { Text("setText()") }

        // scroll() target: the only scrollable node on this screen.
        Card(modifier = Modifier.height(140.dp)) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(30) { index ->
                    Text("Row $index", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ lastResult = scrollList(service, forward = true) }) { Text("Scroll down") }
            Button({ lastResult = scrollList(service, forward = false) }) { Text("Scroll up") }
        }

        Text(text = lastResult, style = MaterialTheme.typography.bodySmall)

        Button({
            Toast.makeText(context, "Test Button Taped", Toast.LENGTH_SHORT).show()
        }) { Text("Test") }
    }
}

private suspend fun typeIntoInput(service: WristchAccessibilityService?): String {
    if (service == null) return "service not connected"
    service.dumpTree()
    // Stand-in for the screenshot step: locate the field, then drive it by coordinates.
    val field = service.executor.findNode { it.isEditable } ?: return "no editable node on screen"
    val bounds = Rect().also { field.getBoundsInScreen(it) }
    val result = service.executor.typeAt(bounds.exactCenterX(), bounds.exactCenterY(), "Hello from Wristch")
    return "typeAt -> $result"
}

private fun scrollList(service: WristchAccessibilityService?, forward: Boolean): String {
    if (service == null) return "service not connected"
    val node = service.executor.findNode { it.isScrollable } ?: return "scrollable node not found"
    val direction = if (forward) "forward" else "backward"
    return "scroll $direction -> ${service.executor.scroll(node, forward)}"
}
