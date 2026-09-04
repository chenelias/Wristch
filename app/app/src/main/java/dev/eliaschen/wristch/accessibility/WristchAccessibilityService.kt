package dev.eliaschen.wristch.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

class WristchAccessibilityService : AccessibilityService() {

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    val executor: ScreenExecutor by lazy { ScreenExecutor(this) }


    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        _isConnected.value = false
        screenshotExecutor.shutdown()
        Log.i(TAG, "disconnected")
        super.onDestroy()
    }

    fun backToHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun tapTestButton() {
        Log.i(TAG, "button method is called")
        val testBtn = rootInActiveWindow.findAccessibilityNodeInfosByText("Test")
            .firstOrNull { it.isClickable } ?: return
        Log.i(TAG, "button is available")
        executor.click(testBtn)
    }

    /**
     * A screenshot of the default display.
     *
     * Goes through the accessibility API rather than MediaProjection precisely because it
     * needs no per-session consent dialog - an agent acting on a watch gesture cannot stop
     * to ask for screen-capture permission every time. The platform rate-limits this to
     * roughly one call per second and returns null when it refuses.
     */
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
        private const val TAG = "Wristch"

        @Volatile
        private var instance: WristchAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun current(): WristchAccessibilityService? = instance
    }
}
