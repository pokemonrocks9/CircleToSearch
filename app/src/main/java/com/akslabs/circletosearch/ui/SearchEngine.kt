package com.akslabs.circletosearch.ui

import android.content.SharedPreferences

/**
 * One list of services; each says whether it can do image search, text search, or both.
 * Image and text engines are chosen separately in the app settings.
 */
enum class SearchEngine(
    val key: String,
    val label: String,
    val note: String,
    /** Origin the page is served from (keeps Origin/Referer sane for same-origin requests). */
    val baseUrl: String,
    val image: Boolean,
    val text: Boolean,
) {
    GOOGLE("google", "Google", "Lens + Search", "https://lens.google.com/", true, true),
    TINEYE("tineye", "TinEye", "Reverse image only", "https://tineye.com/", false, false),
    BING("bing", "Bing", "Visual Search + Search", "https://www.bing.com/", true, true),
    YANDEX("yandex", "Yandex", "Image search + Search", "https://yandex.com/", true, true),
    SAUCENAO("saucenao", "SauceNAO", "Anime / art source finder", "https://saucenao.com/", true, false),
    DUCKDUCKGO("ddg", "DuckDuckGo", "Text only", "https://duckduckgo.com/", false, true);

    companion object {
        val imageEngines get() = entries.filter { it.image }
        val textEngines get() = entries.filter { it.text }

        fun image(p: SharedPreferences): SearchEngine =
            imageEngines.firstOrNull { it.key == p.getString("image_engine", null) } ?: GOOGLE

        fun text(p: SharedPreferences): SearchEngine =
            textEngines.firstOrNull { it.key == p.getString("text_engine", null) } ?: GOOGLE
    }
}
