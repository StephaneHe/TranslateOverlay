package com.translateoverlay.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.translateoverlay.TranslateOverlayApp
import com.translateoverlay.service.OverlayAccessibilityService

enum class Screen { HOME, EXCLUDED_APPS, MODELS }

class MainActivity : ComponentActivity() {

    private val serviceEnabledState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TranslateOverlayApp
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Color(0xFF8AB0FF))
            else lightColorScheme(primary = Color(0xFF1E5EFF))
            MaterialTheme(colorScheme = scheme) {
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
                when (screen) {
                    Screen.HOME -> HomeScreen(
                        settings = app.settings,
                        engine = app.engine,
                        secrets = app.secrets,
                        router = app.router,
                        serviceEnabled = serviceEnabledState.value,
                        onOpenAccessibility = ::openAccessibilitySettings,
                        onOpenAppInfo = ::openAppInfo,
                        onNavigate = { screen = it },
                    )
                    Screen.EXCLUDED_APPS -> ExcludedAppsScreen(app.settings) { screen = Screen.HOME }
                    Screen.MODELS -> ModelsScreen(app.engine, app.settings) { screen = Screen.HOME }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabledState.value = isServiceEnabled(this) || OverlayAccessibilityService.running.value
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Android 13+: sideloaded apps must first "Allow restricted settings" from this screen. */
    private fun openAppInfo() {
        startActivity(
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        fun isServiceEnabled(context: Context): Boolean {
            val expected = ComponentName(context, OverlayAccessibilityService::class.java)
            val enabled = AndroidSettings.Secure.getString(
                context.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }

        val restrictedSettingsApply: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }
}
