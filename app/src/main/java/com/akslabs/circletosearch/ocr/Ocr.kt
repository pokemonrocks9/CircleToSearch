package com.akslabs.circletosearch.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

enum class Script { LATIN, JAPANESE, CHINESE, KOREAN }

/** [script] is null where ML Kit has no model (e.g. Russian); those always use Tesseract. */
enum class OcrLang(val key: String, val label: String, val script: Script?, val tess: String) {
    ENGLISH("english", "English", Script.LATIN, "eng"),
    SPANISH("spanish", "Spanish", Script.LATIN, "spa"),
    FRENCH("french", "French", Script.LATIN, "fra"),
    GERMAN("german", "German", Script.LATIN, "deu"),
    ITALIAN("italian", "Italian", Script.LATIN, "ita"),
    PORTUGUESE("portuguese", "Portuguese", Script.LATIN, "por"),
    RUSSIAN("russian", "Russian", null, "rus"),
    JAPANESE("japanese", "Japanese", Script.JAPANESE, "jpn"),
    CHINESE("chinese", "Chinese (Simplified)", Script.CHINESE, "chi_sim"),
    KOREAN("korean", "Korean", Script.KOREAN, "kor");

    companion object {
        fun from(key: String?): OcrLang = entries.firstOrNull { it.key == key } ?: ENGLISH
    }
}

/**
 * On-device text recognition. ML Kit (bundled models, no Play Services) or Tesseract
 * (open source; language data is downloaded on first use from the tessdata_fast repository).
 */
object Ocr {
    private const val TAG = "CTS.OCR"
    private val main = Handler(Looper.getMainLooper())

    suspend fun recognize(context: Context, src: Bitmap, engine: String, lang: OcrLang): String {
        val bmp = upscale(src)
        return if (engine == "mlkit" && lang.script != null) mlKit(bmp, lang.script) else tesseract(context, bmp, lang)
    }

    /** Small crops (a line of text) have too few pixels per character; scale them up before recognizing. */
    private fun upscale(b: Bitmap): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest >= 1200) return b
        val f = (1200f / longest).coerceAtMost(3f)
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    private suspend fun mlKit(bmp: Bitmap, script: Script): String {
        val client: TextRecognizer = when (script) {
            Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
        try {
            return suspendCancellableCoroutine { cont ->
                client.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { cont.resume(it.text) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            client.close()
        }
    }

    private fun tesseract(context: Context, bmp: Bitmap, lang: OcrLang): String {
        val root = File(context.filesDir, "tess")
        val dataFile = File(root, "tessdata/${lang.tess}.traineddata")
        if (!dataFile.exists()) download(context, lang, dataFile)
        val api = TessBaseAPI()
        try {
            check(api.init(root.absolutePath, lang.tess)) { "tesseract init failed" }
            api.setImage(bmp)
            return api.getUTF8Text().orEmpty()
        } finally {
            api.recycle()
        }
    }

    private fun download(context: Context, lang: OcrLang, dest: File) {
        main.post { Toast.makeText(context, "Downloading ${lang.label} language data…", Toast.LENGTH_LONG).show() }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        // The "fast" models are poor at CJK; use the full-accuracy ones for those (bigger download).
        val repo = if (lang.script == Script.JAPANESE || lang.script == Script.CHINESE || lang.script == Script.KOREAN)
            "tessdata_best" else "tessdata_fast"
        val url = "https://github.com/tesseract-ocr/$repo/raw/main/${lang.tess}.traineddata"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            check(conn.responseCode == 200) { "download HTTP ${conn.responseCode}" }
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.length() > 10_000) { "download too small" }
            check(tmp.renameTo(dest)) { "rename failed" }
            Log.d(TAG, "downloaded ${dest.name} (${dest.length()} bytes)")
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }
}
