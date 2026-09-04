package dev.eliaschen.wristch.ui.screen

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.eliaschen.wristch.accessibility.WristchAccessibilityService

@Composable
fun TestScreen(modifier: Modifier = Modifier) {
    val isConnected by WristchAccessibilityService.isConnected.collectAsState()
    val context = LocalContext.current
    val service = WristchAccessibilityService.current()

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
        Button({
            Toast.makeText(context, "Test Button Taped", Toast.LENGTH_SHORT).show()
        }) { Text("Test") }
    }
}