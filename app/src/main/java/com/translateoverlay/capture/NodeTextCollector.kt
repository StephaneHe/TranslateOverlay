package com.translateoverlay.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.translateoverlay.core.BlockSource
import com.translateoverlay.core.Box
import com.translateoverlay.core.TextBlock
import com.translateoverlay.core.WebTextJoiner

/**
 * Text of the screen from the accessibility tree, plus zones where OCR must be ignored: input
 * fields (the browser address bar: OCR reads "ynet.co. il/home/C" there, which no URL filter can
 * reliably catch) and system windows (status bar).
 */
data class ScreenText(val blocks: List<TextBlock>, val ignoreZones: List<Box>)

/** Extracts visible text (exact strings + screen bounds) from the accessibility tree of app windows. */
object NodeTextCollector {
    private const val MAX_NODES = 4000
    private const val MAX_DEPTH = 80

    fun collect(windows: List<AccessibilityWindowInfo>, ownPackage: String, screen: Box): ScreenText {
        val out = ArrayList<TextBlock>()
        val zones = ArrayList<Box>()
        val occluders = ArrayList<Box>()
        var budget = MAX_NODES
        val rect = Rect()

        // Topmost first, so lower windows hidden behind a dialog/split pane are skipped.
        for (window in windows.sortedByDescending { it.layer }) {
            window.getBoundsInScreen(rect)
            val windowBox = Box(rect.left, rect.top, rect.right, rect.bottom).intersect(screen)
            val isApp = window.type == AccessibilityWindowInfo.TYPE_APPLICATION
            if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM && !windowBox.isEmpty) zones += windowBox
            val root = if (isApp) window.root else null
            if (root != null && root.packageName?.toString() != ownPackage && !windowBox.isEmpty) {
                val higher = occluders.toList()
                val webFragments = ArrayList<WebTextJoiner.Run>()

                fun boxOf(node: AccessibilityNodeInfo): Box? {
                    node.getBoundsInScreen(rect)
                    val box = Box(rect.left, rect.top, rect.right, rect.bottom).intersect(windowBox)
                    return if (box.isEmpty || higher.any { it.contains(box) }) null else box
                }

                fun isWebView(node: AccessibilityNodeInfo) =
                    node.className?.toString() == "android.webkit.WebView"

                /**
                 * Web pages expose each inline run (link, bold span…) as its own leaf node. Leaves are
                 * grouped by their parent element (links are transparent), so a paragraph is
                 * translated as a whole instead of word by word.
                 */
                fun visit(node: AccessibilityNodeInfo, depth: Int, inWeb: Boolean, paragraph: Any?, clip: Box) {
                    if (budget-- <= 0 || depth > MAX_DEPTH || !node.isVisibleToUser) return
                    val web = inWeb || isWebView(node)
                    node.getBoundsInScreen(rect)
                    val own = Box(rect.left, rect.top, rect.right, rect.bottom)
                    val clipped = own.intersect(clip)
                    if (web && WebTextJoiner.isClippedAway(own, clip)) return
                    val childClip = if (web && !own.isEmpty) clipped else clip
                    val text = node.text?.toString()
                    val usable = !text.isNullOrBlank() && !node.isPassword && !node.isEditable
                    if (node.isEditable) boxOf(node)?.let { zones += it }
                    if (web) {
                        if (node.childCount == 0) {
                            if (usable) boxOf(node)?.let { webFragments += WebTextJoiner.Run(text!!, it, paragraph ?: node) }
                            return
                        }
                        // A single-child clickable node is an inline link: it stays in its paragraph.
                        val inlineLink = paragraph != null && node.childCount == 1 && node.isClickable
                        val group = if (inlineLink) paragraph!! else node
                        for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1, true, group, childClip) }
                        return
                    }
                    if (usable) boxOf(node)?.let { out += TextBlock(text!!.trim(), it, BlockSource.NODE) }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1, false, null, childClip) }
                }
                visit(root, 0, false, null, windowBox)

                out += WebTextJoiner.join(webFragments)
            }
            if (!windowBox.isEmpty && window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                occluders += windowBox
            }
        }
        return ScreenText(out, zones)
    }
}
