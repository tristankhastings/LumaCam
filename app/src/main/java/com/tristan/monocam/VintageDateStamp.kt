package com.tristan.monocam

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

object VintageDateStamp {

    private const val SEG_A = 1 shl 0
    private const val SEG_B = 1 shl 1
    private const val SEG_C = 1 shl 2
    private const val SEG_D = 1 shl 3
    private const val SEG_E = 1 shl 4
    private const val SEG_F = 1 shl 5
    private const val SEG_G = 1 shl 6

    private class StampCacheEntry(
        val segmentsPath: Path,
        val corePath: Path,
        val outerBloomPaint: Paint,
        val midGlowPaint: Paint,
        val bodyPaint: Paint,
        val corePaint: Paint
    )

    private val stampCache = ConcurrentHashMap<String, StampCacheEntry>()

    fun formatCurrentDate(): String {
        return SimpleDateFormat("yyyy MM dd", Locale.US).format(Date())
    }

    fun measureWidth(text: String, digitHeight: Float): Float {
        val digitW = digitHeight * 0.54f
        val digitGap = digitHeight * 0.22f
        val spaceW = digitHeight * 0.52f
        var width = 0f
        text.forEachIndexed { index, ch ->
            if ((index > 0) && (ch != ' ') && (text[index - 1] != ' ')) {
                width += digitGap
            }
            width += if (ch == ' ') spaceW else digitW
        }
        return width
    }

    fun drawInBottomRight(
        canvas: Canvas,
        text: String,
        surfaceWidth: Float,
        surfaceHeight: Float
    ) {
        val longSide = max(surfaceWidth, surfaceHeight)
        val digitHeight = (longSide * 0.026f).coerceAtLeast(14f)
        val stampWidth = measureWidth(text, digitHeight)
        val marginRight = surfaceWidth * 0.046f
        val marginBottom = surfaceHeight * 0.046f
        val left = (surfaceWidth - marginRight - stampWidth).coerceAtLeast(12f)
        val top = (surfaceHeight - marginBottom - digitHeight).coerceAtLeast(12f)
        drawAt(canvas, text, left, top, digitHeight)
    }

    fun drawAt(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        digitHeight: Float
    ) {
        val cacheKey = "$text|${(digitHeight * 10f).toInt()}"
        val entry = stampCache.getOrPut(cacheKey) {
            if (stampCache.size > 6) stampCache.clear()
            buildStampEntry(text, digitHeight)
        }

        canvas.save()
        canvas.translate(left + digitHeight * 0.12f, top)
        canvas.skew(-0.12f, 0f)

        canvas.drawPath(entry.segmentsPath, entry.outerBloomPaint)
        canvas.drawPath(entry.segmentsPath, entry.midGlowPaint)
        canvas.drawPath(entry.segmentsPath, entry.bodyPaint)
        canvas.drawPath(entry.corePath, entry.corePaint)

        canvas.restore()
    }

