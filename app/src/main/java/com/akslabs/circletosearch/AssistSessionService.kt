package com.akslabs.circletosearch

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.akslabs.circletosearch.data.BitmapRepository

/**
 * Assistant session. Its only job is to get a screenshot into [BitmapRepository] and start
 * [OverlayActivity]. If the system never supplies one, it says so instead of failing silently.
 */
class AssistSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession = Session(this)

    private class Session(context: Context) : VoiceInteractionSession(context) {
        private val handler = Handler(Looper.getMainLooper())
        private var launched = false

        override fun onCreate() {
            super.onCreate()
            // The overlay activity is the only UI; the session window must never draw (it is
            // bottom-anchored and shows as a flash). Configure the window FIRST: once the UI is
            // disabled, touching the window throws IllegalStateException.
            try {
                window?.window?.apply {
                    setBackgroundDrawable(ColorDrawable(0))
                    setDimAmount(0f)
                    setWindowAnimations(0)
                }
                setUiEnabled(false)
            } catch (e: Exception) {
                Log.w(TAG, "session window setup failed", e)
            }
        }

        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            launched = false
            handler.removeCallbacksAndMessages(null)
            // Screenshot already stored by AssistEntryActivity?
            val have = BitmapRepository.take()
            if (have != null) {
                go(have)
                return
            }
            // The system may deliver one via onHandleScreenshot just after this. If nothing has
            // arrived shortly, fall back to the accessibility screenshot; if that's off, say so.
            handler.postDelayed({
                if (launched) return@postDelayed
                launched = true
                val a11y = CircleToSearchAccessibilityService.instance
                if (a11y != null) {
                    Log.d(TAG, "No system screenshot; using accessibility capture")
                    hide() // get our (empty) session window out of the capture
                    handler.postDelayed({
                        a11y.capture {
                            Toast.makeText(context, R.string.toast_no_screenshot, Toast.LENGTH_LONG).show()
                        }
                    }, 120L)
                } else {
                    Log.e(TAG, "No system screenshot and accessibility service is off")
                    Toast.makeText(context, R.string.toast_enable_a11y, Toast.LENGTH_LONG).show()
                    hide()
                }
            }, FALLBACK_MS)
        }

        override fun onHandleScreenshot(screenshot: android.graphics.Bitmap?) {
            super.onHandleScreenshot(screenshot)
            Log.d(TAG, "onHandleScreenshot null=${screenshot == null}")
            if (launched || screenshot == null) return
            go(screenshot)
        }

        private fun go(bitmap: android.graphics.Bitmap) {
            launched = true
            handler.removeCallbacksAndMessages(null)
            BitmapRepository.set(bitmap)
            OverlayActivity.launch(context)
            hide() // our session window must not sit above the overlay
        }

        override fun onHide() {
            super.onHide() // timers stay alive: the accessibility fallback runs after hide()
        }
    }

    private companion object {
        const val TAG = "CTS.Session"
        const val FALLBACK_MS = 300L
    }
}
