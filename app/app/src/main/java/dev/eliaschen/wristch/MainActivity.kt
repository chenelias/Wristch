package dev.eliaschen.wristch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.eliaschen.wristch.history.RunHistory
import dev.eliaschen.wristch.notes.NoteStore
import dev.eliaschen.wristch.ui.theme.WristchTheme
import dev.eliaschen.wristch.vibe.VibeStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
