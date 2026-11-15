package com.akslabs.circletosearch

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.akslabs.circletosearch.data.BitmapRepository

/**
 * Fallback screenshot source, used only when the assistant session is handed no screenshot.
 * It listens to nothing and reads nothing from the screen; it exists solely for takeScreenshot().
 */
class CircleToSearchAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /** Captures the screen and opens the overlay. Calls [onFail] if the capture can't be made. */
    fun capture(onFail: () -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hw = result.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                        val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        hw.close()
                        if (copy == null) {
                            Log.e(TAG, "bitmap conversion returned null")
                            onFail()
                            return
                        }
                        BitmapRepository.set(copy)
                        OverlayActivity.launch(this@CircleToSearchAccessibilityService)
                    } catch (e: Exception) {
                        Log.e(TAG, "screenshot handling failed", e)
                        onFail()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshot failed: $errorCode")
                    onFail()
                }
            }
        )
    }

    companion object {
        private const val TAG = "CTS.A11y"
        @Volatile var instance: CircleToSearchAccessibilityService? = null
            private set

        /**
         * True when the user has switched the service on, even if the system hasn't connected it to this
         * process yet (right after install, boot or a process restart [instance] is briefly null).
         */
        fun isEnabled(context: android.content.Context): Boolean {
            if (instance != null) return true
            val list = runCatching {
                android.provider.Settings.Secure.getString(
                    context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull() ?: return false
            val me = android.content.ComponentName(context, CircleToSearchAccessibilityService::class.java)
            return list.split(':').any { android.content.ComponentName.unflattenFromString(it) == me }
        }
    }
}
