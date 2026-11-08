package com.screentranslate.app.util

import android.content.Context
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager

object DisplayUtils {

    fun statusBarHeight(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return runCatching {
            val wm = context.getSystemService(WindowManager::class.java)
            wm.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.statusBars()).top
        }.getOrDefault(0)
    }

    fun navigationBarHeight(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return runCatching {
            val wm = context.getSystemService(WindowManager::class.java)
            wm.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.navigationBars()).bottom
        }.getOrDefault(0)
    }
}
