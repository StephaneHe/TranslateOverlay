package com.translateoverlay.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.translateoverlay.TranslateOverlayApp
import com.translateoverlay.capture.NodeTextCollector
import com.translateoverlay.capture.OcrRecognizer
import com.translateoverlay.core.BubbleVisibilityPolicy
import com.translateoverlay.overlay.BubbleController
import com.translateoverlay.overlay.ScreenMetrics
import com.translateoverlay.overlay.TranslationOverlayView
import com.translateoverlay.pipeline.PipelineOutcome
import com.translateoverlay.pipeline.StyleEstimator
import com.translateoverlay.pipeline.TranslationPipeline
import com.translateoverlay.settings.Settings
import com.translateoverlay.settings.SettingsRepository
import com.translateoverlay.translate.TranslationEngine
import com.translateoverlay.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Single entry point of the app's runtime: floating bubble (accessibility overlay), foreground-app
 * tracking for exclusions, on-demand capture (accessibility tree + screenshot) and result overlay.
 */
class OverlayAccessibilityService : AccessibilityService() {

    private val scope = MainScope()
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var engine: TranslationEngine
    private lateinit var bubble: BubbleController
    private lateinit var pipeline: TranslationPipeline
    private val ocr by lazy { OcrRecognizer(this) }

    private var overlay: TranslationOverlayView? = null
    private var job: Job? = null
    private var bubbleVisible = false
    private var foregroundPackage: String? = null
    private var connected = false
    private var lastScreenshotAt = 0L
    private var transientPackages: Set<String> = emptySet()
    private val handler = Handler(Looper.getMainLooper())
    private val foregroundCheck = Runnable { refreshForeground() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = application as TranslateOverlayApp
        settingsRepo = app.settings
        engine = app.engine
        pipeline = TranslationPipeline(engine, ocr, StyleEstimator(resources.displayMetrics.scaledDensity))
        bubble = BubbleController(this, settingsRepo, ::onBubbleTap, ::openSettings)
        transientPackages = inputMethodPackages() + SYSTEM_UI

        scope.launch {
            settingsRepo.settings.collect { s ->
                bubble.applyAppearance(s.bubbleSizeDp, s.bubbleOpacity)
                updateBubble(s)
            }
        }
        connected = true
        _running.value = true
        refreshForeground()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Debounced: window events arrive in bursts during transitions.
                handler.removeCallbacks(foregroundCheck)
                handler.postDelayed(foregroundCheck, 150)
            }
        }
    }

    override fun onInterrupt() = Unit

    private fun refreshForeground() {
        if (!::bubble.isInitialized) return
        val pkg = runCatching { currentForegroundPackage() }.getOrNull()
        if (pkg != null && pkg != foregroundPackage && pkg !in transientPackages) {
            // The screen content changed: a pending or shown translation no longer matches.
            if (foregroundPackage != null) {
                job?.cancel()
                dismissOverlay()
            }
            foregroundPackage = pkg
        }
        updateBubble(settingsRepo.settings.value)
    }

    private fun currentForegroundPackage(): String? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val window = apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused }
            ?: apps.maxByOrNull { it.layer }
        return window?.root?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString()
    }

    private fun updateBubble(s: Settings) {
        // Once disconnected, the window token is dead: adding the bubble again would crash.
        if (!connected) return
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val show = !locked && overlay == null && BubbleVisibilityPolicy.shouldShow(
            bubbleEnabled = s.bubbleEnabled,
            foregroundPackage = foregroundPackage,
            excludedPackages = s.excludedPackages,
            ownPackage = packageName,
            transientPackages = transientPackages,
            current = bubbleVisible,
        )
        bubble.setVisible(show)
        bubbleVisible = show
    }

    private fun onBubbleTap() {
        if (job?.isActive == true) return
        job = scope.launch {
            bubble.setBusy(true)
            try {
                translateScreen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Erreur : ${e.message ?: e.javaClass.simpleName}")
            } finally {
                bubble.setBusy(false)
            }
        }
    }

    private suspend fun translateScreen() {
        val startedAt = SystemClock.uptimeMillis()
        val s = settingsRepo.settings.value
        val screen = ScreenMetrics.bounds(this)
        val nodes = NodeTextCollector.collect(windows, packageName, screen)

        val screenshot = if (s.ocrEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bubble.setHidden(true)
            try {
                delay(120) // let the bubble disappear from the next frame
                takeScreenshotBitmap()
            } finally {
                bubble.setHidden(false)
            }
        } else {
            null
        }

        val outcome = try {
            pipeline.run(nodes, screenshot, s) { toast(it) }
        } finally {
            screenshot?.recycle()
        }
        Log.i(TAG, "translateScreen: ${nodes.size} nodes, ${outcome.javaClass.simpleName} in ${SystemClock.uptimeMillis() - startedAt} ms")
        when (outcome) {
            is PipelineOutcome.Success -> {
                val langs = outcome.sourceLanguages.joinToString(", ") { it.uppercase() }
                showOverlay(outcome, s.targetLanguage, "$langs → ${s.targetLanguage.uppercase()} · touchez hors du texte pour fermer")
            }
            is PipelineOutcome.NoResult -> toast(outcome.message)
            is PipelineOutcome.Failure -> toast(outcome.message)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshotBitmap(): Bitmap? {
        // The platform refuses more than ~1 screenshot per second.
        val wait = MIN_SCREENSHOT_INTERVAL_MS - (SystemClock.uptimeMillis() - lastScreenshotAt)
        if (wait > 0) delay(wait)
        lastScreenshotAt = SystemClock.uptimeMillis()
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    cont.resume(bitmap)
                }

                // e.g. ERROR_TAKE_SCREENSHOT_SECURE_WINDOW: fall back to the accessibility tree only.
                override fun onFailure(errorCode: Int) = cont.resume(null)
            })
        }
    }

    private fun showOverlay(outcome: PipelineOutcome.Success, targetLanguage: String, caption: String) {
        dismissOverlay()
        val screen = ScreenMetrics.bounds(this)
        val view = TranslationOverlayView(this, outcome.blocks, targetLanguage, caption, screen.height, ::dismissOverlay)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        getSystemService(WindowManager::class.java).addView(view, params)
        overlay = view
        updateBubble(settingsRepo.settings.value)
    }

    private fun dismissOverlay() {
        val view = overlay ?: return
        overlay = null
        runCatching { getSystemService(WindowManager::class.java).removeView(view) }
        if (::bubble.isInitialized) updateBubble(settingsRepo.settings.value)
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun inputMethodPackages(): Set<String> =
        getSystemService(InputMethodManager::class.java)?.enabledInputMethodList
            ?.map { it.packageName }?.toSet() ?: emptySet()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        _running.value = false
        scope.coroutineContext[Job]?.cancelChildren()
        dismissOverlay()
        if (::bubble.isInitialized) bubble.destroy()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        _running.value = false
        scope.cancel()
        handler.removeCallbacks(foregroundCheck)
        dismissOverlay()
        if (::bubble.isInitialized) bubble.destroy()
        ocr.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TranslateOverlay"
        private const val MIN_SCREENSHOT_INTERVAL_MS = 1100L
        private val SYSTEM_UI = setOf("com.android.systemui")

        private val _running = MutableStateFlow(false)
        /** Whether the service is currently connected (observed by the UI). */
        val running: StateFlow<Boolean> = _running.asStateFlow()
    }
}
