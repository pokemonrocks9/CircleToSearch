package com.akslabs.circletosearch.ui

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Long-press an image in the results -> "Save image" -> Pictures/CircleToSearch. No permission needed (MediaStore). */
object ImageSaver {
    private const val TAG = "CTS.Save"
    private const val MAX_BYTES = 30 * 1024 * 1024
    private val main = Handler(Looper.getMainLooper())

    fun prompt(context: Context, url: String, userAgent: String) {
        try {
            AlertDialog.Builder(context)
                .setItems(arrayOf("Save image")) { _, _ -> save(context.applicationContext, url, userAgent) }
                .show()
        } catch (e: Exception) {
            Log.w(TAG, "dialog failed", e)
        }
    }

    private fun toast(c: Context, msg: String) = main.post { Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }

    private fun save(c: Context, url: String, ua: String) {
        Thread {
            try {
                val (bytes, mime) = fetch(url, ua)
                val ext = when {
                    mime.contains("png") -> "png"
                    mime.contains("webp") -> "webp"
                    mime.contains("gif") -> "gif"
                    else -> "jpg"
                }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "CTS_${System.currentTimeMillis()}.$ext")
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CircleToSearch")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert failed")
                c.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                c.contentResolver.update(uri, values, null, null)
                toast(c, "Saved to Pictures/CircleToSearch")
            } catch (e: Exception) {
                Log.e(TAG, "save failed for ${url.take(80)}", e)
                toast(c, "Couldn't save this image")
            }
        }.start()
    }

    private fun fetch(url: String, ua: String): Pair<ByteArray, String> {
        if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            val head = url.substring(5, comma)
            val mime = head.substringBefore(';').ifEmpty { "image/jpeg" }
            val data = url.substring(comma + 1)
            val bytes = if (head.contains("base64")) Base64.decode(data, Base64.DEFAULT)
            else java.net.URLDecoder.decode(data, "UTF-8").toByteArray()
            return bytes to mime
        }
        require(url.startsWith("https://")) { "unsupported url" }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", ua)
        CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
        conn.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                require(out.size() <= MAX_BYTES) { "too large" }
            }
            val mime = (conn.contentType ?: "image/jpeg").substringBefore(';').trim().lowercase(Locale.ROOT)
            return out.toByteArray() to (if (mime.startsWith("image/")) mime else "image/jpeg")
        }
    }
}
