package dev.eliaschen.wristch

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.eliaschen.wristch.history.RunHistory
import dev.eliaschen.wristch.memory.MemoryStore
import dev.eliaschen.wristch.settings.SettingsStore
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
        MemoryStore.attach(this)
        SettingsStore.attach(this)
        // Whatever brought the activity up may have named a run to open - a finished run
        // asking to be shown, from a process that had no back stack to push onto.
        route(intent)
        setContent {
            WristchTheme {
                WristchNavGraph()
            }
        }
    }

    /**
     * The activity is `singleTask`, so a second launch while it is alive - which is the
     * ordinary case, since a run only ever ends with Wristch already running in the
     * background - arrives here rather than through `onCreate`.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    private fun route(intent: Intent?) {
        intent?.getStringExtra(EXTRA_RUN_ID)?.let(TaskRoute::open)
    }

    companion object {
        /** The run a launch is asking the app to land on. */
        const val EXTRA_RUN_ID = "run_id"
    }
}
