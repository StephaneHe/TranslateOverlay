package com.translateoverlay.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.translateoverlay.core.BlockSource
import com.translateoverlay.core.Box
import com.translateoverlay.core.TextBlock

/** Extracts visible text (exact strings + screen bounds) from the accessibility tree of app windows. */
object NodeTextCollector {
    private const val MAX_NODES = 4000
    private const val MAX_DEPTH = 80

    fun collect(windows: List<AccessibilityWindowInfo>, ownPackage: String, screen: Box): List<TextBlock> {
        val out = ArrayList<TextBlock>()
        val occluders = ArrayList<Box>()
        var budget = MAX_NODES
        val rect = Rect()

        // Topmost first, so lower windows hidden behind a dialog/split pane are skipped.
        for (window in windows.sortedByDescending { it.layer }) {
            window.getBoundsInScreen(rect)
            val windowBox = Box(rect.left, rect.top, rect.right, rect.bottom).intersect(screen)
            val isApp = window.type == AccessibilityWindowInfo.TYPE_APPLICATION
            val root = if (isApp) window.root else null
            if (root != null && root.packageName?.toString() != ownPackage && !windowBox.isEmpty) {
                val higher = occluders.toList()
                fun visit(node: AccessibilityNodeInfo, depth: Int) {
                    if (budget-- <= 0 || depth > MAX_DEPTH || !node.isVisibleToUser) return
                    val text = node.text?.toString()
                    if (!text.isNullOrBlank() && !node.isPassword && !node.isEditable) {
                        node.getBoundsInScreen(rect)
                        val box = Box(rect.left, rect.top, rect.right, rect.bottom).intersect(windowBox)
                        if (!box.isEmpty && higher.none { it.contains(box) }) {
                            out += TextBlock(text.trim(), box, BlockSource.NODE)
                        }
                    }
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { visit(it, depth + 1) }
                    }
                }
                visit(root, 0)
            }
            if (!windowBox.isEmpty && window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                occluders += windowBox
            }
        }
        return out
    }
}
