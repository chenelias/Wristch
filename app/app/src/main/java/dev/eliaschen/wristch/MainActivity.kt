package dev.eliaschen.wristch

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.eliaschen.wristch.history.RunHistory
import dev.eliaschen.wristch.notes.NoteStore
import dev.eliaschen.wristch.ui.theme.WristchTheme
import dev.eliaschen.wristch.vibe.VibeStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fully transparent bars in both directions: auto() with two transparent scrims
        // means the system never paints its own contrast band over the app, so the
        // content really does run to the top and bottom edges of the screen.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // The store is process-wide, but it needs app storage before it can load or save.
        RunHistory.attach(this)
        VibeStore.attach(this)
        NoteStore.attach(this)
        setContent {
            WristchTheme {
                WristchNavGraph()
            }
        }
    }
}
