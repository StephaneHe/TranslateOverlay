package com.translateoverlay.settings

import android.content.Context
import android.content.SharedPreferences
import com.translateoverlay.core.Script
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** @property covers writing systems the OCR model reads; text in other scripts comes out as garbage. */
enum class OcrScript(val label: String, val covers: Set<Script>) {
    /** Latin by default, plus the models matching the scripts found on screen (see OcrRecognizer). */
    AUTO("Automatique (recommandé)", setOf(Script.LATIN)),
    LATIN("Latin uniquement", setOf(Script.LATIN)),
    CHINESE("Chinois", setOf(Script.HAN, Script.LATIN)),
    JAPANESE("Japonais", setOf(Script.JAPANESE, Script.HAN, Script.LATIN)),
    KOREAN("Coréen", setOf(Script.KOREAN, Script.LATIN)),
    DEVANAGARI("Devanagari", setOf(Script.DEVANAGARI, Script.LATIN)),
    HEBREW("Hébreu (Tesseract, embarqué)", setOf(Script.HEBREW)),
}

data class Settings(
    val targetLanguage: String = "fr",
    val excludedPackages: Set<String> = emptySet(),
    val bubbleEnabled: Boolean = true,
    val ocrEnabled: Boolean = true,
    val ocrScript: OcrScript = OcrScript.AUTO,
    val wifiOnlyDownloads: Boolean = true,
    val bubbleSizeDp: Int = 52,
    val bubbleOpacity: Float = 0.85f,
)

/** SharedPreferences-backed settings exposed as a [StateFlow] shared by the UI and the service. */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    // Kept as a field: SharedPreferences only holds listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _settings.value = read() }

    init {
        // 1.2.0: the manual OCR choice (Latin by default) was replaced by AUTO; drop the stale key.
        if (prefs.contains(K_SCRIPT_V1)) prefs.edit().remove(K_SCRIPT_V1).apply()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            targetLanguage = prefs.getString(K_TARGET, d.targetLanguage) ?: d.targetLanguage,
            excludedPackages = prefs.getStringSet(K_EXCLUDED, d.excludedPackages)?.toSet() ?: emptySet(),
            bubbleEnabled = prefs.getBoolean(K_BUBBLE, d.bubbleEnabled),
            ocrEnabled = prefs.getBoolean(K_OCR, d.ocrEnabled),
            ocrScript = prefs.getString(K_SCRIPT, null)
                ?.let { runCatching { OcrScript.valueOf(it) }.getOrNull() } ?: d.ocrScript,
            wifiOnlyDownloads = prefs.getBoolean(K_WIFI, d.wifiOnlyDownloads),
            bubbleSizeDp = prefs.getInt(K_BUBBLE_SIZE, d.bubbleSizeDp),
            bubbleOpacity = prefs.getFloat(K_BUBBLE_OPACITY, d.bubbleOpacity),
        )
    }

    fun setTargetLanguage(code: String) = prefs.edit().putString(K_TARGET, code).apply()
    fun setBubbleEnabled(v: Boolean) = prefs.edit().putBoolean(K_BUBBLE, v).apply()
    fun setOcrEnabled(v: Boolean) = prefs.edit().putBoolean(K_OCR, v).apply()
    fun setOcrScript(v: OcrScript) = prefs.edit().putString(K_SCRIPT, v.name).apply()
    fun setWifiOnlyDownloads(v: Boolean) = prefs.edit().putBoolean(K_WIFI, v).apply()
    fun setBubbleSizeDp(v: Int) = prefs.edit().putInt(K_BUBBLE_SIZE, v).apply()
    fun setBubbleOpacity(v: Float) = prefs.edit().putFloat(K_BUBBLE_OPACITY, v).apply()

    fun setExcluded(pkg: String, excluded: Boolean) {
        val next = _settings.value.excludedPackages.toMutableSet()
        if (excluded) next += pkg else next -= pkg
        prefs.edit().putStringSet(K_EXCLUDED, next).apply()
    }

    /** Bubble position is not part of [Settings] to avoid re-emitting on every drag. */
    fun bubblePosition(): Pair<Int, Int>? =
        if (prefs.contains(K_BUBBLE_X)) prefs.getInt(K_BUBBLE_X, 0) to prefs.getInt(K_BUBBLE_Y, 0) else null

    fun saveBubblePosition(x: Int, y: Int) =
        prefs.edit().putInt(K_BUBBLE_X, x).putInt(K_BUBBLE_Y, y).apply()

    private companion object {
        const val K_TARGET = "target_language"
        const val K_EXCLUDED = "excluded_packages"
        const val K_BUBBLE = "bubble_enabled"
        const val K_OCR = "ocr_enabled"
        // v2: AUTO became the default; earlier explicit choices (Latin was the only default) are reset.
        const val K_SCRIPT = "ocr_script_v2"
        const val K_SCRIPT_V1 = "ocr_script"
        const val K_WIFI = "wifi_only_downloads"
        const val K_BUBBLE_SIZE = "bubble_size_dp"
        const val K_BUBBLE_OPACITY = "bubble_opacity"
        const val K_BUBBLE_X = "bubble_x"
        const val K_BUBBLE_Y = "bubble_y"
    }
}
