package dev.eliaschen.wristch.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

class WristchAccessibilityService : AccessibilityService() {

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    val executor: ScreenExecutor by lazy { ScreenExecutor(this) }

    private var debugOverlay: dev.eliaschen.wristch.computer.StatusOverlay? = null

    private val debugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
            when (intent?.getStringExtra("op")) {
                "tap" -> {
                    val x = intent.getFloatExtra("x", 0f)
                    val y = intent.getFloatExtra("y", 0f)
                    val ms = intent.getLongExtra("ms", 50L)
                    val mode = intent.getStringExtra("mode") ?: "point"
                    scope.launch {
                        val r = executor.debugTap(x, y, ms, mode)
                        Log.i("WristchDebug", "tap($x,$y,$ms,$mode) -> $r")
                    }
                }
                "overlay_on" -> scope.launch {
                    val o = debugOverlay ?: dev.eliaschen.wristch.computer.StatusOverlay(
                        this@WristchAccessibilityService,
                        dev.eliaschen.wristch.computer.RunControl(),
                    ).also { debugOverlay = it }
                    o.show("debug overlay")
                    Log.i("WristchDebug", "overlay on")
                }
                "overlay_off" -> scope.launch {
                    debugOverlay?.hide(); debugOverlay = null
                    Log.i("WristchDebug", "overlay off")
                }
                "tree" -> {
                    Log.i("WristchDebug", "tree:\n" + describeScreen(1080, 2424))
                    Log.i("WristchDebug", "root=" + rootInActiveWindow?.packageName + " windows=" + windows.joinToString { "${'$'}{it.root?.packageName}/${'$'}{it.type}/${'$'}{it.isActive}/${'$'}{it.layer}" })
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        registerReceiver(debugReceiver, android.content.IntentFilter("dev.eliaschen.wristch.DEBUG"), android.content.Context.RECEIVER_EXPORTED)
        Log.i(TAG, "connected")
    }

    @Volatile
    private var lastEventAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.uptimeMillis()
    }

    /**
     * Waits until the screen stops changing.
     *
     * `dispatchGesture` reports completion when the *gesture* ends, which says nothing
     * about the app having reacted - a screenshot taken then still shows the screen as it
     * was before the tap. The model reads that as "the tap did nothing" and repeats it,
     * which is how a message gets sent twice.
     *
     * The events we already subscribe to (window state, content, focus) are the signal
     * that something is still redrawing; a stretch of quiet means it has settled.
     */
    suspend fun awaitIdle(quietMs: Long = QUIET_MS, timeoutMs: Long = IDLE_TIMEOUT_MS) {
        // Events lag the gesture, so a first look would always find suspicious quiet.
        delay(MIN_SETTLE_MS)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (SystemClock.uptimeMillis() - lastEventAt >= quietMs) return
            delay(POLL_MS)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        _isConnected.value = false
        screenshotExecutor.shutdown()
        Log.i(TAG, "disconnected")
        super.onDestroy()
    }

    /**
     * The current screen as text, on the same 0-999 grid the model answers in.
     *
     * Read from the live tree at the moment the screenshot is taken, so the two describe
     * the same frame; a tree read later would describe a screen the picture never showed.
     */
    fun describeScreen(width: Int, height: Int): String =
        runCatching { UiTree.summarize(rootInActiveWindow, width, height) }
            .getOrElse {
                Log.w(TAG, "ui tree unavailable: ${it.message}")
                ""
            }

    suspend fun captureScreenshot(): Bitmap? = suspendCancellableCoroutine { continuation ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = result.hardwareBuffer.use { buffer ->
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }
                    continuation.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "screenshot failed, error $errorCode")
                    continuation.resume(null)
                }
            },
        )
    }

    companion object {
        private const val TAG = "WristchAccSer"
        private const val MIN_SETTLE_MS = 250L
        private const val QUIET_MS = 350L
        private const val IDLE_TIMEOUT_MS = 2500L
        private const val POLL_MS = 50L

        @Volatile
        private var instance: WristchAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun current(): WristchAccessibilityService? = instance
    }
}