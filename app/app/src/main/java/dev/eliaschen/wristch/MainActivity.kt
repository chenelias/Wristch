package dev.eliaschen.wristch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.eliaschen.wristch.history.RunHistory
import dev.eliaschen.wristch.ui.theme.WristchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The store is process-wide, but it needs app storage before it can load or save.
        RunHistory.attach(this)
        setContent {
            WristchTheme {
                WristchNavGraph()
            }
        }
    }
}
