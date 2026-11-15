package com.akslabs.circletosearch.utils

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.ByteArrayOutputStream

object ImageUtils {
    /** Crops [rect] (clamped to the bitmap); returns null if nothing is left. */
    fun crop(source: Bitmap, rect: Rect): Bitmap? {
        val left = rect.left.coerceIn(0, source.width - 1)
        val top = rect.top.coerceIn(0, source.height - 1)
        val right = rect.right.coerceIn(left + 1, source.width)
        val bottom = rect.bottom.coerceIn(top + 1, source.height)
        return try {
            Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        } catch (e: Exception) {
            null
        }
    }

    /** JPEG bytes for upload, longest side capped at [maxSide]. Held in memory only; never written to disk. */
    fun toJpeg(bitmap: Bitmap, maxSide: Int = 1600, quality: Int = 90): ByteArray {
        val longest = maxOf(bitmap.width, bitmap.height)
        val src = if (longest > maxSide) {
            val s = maxSide / longest.toFloat()
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt().coerceAtLeast(1),
                (bitmap.height * s).toInt().coerceAtLeast(1), true)
        } else bitmap
        val out = ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
