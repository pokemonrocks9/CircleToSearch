package com.akslabs.circletosearch

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.widget.Toast
import com.akslabs.circletosearch.data.BitmapRepository

/**
 * Entry for ASSIST / VOICE_ASSIST / LAUNCH_CONTEXTUAL_SEARCH. Takes the system's invocation-time
 * screenshot (if any), stores it in-process, and starts the assist session (or the overlay directly).
 * The screenshot never goes through a Binder Bundle, only through [BitmapRepository].
 */
class AssistEntryActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var shot: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        Log.d(TAG, "Assist intent: ${intent?.action}")
        BitmapRepository.clear() // never reuse an earlier capture
        if (intent?.action == ACTION_TRIGGER || intent?.action == Intent.ACTION_MAIN) {
            trigger(0)
            return
        }
        shot = extractScreenshot(intent)
        BitmapRepository.set(shot)
        handoff(0)
    }

    /** Icon tap / edge-gesture trigger: capture via the accessibility service (or open setup). */
    private fun trigger(attempt: Int) {
        val a11y = CircleToSearchAccessibilityService.instance
        if (a11y != null) {
            // Stay alive (transparent, so it isn't in the screenshot) until the overlay is up: with no visible
            // window of ours, Android blocks the background launch of the overlay and nothing appears.
            handler.postDelayed({
                a11y.capture {
                    Toast.makeText(applicationContext, R.string.toast_enable_a11y, Toast.LENGTH_LONG).show()
                    finish()
                }
            }, 80)
            handler.postDelayed({ finish() }, 1500)
            return
        }
        // Switched on but not connected yet (first launch after install/boot): give the system a moment.
        if (CircleToSearchAccessibilityService.isEnabled(this) && attempt < 20) {
            handler.postDelayed({ trigger(attempt + 1) }, 100)
            return
        }
        if (intent?.action == Intent.ACTION_MAIN) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            Toast.makeText(this, R.string.toast_enable_a11y, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun handoff(attempt: Int) {
        val svc = CircleToSearchVoiceService.instance
        if (svc != null) {
            try {
                svc.showSession(Bundle(), if (shot == null) VoiceInteractionSession.SHOW_WITH_SCREENSHOT else 0)
                Log.d(TAG, "Session shown (screenshot=${shot != null}, attempt=$attempt)")
                finish()
                return
            } catch (e: Exception) {
                Log.e(TAG, "showSession failed", e)
            }
        }
        // Process may have just started; the system binds the voice service a moment later.
        if (attempt < RETRIES) {
            handler.postDelayed({ handoff(attempt + 1) }, RETRY_MS)
            return
        }
        if (shot != null) {
            OverlayActivity.launch(this)
        } else {
            Toast.makeText(this, R.string.toast_set_assistant, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun extractScreenshot(intent: Intent?): Bitmap? {
        val extra: Any? = try {
            @Suppress("DEPRECATION")
            intent?.extras?.get("android.app.contextualsearch.extra.SCREENSHOT")
        } catch (e: Exception) {
            Log.e(TAG, "Reading screenshot extra failed", e)
            null
        }
        val raw = extra as? Bitmap ?: return null
        return try {
            if (raw.config == Bitmap.Config.ARGB_8888) raw else raw.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap copy failed", e)
            null
        }
    }

    private companion object {
        const val TAG = "CTS.Entry"
        const val ACTION_TRIGGER = "com.akslabs.circletosearch.TRIGGER"
        const val RETRY_MS = 100L
        const val RETRIES = 10
    }
}