    private fun buildStampEntry(text: String, digitHeight: Float): StampCacheEntry {
        val digitW = digitHeight * 0.54f
        val digitGap = digitHeight * 0.22f
        val spaceW = digitHeight * 0.52f
        val thickness = (digitHeight * 0.145f).coerceAtLeast(1.4f)

        val segmentsPath = Path()
        val corePath = Path()
        var cursorX = 0f

        text.forEachIndexed { index, ch ->
            if ((index > 0) && (ch != ' ') && (text[index - 1] != ' ')) {
                cursorX += digitGap
            }
            if (ch != ' ') {
                val mask = digitMask(ch)
                if (mask != 0) {
                    appendDigitSegments(segmentsPath, mask, cursorX, digitW, digitHeight, thickness)
                    appendDigitSegments(corePath, mask, cursorX, digitW, digitHeight, thickness * 0.56f)
                }
                cursorX += digitW
            } else {
                cursorX += spaceW
            }
        }

        return StampCacheEntry(
            segmentsPath = segmentsPath,
            corePath = corePath,
            outerBloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(105, 255, 36, 0)
                maskFilter = BlurMaskFilter((thickness * 2.1f).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
            },
            midGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(175, 255, 68, 12)
                maskFilter = BlurMaskFilter((thickness * 0.85f).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
            },
            bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(230, 255, 86, 22)
                maskFilter = BlurMaskFilter((thickness * 0.16f).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
            },
            corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(150, 255, 148, 82)
            }
        )
    }

    private fun digitMask(ch: Char): Int = when (ch) {
        '0' -> SEG_A or SEG_B or SEG_C or SEG_D or SEG_E or SEG_F
        '1' -> SEG_B or SEG_C
        '2' -> SEG_A or SEG_B or SEG_G or SEG_E or SEG_D
        '3' -> SEG_A or SEG_B or SEG_G or SEG_C or SEG_D
        '4' -> SEG_F or SEG_G or SEG_B or SEG_C
        '5' -> SEG_A or SEG_F or SEG_G or SEG_C or SEG_D
        '6' -> SEG_A or SEG_F or SEG_G or SEG_E or SEG_C or SEG_D
        '7' -> SEG_A or SEG_B or SEG_C
        '8' -> SEG_A or SEG_B or SEG_C or SEG_D or SEG_E or SEG_F or SEG_G
        '9' -> SEG_A or SEG_B or SEG_C or SEG_D or SEG_F or SEG_G
        else -> 0
    }

    private fun appendDigitSegments(
        outPath: Path,
        mask: Int,
        ox: Float,
        w: Float,
        h: Float,
        t: Float
    ) {
        val halfT = t * 0.5f
        val gap = t * 0.42f
        val midY = h * 0.5f
        val leftX = ox + halfT
        val rightX = ox + w - halfT
        val topY = halfT
        val bottomY = h - halfT

        if ((mask and SEG_A) != 0) {
            addHorizontalSegment(outPath, leftX + gap, rightX - gap, topY, halfT)
        }
        if ((mask and SEG_G) != 0) {
            addHorizontalSegment(outPath, leftX + gap, rightX - gap, midY, halfT)
        }
        if ((mask and SEG_D) != 0) {
            addHorizontalSegment(outPath, leftX + gap, rightX - gap, bottomY, halfT)
        }
        if ((mask and SEG_F) != 0) {
            addVerticalSegment(outPath, leftX, topY + gap, midY - gap, halfT)
        }
        if ((mask and SEG_B) != 0) {
            addVerticalSegment(outPath, rightX, topY + gap, midY - gap, halfT)
        }
        if ((mask and SEG_E) != 0) {
            addVerticalSegment(outPath, leftX, midY + gap, bottomY - gap, halfT)
        }
        if ((mask and SEG_C) != 0) {
            addVerticalSegment(outPath, rightX, midY + gap, bottomY - gap, halfT)
        }
    }

    private fun addHorizontalSegment(path: Path, x1: Float, x2: Float, cy: Float, halfT: Float) {
        if (x2 <= x1 + halfT * 1.4f) return
        val tip = halfT * 0.85f
        path.moveTo(x1, cy)
        path.lineTo(x1 + tip, cy - halfT)
        path.lineTo(x2 - tip, cy - halfT)
        path.lineTo(x2, cy)
        path.lineTo(x2 - tip, cy + halfT)
        path.lineTo(x1 + tip, cy + halfT)
        path.close()
    }

    private fun addVerticalSegment(path: Path, cx: Float, y1: Float, y2: Float, halfT: Float) {
        if (y2 <= y1 + halfT * 1.4f) return
        val tip = halfT * 0.85f
        path.moveTo(cx, y1)
        path.lineTo(cx + halfT, y1 + tip)
        path.lineTo(cx + halfT, y2 - tip)
        path.lineTo(cx, y2)
        path.lineTo(cx - halfT, y2 - tip)
        path.lineTo(cx - halfT, y1 + tip)
        path.close()
    }
}
