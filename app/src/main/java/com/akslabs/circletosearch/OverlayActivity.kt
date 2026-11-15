package com.akslabs.circletosearch

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.akslabs.circletosearch.data.BitmapRepository
import com.akslabs.circletosearch.ui.SearchOverlay
import com.akslabs.circletosearch.ui.theme.CircleToSearchTheme

/**
 * Full-screen frozen screenshot with the circle gesture and the search bar. Singleton task:
 * [onNewIntent] swaps in the new capture and bumps [generation] so every piece of UI state
 * (selection, typed text, QR card) is rebuilt from scratch.
 */
class OverlayActivity : ComponentActivity() {

    private var shot by mutableStateOf<Bitmap?>(null)
    private var generation by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The window is translucent, so a recents thumbnail of it would show the home screen through it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
        }
        if (!load()) return

        setContent {
            CircleToSearchTheme {
                val b = shot
                if (b != null) {
                    key(generation) { SearchOverlay(bitmap = b, onClose = { finish() }) }
                }
            }
        }
    }

    /** The overlay animates itself out in Compose; the system must not add its own "app closing" animation. */
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** Leaving the screen (lock, home, app switch, opening a link) ends the session; never resume a stale capture. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            Log.d(TAG, "Stopped, closing overlay")
            finishAndRemoveTask()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shot = null // never flash the previous page
        load()
    }

    /** Takes the pending screenshot; if there is none, says so and closes. */
    private fun load(): Boolean {
        val b = BitmapRepository.take()
        if (b == null) {
            Log.e(TAG, "No screenshot in repository")
            Toast.makeText(this, R.string.toast_no_screenshot, Toast.LENGTH_LONG).show()
            finish()
            return false
        }
        Log.d(TAG, "Screenshot ${b.width}x${b.height}")
        shot = b
        generation++
        return true
    }

    companion object {
        private const val TAG = "CTS.Overlay"

        fun launch(context: Context) {
            context.startActivity(
                Intent(context, OverlayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }
}
