package com.akslabs.circletosearch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Locale

/**
 * The embedded search. The circled crop is posted straight from the WebView to Google Lens
 * (multipart form, same as lens.google.com's own "upload" box). No other host ever sees the image.
 */
object LensWeb {
    private const val TAG = "CTS.Web"

    /** Back-navigation must not return to the upload form, or it would re-submit the image. */
    const val IMAGE_BACK_FLOOR = 1
    const val TEXT_BACK_FLOOR = 0

    /** Uptime when the circle gesture ended; used only for CTS.Perf timing logs. */
    @Volatile var gestureEndMs = 0L

    private val lang: String get() = Locale.getDefault().language.ifEmpty { "en" }

    fun uploadHtml(engine: SearchEngine, jpeg: ByteArray, query: String?): String {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        when (engine) {
            SearchEngine.TINEYE -> return tineyeHtml(b64)
            SearchEngine.YANDEX -> return yandexHtml(b64)
            SearchEngine.BING -> return bingHtml(b64)
            else -> {}
        }
        val q = query?.trim().orEmpty()
        val qField = if (q.isEmpty() || engine != SearchEngine.GOOGLE) "" else
            "<input type=\"hidden\" name=\"q\" value=\"${q.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")}\">"
        val (action, field) = when (engine) {
            SearchEngine.GOOGLE ->
                "https://lens.google.com/v3/upload?hl=$lang&amp;re=df&amp;ep=gisbubb&amp;st=${System.currentTimeMillis()}" to "encoded_image"
            SearchEngine.SAUCENAO -> "https://saucenao.com/search.php" to "file"
            else -> "https://lens.google.com/v3/upload?hl=$lang&amp;re=df&amp;ep=gisbubb&amp;st=${System.currentTimeMillis()}" to "encoded_image"
        }
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark"><style>@media (prefers-color-scheme: dark){body{background:#202124 !important;color:#aaa !important}}</style></head>
<body style="margin:0;background:#fff;font-family:sans-serif;color:#777">
<p style="text-align:center;margin-top:48px">Searching&hellip;</p>
<form id="f" method="POST" enctype="multipart/form-data" action="$action">
<input type="file" name="$field" id="i" style="display:none">$qField</form>
<script>
(function(){
  var s=atob("$b64"),a=new Uint8Array(s.length);
  for(var k=0;k<s.length;k++)a[k]=s.charCodeAt(k);
  var dt=new DataTransfer();
  dt.items.add(new File([a],"image.jpg",{type:"image/jpeg"}));
  document.getElementById("i").files=dt.files;
  document.getElementById("f").submit();
})();
</script></body></html>"""
    }

    /**
     * TinEye's site does not take a plain form POST (that gives HTTP 405). Its own page sends the image
     * in a background request to /result_json/ and then opens /search/<hash>. This mimics that, same-origin
     * (the page is served with tineye.com as its base URL). The response head is logged as CTSDBG so a
     * failure shows exactly what TinEye answered.
     */
    private fun tineyeHtml(b64: String): String = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark">
<style>body{margin:0;background:#fff;font-family:sans-serif;color:#777;text-align:center;padding:48px 16px}
@media (prefers-color-scheme: dark){body{background:#202124;color:#aaa}}</style></head>
<body><p id="m">Searching&hellip;</p>
<script>
(async function(){
  var m=document.getElementById("m");
  try{
    await fetch("https://tineye.com/",{credentials:"include"});
    var s=atob("$b64"),a=new Uint8Array(s.length);
    for(var k=0;k<s.length;k++)a[k]=s.charCodeAt(k);
    var fd=new FormData();
    fd.append("image",new File([a],"image.jpg",{type:"image/jpeg"}));
    var r=await fetch("https://tineye.com/result_json/?sort=score&order=desc&page=1",
      {method:"POST",body:fd,credentials:"include"});
    var t=await r.text();
    console.log("CTSDBG tineye "+r.status+" "+t.slice(0,300));
    var h=t.match(/[0-9a-f]{40}/);
    if(r.ok&&h){location.href="https://tineye.com/search/"+h[0]+"?sort=score&order=desc&page=1";}
    else{m.textContent="TinEye did not accept the upload (HTTP "+r.status+")";}
  }catch(e){
    console.log("CTSDBG tineye error "+e);
    m.textContent="Upload to TinEye failed: "+e;
  }
})();
</script></body></html>"""

    /** Bing's own "search by image" box posts the image as a base64 text field. Untested guess. */
    private fun bingHtml(b64: String): String = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark">
