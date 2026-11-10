package com.tapnex.autoclicker.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/** Real physical screen size in pixels for the current rotation (includes status bar and cutout). */
object ScreenUtil {
    fun realSize(context: Context): Pair<Int, Int> {
        val dm = DisplayMetrics()
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        return Pair(dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))
    }
}
