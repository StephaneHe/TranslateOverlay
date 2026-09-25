package com.translateoverlay.core

import kotlin.math.max
import kotlin.math.min

/** Axis-aligned rectangle in screen pixels. Pure Kotlin so it can be unit-tested without Android. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = if (isEmpty) 0L else width.toLong() * height.toLong()
    val isEmpty: Boolean get() = right <= left || bottom <= top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun intersect(other: Box): Box = Box(
        max(left, other.left), max(top, other.top),
        min(right, other.right), min(bottom, other.bottom),
    )

    fun intersectionArea(other: Box): Long = intersect(other).area

    fun contains(other: Box): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    fun containsPoint(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    fun union(other: Box): Box = Box(
        min(left, other.left), min(top, other.top),
        max(right, other.right), max(bottom, other.bottom),
    )
}

enum class BlockSource { NODE, OCR }

enum class TextAlign { START, CENTER, END }

/** @property confidence OCR confidence in [0, 1] (1 when unknown). */
data class TextLine(val text: String, val box: Box, val confidence: Float = 1f)

/** A piece of on-screen text to translate, with its location and optional OCR line geometry. */
data class TextBlock(
    val text: String,
    val box: Box,
    val source: BlockSource,
    val lines: List<TextLine> = emptyList(),
)

/** @property align physical alignment measured on screen; meaningful only when [alignMeasured]. */
data class BlockStyle(
    val textSizePx: Float,
    val textColor: Int,
    val backgroundColor: Int,
    val align: TextAlign,
    val bold: Boolean,
    val alignMeasured: Boolean = false,
    val outlineColor: Int? = null,
)

data class TranslatedBlock(
    val block: TextBlock,
    val style: BlockStyle,
    val sourceLanguage: String,
    val translation: String,
)
