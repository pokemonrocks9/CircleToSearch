package com.akslabs.circletosearch

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.akslabs.circletosearch.ocr.OcrLang
import com.akslabs.circletosearch.ui.SearchEngine
import com.akslabs.circletosearch.ui.theme.CircleToSearchTheme

/**
 * Setup checklist. Opens on first launch and shows live status for everything the app needs,
 * with a button straight to the right settings screen for each missing item. Re-checks on resume.
 */
class MainActivity : ComponentActivity() {

    private var assistantOk by mutableStateOf(false)
    private var a11yOk by mutableStateOf(false)
    private var networkOk by mutableStateOf(false)

    private val prefs by lazy { getSharedPreferences("cts", Context.MODE_PRIVATE) }
    private var imageEngine by mutableStateOf(SearchEngine.GOOGLE)
    private var textEngine by mutableStateOf(SearchEngine.GOOGLE)
    private var keepCookies by mutableStateOf(false)
    private var translateOn by mutableStateOf(true)
    private var translateMode by mutableStateOf("auto")
    private var ocrEngine by mutableStateOf("mlkit")
    private var ocrLang by mutableStateOf(OcrLang.ENGLISH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imageEngine = SearchEngine.image(prefs)
        textEngine = SearchEngine.text(prefs)
        keepCookies = prefs.getBoolean("keep_cookies", false)
        translateOn = prefs.getBoolean("translate_enabled", true)
        translateMode = prefs.getString("translate_mode", "auto") ?: "auto"
        ocrEngine = prefs.getString("ocr_engine", "mlkit") ?: "mlkit"
        ocrLang = OcrLang.from(prefs.getString("ocr_lang", null))
        refresh()
        setContent {
            CircleToSearchTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                            .verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
                        Text(
                            if (assistantOk && a11yOk && networkOk) "All set. Long-press home (or use your gesture app) to search."
                            else "Finish the steps below.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SetupRow(
                            title = "Default assistant",
                            body = "Lets your home gesture hand this app the screen.",
                            ok = assistantOk,
                            action = "Choose assistant",
                            onAction = ::openAssistantSettings
                        )
                        SetupRow(
                            title = "Screenshot fallback",
                            body = "Accessibility service, used only when Android sends no screenshot. It never reads or " +
                                "stores what is on screen. If the switch is greyed out: App info, then the three-dot menu, " +
                                "then Allow restricted settings.",
                            ok = a11yOk,
                            action = "Accessibility settings",
                            onAction = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        )
                        SetupRow(
                            title = "Network access",
                            body = "Needed to show Google Lens and search results. On GrapheneOS: Permissions, then Network.",
                            ok = networkOk,
                            action = "Open app permissions",
                            onAction = {
                                startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                                )
                            }
                        )

                        Text("Search", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text("Image search", style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                                SearchEngine.imageEngines.forEach { e ->
                                    EngineRow(e, imageEngine == e) { chooseEngine(e, true) }
                                }
                                Text("Text search", style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(start = 20.dp, top = 12.dp))
                                SearchEngine.textEngines.forEach { e ->
                                    EngineRow(e, textEngine == e) { chooseEngine(e, false) }
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Translate button", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Show it in the search bar (applies next time it opens)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(checked = translateOn, onCheckedChange = {
                                        translateOn = it; prefs.edit().putBoolean("translate_enabled", it).apply()
                                    })
                                }
                                Text("Translate: method", style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(start = 20.dp, top = 12.dp))
                                OptionRow("Text only", "Read text on the phone; only the text is sent to Google Translate", translateMode == "text") {
                                    translateMode = "text"; prefs.edit().putString("translate_mode", "text").apply()
                                }
                                OptionRow("Text, then image", "If no text is found, Google Translate reads the image (sends the image)", translateMode == "auto") {
                                    translateMode = "auto"; prefs.edit().putString("translate_mode", "auto").apply()
                                }
                                OptionRow("Image only", "Always let Google Translate read the image (sends the image)", translateMode == "image") {
                                    translateMode = "image"; prefs.edit().putString("translate_mode", "image").apply()
                                }
                                Text("Translate: text recognition", style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(start = 20.dp, top = 12.dp))
                                OptionRow("ML Kit", "Accurate, bundled in the app (Google library, runs on device)", ocrEngine == "mlkit") {
                                    ocrEngine = "mlkit"; prefs.edit().putString("ocr_engine", "mlkit").apply()
                                }
                                OptionRow("Tesseract", "Fully open source; language data downloads on first use", ocrEngine == "tess") {
                                    ocrEngine = "tess"; prefs.edit().putString("ocr_engine", "tess").apply()
                                }
                                Text("Translate: language of the text", style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(start = 20.dp, top = 12.dp))
                                OcrLang.entries.forEach { l ->
                                    OptionRow(
                                        l.label,
                                        if (l.script == null) "Always uses Tesseract" else null,
                                        ocrLang == l
                                    ) { ocrLang = l; prefs.edit().putString("ocr_lang", l.key).apply() }
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Remember cookies", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Fewer captchas once solved. Off = a fresh, anonymous browser each time.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(checked = keepCookies, onCheckedChange = ::updateKeepCookies)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun EngineRow(e: SearchEngine, selected: Boolean, onPick: () -> Unit) =
        OptionRow(e.label, e.note, selected, onPick)

    @androidx.compose.runtime.Composable
    private fun OptionRow(label: String, note: String?, selected: Boolean, onPick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onPick() }.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onPick)
            Column(Modifier.padding(start = 4.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    private fun chooseEngine(e: SearchEngine, forImage: Boolean) {
        if (forImage) imageEngine = e else textEngine = e
        prefs.edit().putString(if (forImage) "image_engine" else "text_engine", e.key).apply()
    }

    private fun updateKeepCookies(on: Boolean) {
        keepCookies = on
        prefs.edit().putBoolean("keep_cookies", on).apply()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        assistantOk = isAssistant()
        a11yOk = CircleToSearchAccessibilityService.isEnabled(this)
        networkOk = checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
    }

    private fun isAssistant(): Boolean {
        val rm = getSystemService(RoleManager::class.java)
        if (rm?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true) return true
        return listOf("assistant", "voice_interaction_service").any { key ->
            runCatching { Settings.Secure.getString(contentResolver, key) }.getOrNull()?.contains(packageName) == true
        }
    }

    private fun openAssistantSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }
}

@Composable
private fun SetupRow(title: String, body: String, ok: Boolean, action: String, onAction: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (ok) "On" else "Off",
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!ok) Button(onClick = onAction) { Text(action) }
        }
    }
}
