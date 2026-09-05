package dev.eliaschen.wristch.computer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The hand on the agent's shoulder: hold it here, let it carry on, or end it.
 *
 * A run is a loop of network requests and real gestures, so it cannot be interrupted at an
 * arbitrary instant - a half-dispatched swipe has to finish. Instead the loop passes
 * through [awaitGo] at the points where stopping is safe: before it asks the model what to
 * do next, and before each action it was told to take.
 */
class RunControl {

    enum class State { RUNNING, PAUSED, STOPPED }

    private val _state = MutableStateFlow(State.RUNNING)
    val state: StateFlow<State> = _state.asStateFlow()

    val isPaused: Boolean get() = _state.value == State.PAUSED

    /** Pausing a run that has already been stopped would restart it; hence the guards. */
    fun pause() {
        _state.compareAndSet(State.RUNNING, State.PAUSED)
    }

    fun resume() {
        _state.compareAndSet(State.PAUSED, State.RUNNING)
    }

    fun stop() {
        _state.value = State.STOPPED
    }

    /** Fresh state for a new run; the object outlives any single one. */
    fun reset() {
        _state.value = State.RUNNING
    }

    /**
     * Suspends while paused, and answers whether the run should carry on.
     *
     * Stopping while paused works because it moves the state out of PAUSED - the wait
     * below ends, and returns false rather than resuming the loop.
     */
    suspend fun awaitGo(): Boolean = when (_state.value) {
        State.RUNNING -> true
        State.STOPPED -> false
        State.PAUSED -> _state.first { it != State.PAUSED } == State.RUNNING
    }
}
