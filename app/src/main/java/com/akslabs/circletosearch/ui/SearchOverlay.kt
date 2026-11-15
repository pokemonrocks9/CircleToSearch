package com.akslabs.circletosearch.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.akslabs.circletosearch.R
import com.akslabs.circletosearch.ocr.Ocr
import com.akslabs.circletosearch.ocr.OcrLang
import com.akslabs.circletosearch.utils.ImageUtils
import com.akslabs.circletosearch.utils.QrResult
import com.akslabs.circletosearch.utils.QrResultWithBounds
import com.akslabs.circletosearch.utils.QrScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FRAC_DEFAULT = 0.6f
private const val FRAC_MAX = 0.92f
private const val FRAC_CLOSE = 0.3f

/**
 * Frozen screenshot + circle gesture + one search bar. Circle something: QR codes are decoded
 * on-device; anything else slides up an embedded Google Lens result (typed words are attached for
 * multisearch). Typing and pressing search with no circle opens an embedded Google search.
 */
@Composable
fun SearchOverlay(bitmap: Bitmap, onClose: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    val prefs = remember { context.getSharedPreferences("cts", Context.MODE_PRIVATE) }
    val engine = remember { SearchEngine.image(prefs) } // image engine, chosen in the app settings
    val textEngine = remember { SearchEngine.text(prefs) }
    val translateEnabled = remember { prefs.getBoolean("translate_enabled", true) }
    var query by remember { mutableStateOf("") }
    var points by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var selection by remember { mutableStateOf<Rect?>(null) }
    var qr by remember { mutableStateOf<QrResultWithBounds?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var working by remember { mutableStateOf(false) }
    var translateMode by remember { mutableStateOf(false) } // long-press translate: next stroke picks the text
    var lastCrop by remember { mutableStateOf<Bitmap?>(null) }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(100) }
    var webError by remember { mutableStateOf<String?>(null) }
    var backFloor by remember { mutableIntStateOf(LensWeb.IMAGE_BACK_FLOOR) }
    val sheetFrac = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) } // 0 = shown, 1 = dismissed (drives the swipe-back look)

    // Fade the bottom block in after window insets have settled, so it can't flash/jump on open.
    val uiAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(60)
        uiAlpha.animateTo(1f, tween(150))
    }

    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }

    fun closeSheet() {
        scope.launch {
            sheetFrac.animateTo(0f, tween(200))
            sheetOpen = false
            selection = null
            webView?.loadUrl("about:blank")
        }
    }

    /** Opens the sheet right away; the caller loads content into the returned WebView. */
    fun openSheet(historyExtra: Int): WebView {
        val w = webView ?: LensWeb.create(context, { progress = it }, { webError = it }).also { webView = it }
        // Back may not return to the upload form (it would re-submit) or to a warmup/blank page.
        backFloor = w.copyBackForwardList().currentIndex + 1 + historyExtra
        progress = 0
        webError = null
        sheetOpen = true
        scope.launch { sheetFrac.animateTo(FRAC_DEFAULT, tween(220)) }
        return w
    }

    // Warm the WebView and the connection to Lens while the user is still drawing.
    LaunchedEffect(Unit) {
        delay(450)
        if (webView == null) {
            val w = LensWeb.create(context, { progress = it }, { webError = it })
            webView = w
            w.loadDataWithBaseURL(engine.baseUrl, LensWeb.warmupHtml(engine), "text/html", "utf-8", null)
        }
    }

    // Sheet open: back steps through results, then closes the sheet.
    BackHandler(enabled = sheetOpen) {
        val w = webView
        if (w != null && w.copyBackForwardList().currentIndex > backFloor && w.canGoBack()) w.goBack()
        else closeSheet()
    }

    // Sheet closed: back dismisses the whole overlay. The bar slides down and the dim fades away,
    // following the swipe, instead of the system animating the entire window as an app closing.
    PredictiveBackHandler(enabled = !sheetOpen) { progress ->
        try {
            progress.collect { ev -> exit.snapTo(ev.progress * 0.6f) }
            exit.animateTo(1f, tween(140))
            onClose()
        } catch (e: CancellationException) {
            exit.animateTo(0f, tween(160))
        }
    }

    val minSelectionPx = with(density) { 40.dp.toPx() }
    val padPx = with(density) { 10.dp.toPx() }

    // Translate by image: Google Translate's image tab reads the picture itself (the image goes to Google).
    fun translateImage(src: Bitmap) {
        scope.launch {
            val js = withContext(Dispatchers.Default) {
                runCatching { LensWeb.translateImageJs(ImageUtils.toJpeg(src, 1600, 85)) }.getOrNull()
            }
            if (js == null) {
                Toast.makeText(context, R.string.toast_lens_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            selection = null
            val w = openSheet(LensWeb.TEXT_BACK_FLOOR)
            w.tag = js
            w.loadUrl(LensWeb.translateImagesUrl())
        }
    }

    // Translate: by default read the text on the device (ML Kit or Tesseract) and send only the text to
    // Google Translate. Setting "translate_mode": text | auto (image if no text is found) | image.
    fun runTranslate(src: Bitmap) {
        if (working) return
        focus.clearFocus()
        val mode = prefs.getString("translate_mode", "auto") ?: "auto"
        if (mode == "image") {
            translateImage(src)
            return
        }
        working = true
        val ocrEngine = prefs.getString("ocr_engine", "mlkit") ?: "mlkit"
        val ocrLang = OcrLang.from(prefs.getString("ocr_lang", null))
        scope.launch {
            val text = withContext(Dispatchers.Default) {
                runCatching { Ocr.recognize(context, src, ocrEngine, ocrLang) }
                    .onFailure { Log.e("CTS.OCR", "recognize failed", it) }
                    .getOrNull()
            }
            working = false
            val usable = text != null && text.isNotBlank()
            when {
                usable -> {
                    selection = null
                    openSheet(LensWeb.TEXT_BACK_FLOOR).loadUrl(LensWeb.translateUrl(text!!))
                }
                mode == "auto" -> translateImage(src)
                text == null -> Toast.makeText(context, "Couldn't read text (see CTS.OCR log)", Toast.LENGTH_SHORT).show()
                else -> Toast.makeText(context, "No text found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun finishGesture() {
        val pts = points
        points = emptyList()
        if (working || pts.size < 6 || viewSize.width == 0 || viewSize.height == 0) return
        if (translateMode) {
            // Draw mode: a line (or any stroke) over text; its box, padded so a thin line still catches the text.
            translateMode = false
            val pad = with(density) { 22.dp.toPx() }
            val r = Rect(
                left = (pts.minOf { it.x } - pad).coerceAtLeast(0f),
                top = (pts.minOf { it.y } - pad).coerceAtLeast(0f),
                right = (pts.maxOf { it.x } + pad).coerceAtMost(viewSize.width.toFloat()),
                bottom = (pts.maxOf { it.y } + pad).coerceAtMost(viewSize.height.toFloat())
            )
            selection = r
            val tx = bitmap.width / viewSize.width.toFloat()
            val ty = bitmap.height / viewSize.height.toFloat()
            val c = ImageUtils.crop(
                bitmap,
                android.graphics.Rect(
                    (r.left * tx).toInt(), (r.top * ty).toInt(), (r.right * tx).toInt(), (r.bottom * ty).toInt()
                )
            )
            if (c != null) runTranslate(c) else selection = null
            return
        }
        val rect = Rect(
            left = (pts.minOf { it.x } - padPx).coerceAtLeast(0f),
            top = (pts.minOf { it.y } - padPx).coerceAtLeast(0f),
            right = (pts.maxOf { it.x } + padPx).coerceAtMost(viewSize.width.toFloat()),
            bottom = (pts.maxOf { it.y } + padPx).coerceAtMost(viewSize.height.toFloat())
        )
        if (rect.width < minSelectionPx || rect.height < minSelectionPx) return
        selection = rect

        // View coords -> bitmap pixels (the screenshot is drawn stretched over the full window).
        val sx = bitmap.width / viewSize.width.toFloat()
        val sy = bitmap.height / viewSize.height.toFloat()
        val crop = ImageUtils.crop(
            bitmap,
            android.graphics.Rect(
                (rect.left * sx).toInt(), (rect.top * sy).toInt(),
                (rect.right * sx).toInt(), (rect.bottom * sy).toInt()
            )
        )
        lastCrop = crop
        if (crop == null) {
            Toast.makeText(context, R.string.toast_lens_failed, Toast.LENGTH_SHORT).show()
            selection = null
            return
        }
        val typed = query
        val eng = engine
        working = true
        val t0 = android.os.SystemClock.uptimeMillis()
        LensWeb.gestureEndMs = t0
        scope.launch {
            // Encode the upload in parallel with the (fast) QR check; it is discarded if a QR is found.
            val htmlJob = async(Dispatchers.Default) {
                runCatching { LensWeb.uploadHtml(eng, ImageUtils.toJpeg(crop, 1280, 85), typed) }.getOrNull()
            }
            val hit = withContext(Dispatchers.Default) {
                runCatching { QrScanner.scanCrop(crop) }.getOrNull()
            }
            Log.d("CTS.Perf", "qr check done +${android.os.SystemClock.uptimeMillis() - t0}ms")
            if (hit != null) {
                htmlJob.cancel()
                working = false
                qr = hit
                return@launch
            }
            val w = openSheet(LensWeb.IMAGE_BACK_FLOOR) // slide up immediately
            val html = htmlJob.await()
            Log.d("CTS.Perf", "html ready, sheet open +${android.os.SystemClock.uptimeMillis() - t0}ms")
            working = false
            if (html == null) {
                Log.e("CTS.Overlay", "Could not prepare upload")
                Toast.makeText(context, R.string.toast_lens_failed, Toast.LENGTH_SHORT).show()
                closeSheet()
                return@launch
            }
            w.loadDataWithBaseURL(eng.baseUrl, html, "text/html", "utf-8", null)
        }
    }

    Box(
        Modifier.fillMaxSize().onSizeChanged { viewSize = it }
            .graphicsLayer { alpha = 1f - exit.value }
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                // A plain tap on the screenshot (anywhere not covered by the search bar / sheet) closes the overlay.
                detectTapGestures {
                    focus.clearFocus()
                    scope.launch {
                        exit.animateTo(1f, tween(140))
                        onClose()
                    }
                }
            }.pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        focus.clearFocus() // collapse the keyboard so the whole screen is circle-able
                        qr = null
                        selection = null
                        points = listOf(start)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        points = points + change.position
                    },
                    onDragEnd = { finishGesture() },
                    onDragCancel = { points = emptyList() }
                )
            }
        ) {
            val scrim = Color.Black.copy(alpha = 0.45f)
            val sel = selection
            if (sel != null) {
                val hole = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addRoundRect(RoundRect(sel, CornerRadius(28.dp.toPx())))
                }
                drawPath(hole, scrim)
                drawRoundRect(
                    color = Color.White,
                    topLeft = sel.topLeft,
                    size = sel.size,
                    cornerRadius = CornerRadius(28.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )
            } else {
                drawRect(scrim)
            }
            if (points.size > 1) {
                val p = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                }
                drawPath(p, Color(0xFF8AB4F8).copy(alpha = 0.35f),
                    style = Stroke(14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(p, Color.White,
                    style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }

        // Bottom block: (sheet) + (QR card) + search bar. One surface so the sheet and bar read as one piece.
        Surface(
            color = if (sheetOpen) MaterialTheme.colorScheme.surface else Color.Transparent,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()
                .graphicsLayer {
                    alpha = uiAlpha.value
                    translationY = exit.value * 160.dp.toPx()
                }
        ) {
            Column(Modifier.navigationBarsPadding()) {
                val w = webView
                val barBlock: @Composable () -> Unit = {
                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = if (sheetOpen) 0.dp else 10.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val code = qr
                        if (code != null) QrCard(code, context)
                        SearchBar(
                            translateMode = translateMode,
                            translateEnabled = translateEnabled,
                            onTranslate = {
                                val sel = selection
                                runTranslate(if (sel != null) lastCrop ?: bitmap else bitmap)
                            },
                            onTranslateDraw = { translateMode = !translateMode; qr = null; selection = null },
                            query = query,
                            onQueryChange = { query = it },
                            onSearch = {
                                if (query.isNotBlank()) {
                                    focus.clearFocus()
                                    selection = null
                                    val url = LensWeb.textUrl(textEngine, query)
                                    openSheet(LensWeb.TEXT_BACK_FLOOR).loadUrl(url)
                                }
                            }
                        )
                    }
                }
                if (sheetOpen && w != null) {
                    val sheetHeight = with(density) { (sheetFrac.value * viewSize.height).toDp() }
                    // Handle (drag to resize / dismiss)
                    Box(
                        Modifier.fillMaxWidth().height(22.dp).pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dy ->
                                    change.consume()
                                    val h = viewSize.height.toFloat().coerceAtLeast(1f)
                                    scope.launch {
                                        sheetFrac.snapTo((sheetFrac.value - dy / h).coerceIn(0f, FRAC_MAX))
                                    }
                                },
                                onDragEnd = {
                                    val v = sheetFrac.value
                                    when {
                                        v < FRAC_CLOSE -> closeSheet()
                                        v < 0.76f -> scope.launch { sheetFrac.animateTo(FRAC_DEFAULT, tween(200)) }
                                        else -> scope.launch { sheetFrac.animateTo(FRAC_MAX, tween(200)) }
                                    }
                                }
                            )
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.width(36.dp).height(4.dp).background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape
                            )
                        )
                    }
                    // Search bar sits at the top of the sheet, above the results
                    barBlock()
                    Box(Modifier.fillMaxWidth().height(2.dp)) {
                        if (progress < 100) {
                            Box(
                                Modifier.fillMaxWidth(progress / 100f).fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(sheetHeight)) {
                        AndroidView(factory = { w }, modifier = Modifier.fillMaxSize())
                        val err = webError
                        if (err != null) {
                            Text(
                                "Couldn't reach Google\n$err",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                modifier = Modifier.align(Alignment.Center).padding(24.dp)
                            )
                        }
                    }
                } else {
                    barBlock()
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onTranslate: () -> Unit,
    onTranslateDraw: () -> Unit,
    translateMode: Boolean,
    translateEnabled: Boolean
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.bar_icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(14.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        if (translateMode) "Draw over the text to translate" else stringResource(R.string.search_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // Tap: translate the whole screen (or the circled area). Long-press: draw over the text to translate.
            if (translateEnabled) Box(
                Modifier.size(48.dp).pointerInput(Unit) {
                    detectTapGestures(onTap = { onTranslate() }, onLongPress = { onTranslateDraw() })
                },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.ic_translate),
                    contentDescription = "Translate",
                    tint = if (translateMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QrCard(code: QrResultWithBounds, context: Context) {
    val open = openIntent(code.result)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp)) {
            Text(
                code.rawText,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("QR", code.rawText))
                    Toast.makeText(context, R.string.qr_copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.qr_copy)) }
                if (open != null) {
                    TextButton(onClick = {
                        try {
                            context.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            Toast.makeText(context, R.string.toast_lens_failed, Toast.LENGTH_SHORT).show()
                        }
                    }) { Text(stringResource(R.string.qr_open)) }
                }
            }
        }
    }
}

private fun openIntent(r: QrResult): Intent? = when (r) {
    is QrResult.Url -> Intent(Intent.ACTION_VIEW, Uri.parse(r.url))
    is QrResult.Phone -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${r.number}"))
    is QrResult.GeoPoint -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:${r.lat},${r.lng}"))
    else -> null
}