<style>body{margin:0;background:#fff;font-family:sans-serif;color:#777;text-align:center;padding:48px 16px}
@media (prefers-color-scheme: dark){body{background:#202124;color:#aaa}}</style></head>
<body><p>Searching&hellip;</p>
<form id="f" method="POST" enctype="multipart/form-data"
 action="https://www.bing.com/images/search?view=detailv2&iss=sbiupload&FORM=SBIHMP&sbisrc=ImgPicker&idpbck=1">
<input type="hidden" name="imageBin" value="$b64"></form>
<script>document.getElementById("f").submit();</script></body></html>"""

    /** Yandex: same-origin upload to its cbir endpoint, then open the result page by id. Untested guess. */
    private fun yandexHtml(b64: String): String = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark">
<style>body{margin:0;background:#fff;font-family:sans-serif;color:#777;text-align:center;padding:48px 16px}
@media (prefers-color-scheme: dark){body{background:#202124;color:#aaa}}</style></head>
<body><p id="m">Searching&hellip;</p>
<script>
(async function(){
  var m=document.getElementById("m");
  try{
    var s=atob("$b64"),a=new Uint8Array(s.length);
    for(var k=0;k<s.length;k++)a[k]=s.charCodeAt(k);
    var fd=new FormData();
    fd.append("upfile",new File([a],"image.jpg",{type:"image/jpeg"}));
    var u="https://yandex.com/images/search?rpt=imageview&format=json&request="+
      encodeURIComponent('{"blocks":[{"block":"cbir-uploader__get-cbir-id"}]}');
    var r=await fetch(u,{method:"POST",body:fd,credentials:"include"});
    var t=await r.text();
    console.log("CTSDBG yandex "+r.status+" "+t.slice(0,300));
    var h=t.match(/"cbir_?[iI]d"\s*:\s*"([^"]+)"/);
    if(r.ok&&h){location.href="https://yandex.com/images/search?rpt=imageview&cbir_id="+encodeURIComponent(h[1]);}
    else{m.textContent="Yandex did not accept the upload (HTTP "+r.status+")";}
  }catch(e){
    console.log("CTSDBG yandex error "+e);
    m.textContent="Upload to Yandex failed: "+e;
  }
})();
</script></body></html>"""

    fun textUrl(engine: SearchEngine, query: String): String = when (engine) {
        SearchEngine.GOOGLE -> "https://www.google.com/search?hl=$lang&q=" + Uri.encode(query.trim())
        SearchEngine.DUCKDUCKGO -> "https://duckduckgo.com/?ia=web&q=" + Uri.encode(query.trim())
        SearchEngine.BING -> "https://www.bing.com/search?q=" + Uri.encode(query.trim())
        SearchEngine.YANDEX -> "https://yandex.com/search/?text=" + Uri.encode(query.trim())
        else -> "https://www.google.com/search?hl=$lang&q=" + Uri.encode(query.trim())
    }

    /** Recognized text -> Google Translate (auto-detect source, phone language as target). Only text is sent. */
    fun translateUrl(text: String): String {
        val loc = Locale.getDefault()
        val tl = if (loc.language == "zh") loc.toLanguageTag() else loc.language.ifEmpty { "en" }
        val t = text.trim().replace(Regex("\\s*\\n\\s*"), "\n").take(4500)
        return "https://translate.google.com/?sl=auto&tl=$tl&op=translate&text=" + Uri.encode(t)
    }

    private fun targetLang(): String {
        val loc = Locale.getDefault()
        return if (loc.language == "zh") loc.toLanguageTag() else loc.language.ifEmpty { "en" }
    }

    /** Google Translate's "Images" tab. */
    fun translateImagesUrl(): String = "https://translate.google.com/?sl=auto&tl=${targetLang()}&op=images"

    /**
     * Run on that page once it has loaded: puts the picture into the page's own file input and fires "change",
     * exactly what picking a file would do. Waits for the input to appear. Result is logged as CTSDBG.
     */
    fun translateImageJs(jpeg: ByteArray): String {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        return """(function(){
var s=atob("$b64"),a=new Uint8Array(s.length);
for(var k=0;k<s.length;k++)a[k]=s.charCodeAt(k);
var f=new File([a],"image.jpg",{type:"image/jpeg"}),n=0;
var t=setInterval(function(){
  var all=document.querySelectorAll('input[type=file]'),i=null;
  for(var q=0;q<all.length;q++){if(/image|png|jpe?g|webp/i.test(all[q].accept||"")){i=all[q];break;}}
  if(!i&&n>40&&all.length)console.log("CTSDBG translate inputs: "+Array.prototype.map.call(all,function(x){return x.accept}).join(" | "));
  if(i){clearInterval(t);var dt=new DataTransfer();dt.items.add(f);i.files=dt.files;
    i.dispatchEvent(new Event('input',{bubbles:true}));i.dispatchEvent(new Event('change',{bubbles:true}));
    console.log("CTSDBG translate image injected");}
  else if(++n>80){clearInterval(t);console.log("CTSDBG translate: no image file input found");}
},250);
})();"""
    }

    /** Tiny page loaded while the user is still drawing, so DNS/TLS to the engine is already warm. */
    fun warmupHtml(engine: SearchEngine): String {
        val host = Uri.parse(engine.baseUrl).host
        return "<html><head><link rel=\"preconnect\" href=\"https://$host\"></head><body></body></html>"
    }

    /**
     * Hides Google's own chrome (top bar, sign-in, apps button). Runs at document start so nothing
     * flashes, and keeps sweeping because the pages build themselves with script. Selectors are
     * best-effort: Google renames classes, so these lean on semantic hooks (role, aria-label, href).
     */
    private const val HIDE_JS = """
(function(){
  if (window.__ctsHide) return; window.__ctsHide = 1;
  var css = 'header,[role="banner"],#gb,a[href*="accounts.google.com"],a[href*="ServiceLogin"],' +
            '[aria-label="Google apps"],[aria-label^="Google Account"]{display:none !important}';
  if (location.hostname.indexOf('lens.') !== 0) {
    css += '#searchform,form[role="search"],#sfcnt,#tsf{display:none !important}';
  }
  var st = document.createElement('style'); st.textContent = css;
  (document.head || document.documentElement).appendChild(st);
  var queued = false;
  function sweep(){
    queued = false;
    var els = document.querySelectorAll('a,button,[role="button"]');
    for (var i = 0; i < els.length; i++) {
      var t = (els[i].innerText || '').trim();
      if (t === 'Sign in' || t === 'Sign in to Google') els[i].style.setProperty('display','none','important');
    }
  }
  function queue(){ if (!queued) { queued = true; requestAnimationFrame(sweep); } }
  new MutationObserver(queue).observe(document.documentElement, {childList:true, subtree:true});
  document.addEventListener('DOMContentLoaded', queue);
})();
"""

    /** Debug builds only: prints what is at the top of the page so selectors can be tuned. */
    private const val DEBUG_JS = """
setTimeout(function(){try{var o=[];document.querySelectorAll('body *').forEach(function(e){
var r=e.getBoundingClientRect();
if(r.top<110&&r.height>10&&r.height<130&&o.length<25){
o.push(e.tagName+'#'+e.id+'.'+(e.className&&e.className.toString().slice(0,30))+'['+(e.getAttribute('role')||'')+'|'+(e.getAttribute('aria-label')||'')+']'+(e.innerText||'').trim().slice(0,20));}});
console.log('CTSDBG '+location.hostname+' '+o.join(' ;; '));}catch(x){}},2500);
"""

    /** Hosts that stay inside the sheet; every other link opens in the system browser. */
    private fun isInternal(host: String?): Boolean {
        val h = host ?: return false
        return isGoogle(h) || h == "tineye.com" || h.endsWith(".tineye.com") ||
            h == "duckduckgo.com" || h.endsWith(".duckduckgo.com") ||
            h == "saucenao.com" || h.endsWith(".saucenao.com") ||
            h == "bing.com" || h.endsWith(".bing.com") || h.endsWith(".bing.net") ||
            h == "yandex.com" || h.endsWith(".yandex.com") || h.endsWith(".yandex.net")
    }

    private fun isGoogle(host: String?): Boolean {
        val h = host ?: return false
        return h.startsWith("google.") || h.contains(".google.") ||
            h.endsWith(".gstatic.com") || h.endsWith(".googleusercontent.com")
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, onProgress: (Int) -> Unit, onError: (String) -> Unit): WebView {
        // Default: fresh cookie jar per session, so a challenged/flagged session can't poison the next one.
        // Optional setting keeps cookies, so a solved captcha sticks and Google challenges less.
        val keepCookies = context.getSharedPreferences("cts", Context.MODE_PRIVATE).getBoolean("keep_cookies", false)
        if (!keepCookies) {
            try {
                CookieManager.getInstance().removeAllCookies(null)
            } catch (e: Exception) {
                Log.w(TAG, "cookie clear failed", e)
            }
        }
        return WebView(context).apply {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            setBackgroundColor(if (night) 0xFF202124.toInt() else Color.WHITE)
            // Dark mode: the app theme is DayNight, so pages that support prefers-color-scheme
            // (Google Search and Lens do) render dark; others get WebView's algorithmic darkening.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, night)
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                // Look like Chrome, not an embedded WebView (Lens serves a reduced page to "wv").
                userAgentString = userAgentString.replace("; wv", "").replace(" Version/4.0", "")
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val hasStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            if (hasStartScript) {
                try {
                    WebViewCompat.addDocumentStartJavaScript(this, HIDE_JS, setOf("https://*.google.com"))
                } catch (e: Exception) {
                    Log.w(TAG, "start script failed", e)
                }
            }
            // Long-press an image in the results to save it.
            setOnLongClickListener { v ->
                val r = (v as WebView).hitTestResult
                val img = r.type == WebView.HitTestResult.IMAGE_TYPE ||
                    r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                val src = r.extra
                if (img && !src.isNullOrEmpty()) {
                    ImageSaver.prompt(context, src, settings.userAgentString)
                    true
                } else false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.message().startsWith("CTSDBG")) Log.d(TAG, m.message())
                    return true
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) = onProgress(newProgress)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val scheme = uri.scheme ?: return true
                    if (scheme == "about" || scheme == "data" || scheme == "blob") return false
                    if (scheme == "https" && isInternal(uri.host)) return false
                    // Anything leaving Google (a result link) opens in the system browser.
                    if (scheme == "https" || scheme == "http") {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            Log.w(TAG, "No app for $uri")
                        }
                    }
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (gestureEndMs != 0L) Log.d("CTS.Perf", "page finished +${android.os.SystemClock.uptimeMillis() - gestureEndMs}ms ${url?.take(60)}")
                    Log.d(TAG, "loaded ${url?.take(80)}")
                    val host = url?.let { Uri.parse(it).host }
                    (view?.tag as? String)?.let { js ->
                        // One-shot script queued for this page (the translate-by-image upload).
                        if (host == "translate.google.com") {
                            view.tag = null
                            view.evaluateJavascript(js, null)
                        }
                    }
                    if (isGoogle(host)) {
                        if (!hasStartScript) view?.evaluateJavascript(HIDE_JS, null)
                        if (debuggable) view?.evaluateJavascript(DEBUG_JS, null)
                    }
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        Log.e(TAG, "main frame error ${error?.errorCode} ${error?.description} for ${request.url.host}")
                        onError("${error?.description ?: "load error"} (${request.url.host})")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?
                ) {
                    if (request?.isForMainFrame == true) {
                        // Log only: a captcha / block page is an HTTP error but is a real page the user must see.
                        Log.w(TAG, "main frame HTTP ${errorResponse?.statusCode} for ${request.url.host}${request.url.path}")
                    }
                }
            }
        }
    }
}
