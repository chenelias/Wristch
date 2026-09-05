package dev.eliaschen.wristch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A task the app should be showing, asked for from outside the navigation graph.
 *
 * A run outlives the screen that started it and usually ends while Wristch is not even in
 * front - the agent spends the whole run inside other apps. So when it finishes, something
 * that is not a composable has to say where the app should land, and it cannot do that by
 * calling into a back stack that may not be composed at that moment. It leaves the request
 * here instead, and the graph picks it up whenever it next exists.
 *
 * One slot, not a queue: only one run happens at a time, and a second request means the
 * first is stale.
 */
object TaskRoute {

    private val _pending = MutableStateFlow<String?>(null)

    /** The run to open, or null when there is nothing waiting. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun open(runId: String) {
        _pending.value = runId
    }

    /** Taken by the graph once it has actually navigated, so it never lands twice. */
    fun consume(runId: String) {
        _pending.compareAndSet(runId, null)
    }
}
