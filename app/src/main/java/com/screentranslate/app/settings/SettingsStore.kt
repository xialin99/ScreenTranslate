package com.screentranslate.app.settings

import android.content.Context

class SettingsStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("screen_translate_settings", Context.MODE_PRIVATE)

    var engine: String
        get() = sp.getString(KEY_ENGINE, ENGINE_FREE) ?: ENGINE_FREE
        set(value) {
            sp.edit().putString(KEY_ENGINE, value).apply()
        }

    var baiduAppId: String
        get() = sp.getString(KEY_BAIDU_APP_ID, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_BAIDU_APP_ID, value).apply()
        }

    var baiduKey: String
        get() = sp.getString(KEY_BAIDU_KEY, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_BAIDU_KEY, value).apply()
        }

    var autoMode: Boolean
        get() = sp.getBoolean(KEY_AUTO_MODE, false)
        set(value) {
            sp.edit().putBoolean(KEY_AUTO_MODE, value).apply()
        }

    var touchMode: Boolean
        get() = sp.getBoolean(KEY_TOUCH_MODE, false)
        set(value) {
            sp.edit().putBoolean(KEY_TOUCH_MODE, value).apply()
        }

    companion object {
        const val ENGINE_DEVICE = "device"
        const val ENGINE_BAIDU = "baidu"
        const val ENGINE_FREE = "free"

        private const val KEY_ENGINE = "engine"
        private const val KEY_BAIDU_APP_ID = "baidu_app_id"
        private const val KEY_BAIDU_KEY = "baidu_key"
        private const val KEY_AUTO_MODE = "auto_mode"
        private const val KEY_TOUCH_MODE = "touch_mode"
    }
}
