package com.screentranslate.app

import android.app.Application
import com.screentranslate.app.settings.SettingsStore
import com.screentranslate.app.translate.Glossary

class ScreenTranslateApp : Application() {

    val settings: SettingsStore by lazy { SettingsStore(this) }
    val glossary: Glossary by lazy { Glossary(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: ScreenTranslateApp
            private set
    }
}
