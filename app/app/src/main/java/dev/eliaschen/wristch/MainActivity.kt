package dev.eliaschen.wristch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.eliaschen.wristch.ui.theme.WristchTheme
import dev.eliaschen.wristch.ui.screen.AgentScreen
import dev.eliaschen.wristch.ui.screen.TestScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WristchTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var tab by remember { mutableIntStateOf(0) }
                    Column(modifier = Modifier.padding(innerPadding)) {
                        PrimaryTabRow(selectedTabIndex = tab) {
                            Tab(tab == 0, { tab = 0 }, text = { Text("Agent") })
                            Tab(tab == 1, { tab = 1 }, text = { Text("Test") })
                        }
                        when (tab) {
                            0 -> AgentScreen()
                            else -> TestScreen()
                        }
                    }
                }
            }
        }
    }
}