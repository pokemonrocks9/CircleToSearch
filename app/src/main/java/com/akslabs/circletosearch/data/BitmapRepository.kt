package com.akslabs.circletosearch.data

import android.graphics.Bitmap

/**
 * In-process hand-off of the latest screenshot. [take] returns it exactly once and clears it,
 * so a later invocation can never be served a previous page's capture.
 */
object BitmapRepository {
    @Volatile private var screenshot: Bitmap? = null

    fun set(bitmap: Bitmap?) { screenshot = bitmap }

    fun take(): Bitmap? = screenshot.also { screenshot = null }

    fun clear() { screenshot = null }
}
