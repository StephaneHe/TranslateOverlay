package com.translateoverlay.overlay

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import com.translateoverlay.core.Box

object ScreenMetrics {
    /** Full physical display bounds, in the same coordinate space as getBoundsInScreen(). */
    fun bounds(context: Context): Box {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val r = wm.maximumWindowMetrics.bounds
            Box(r.left, r.top, r.right, r.bottom)
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            Box(0, 0, p.x, p.y)
        }
    }
}
