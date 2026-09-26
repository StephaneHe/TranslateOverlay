package com.translateoverlay.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.translateoverlay.TranslateOverlayApp
import com.translateoverlay.translate.SecretStore
import com.translateoverlay.translate.TranslationProvider
import java.io.File

/**
 * DEBUG BUILDS ONLY (src/debug): installs an API key for emulator tests without it ever appearing
 * in a command line, an intent extra or a log. The key is written by stdin into the app's private
 * directory, then this receiver encrypts it (Keystore) and deletes the file:
 *
 *   adb shell run-as com.translateoverlay sh -c 'cat > files/debug_nvidia_key' < (key on stdin)
 *   adb shell am broadcast -n com.translateoverlay/.debug.DebugKeyReceiver [--es provider NVIDIA]
 *
 * Without the file, only the extras are applied: provider, target (language code), clear (deletes the key).
 */
class DebugKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TranslateOverlayApp
        val file = File(context.filesDir, "debug_nvidia_key")
        if (file.exists()) {
            val key = file.readText().trim()
            file.delete()
            app.secrets.put(SecretStore.NVIDIA_KEY, key)
            app.router.onKeyChanged()
            Log.i(TAG, "NVIDIA key installed (${key.length} chars), file deleted")
        }
        if (intent.getBooleanExtra("clear", false)) {
            app.secrets.put(SecretStore.NVIDIA_KEY, null)
            app.router.onKeyChanged()
            Log.i(TAG, "NVIDIA key deleted")
        }
        intent.getStringExtra("provider")?.let { name ->
            runCatching { TranslationProvider.valueOf(name) }.getOrNull()?.let {
                app.settings.setProvider(it)
                Log.i(TAG, "provider = $it")
            }
        }
        intent.getStringExtra("target")?.let {
            app.settings.setTargetLanguage(it)
            Log.i(TAG, "target = $it")
        }
        Log.i(TAG, "ready=${app.router.isReady(TranslationProvider.NVIDIA)}")
    }

    private companion object {
        const val TAG = "TO-Debug"
    }
}
