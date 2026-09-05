package dev.eliaschen.wristch.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.time.Duration.Companion.seconds

class WristchAccessibilityService : AccessibilityService() {

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
        serviceScope.cancel()
        screenshotExecutor.shutdown()
        Log.i(TAG, "disconnected")
        super.onDestroy()
    }

    fun backToHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun tapTestButton() {
        serviceScope.launch {
            delay(2.seconds)
            Log.i(TAG, "tapTestButton -> ${executor.clickByName("Play Store")}")
        }
    }

    fun dumpTree(node: AccessibilityNodeInfo? = rootInActiveWindow, depth: Int = 0) {
        val current = node ?: return
        val bounds = Rect().also { current.getBoundsInScreen(it) }
        Log.d(
            TAG,
            "${"  ".repeat(depth)}${current.className} text=\"${current.text}\" " +
                    "desc=${current.contentDescription} id=${current.viewIdResourceName} " +
                    "clickable=${current.isClickable} bounds=$bounds",
        )
        for (index in 0 until current.childCount) dumpTree(current.getChild(index), depth + 1)
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

        @Volatile
        private var instance: WristchAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun current(): WristchAccessibilityService? = instance
    }
}