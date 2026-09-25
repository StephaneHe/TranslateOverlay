package com.translateoverlay

import android.app.Application
import com.translateoverlay.settings.SettingsRepository
import com.translateoverlay.translate.SecretStore
import com.translateoverlay.translate.TranslationEngine
import com.translateoverlay.translate.TranslatorRouter

class TranslateOverlayApp : Application() {
    lateinit var settings: SettingsRepository
        private set
    lateinit var engine: TranslationEngine
        private set
    lateinit var secrets: SecretStore
        private set
    lateinit var router: TranslatorRouter
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        engine = TranslationEngine(this)
        secrets = SecretStore(this)
        router = TranslatorRouter(engine, secrets)
    }
}
