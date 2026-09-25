package com.translateoverlay.pipeline

import android.util.Log
import com.translateoverlay.BuildConfig
import com.translateoverlay.core.TextBlock

/** Pipeline diagnostics (debug builds only, info level: some OEM builds drop debug logs): `adb logcat -s TO-Diag`. */
object Diag {
    const val TAG = "TO-Diag"
    val enabled: Boolean = BuildConfig.DEBUG

    inline fun log(message: () -> String) {
        if (enabled) Log.i(TAG, message())
    }

    fun describe(block: TextBlock): String {
        val b = block.box
        val text = block.text.take(60).replace('\n', ' ')
        val conf = block.lines.takeIf { it.isNotEmpty() }?.let { l -> " conf=%.2f".format(l.minOf { it.confidence }) } ?: ""
        return "${block.source}[${b.left},${b.top},${b.right},${b.bottom}]$conf \"$text\""
    }
}
