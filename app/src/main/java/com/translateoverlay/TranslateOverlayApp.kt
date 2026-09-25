package com.translateoverlay

import android.app.Application
import com.translateoverlay.settings.SettingsRepository
import com.translateoverlay.translate.TranslationEngine

class TranslateOverlayApp : Application() {
    lateinit var settings: SettingsRepository
        private set
    lateinit var engine: TranslationEngine
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        engine = TranslationEngine(this)
    }
}
