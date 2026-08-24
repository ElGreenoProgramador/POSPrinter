package com.example.sunmiprinter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.roundToInt

/**
 * Thermal printers only understand black-and-white dots. Sunmi's printBitmap()
 * will auto-threshold a color image for you, but the result looks muddy for
 * photos/video frames. Applying Floyd-Steinberg dithering ourselves first
 * gives noticeably better-looking prints (this is the same trick used by
 * most receipt-printer photo apps).
 */
object PrintImageUtils {

    /** Scales [src] to [targetWidth] px wide, preserving aspect ratio. */
    fun scaleToPrinterWidth(src: Bitmap, targetWidth: Int): Bitmap {
        if (src.width == targetWidth) return src
        val targetHeight = (src.height.toFloat() * targetWidth / src.width).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true)
    }

    /** Converts to grayscale, then Floyd-Steinberg dithers to pure black/white. */
    fun ditherForThermalPrint(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val gray = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = src.getPixel(x, y)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                gray[y * w + x] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val old = gray[idx]
                val new = if (old < 128f) 0f else 255f
                val err = old - new
                out.setPixel(x, y, if (new < 1f) Color.BLACK else Color.WHITE)
                if (x + 1 < w) gray[idx + 1] += err * 7f / 16f
                if (y + 1 < h) {
                    if (x - 1 >= 0) gray[idx + w - 1] += err * 3f / 16f
                    gray[idx + w] += err * 5f / 16f
                    if (x + 1 < w) gray[idx + w + 1] += err * 1f / 16f
                }
            }
        }
        return out
    }

    /**
     * Shifts every RGB channel by a flat amount so the image prints lighter or
     * darker. [brightness] is -100 (much darker) to +100 (much lighter), 0 = no
     * change. Applied *before* dithering, since dithering is what actually
     * decides which pixels end up as printed dots - brightening/darkening the
     * source first shifts that threshold decision, exactly like adjusting
     * exposure before converting a photo to halftone.
     */
    fun adjustBrightness(src: Bitmap, brightness: Int): Bitmap {
        val clamped = brightness.coerceIn(-100, 100)
        if (clamped == 0) return src
        val delta = clamped * 2.55f // map -100..100 -> -255..255
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = src.getPixel(x, y)
                val r = (Color.red(p) + delta).roundToInt().coerceIn(0, 255)
                val g = (Color.green(p) + delta).roundToInt().coerceIn(0, 255)
                val b = (Color.blue(p) + delta).roundToInt().coerceIn(0, 255)
                out.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        return out
    }

    /**
     * Places [printedBitmap] (already scaled+dithered, i.e. the exact pixels
     * that will be sent to the printer) onto a white canvas the full width of
     * the paper, positioned per [alignment]. This is what a print preview
     * should show: not just the photo, but where on the receipt it will
     * actually land.
     */
    fun composePrintPreview(printedBitmap: Bitmap, canvasWidthPx: Int, alignment: Int): Bitmap {
        val canvas = Bitmap.createBitmap(canvasWidthPx, printedBitmap.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)
        c.drawColor(Color.WHITE)
        val x = when (alignment) {
            SunmiPrinterHelper.ALIGN_LEFT -> 0
            SunmiPrinterHelper.ALIGN_RIGHT -> canvasWidthPx - printedBitmap.width
            else -> (canvasWidthPx - printedBitmap.width) / 2
        }.coerceAtLeast(0)
        c.drawBitmap(printedBitmap, x.toFloat(), 0f, null)
        return canvas
    }

    /** Full pipeline: scale, adjust brightness, then dither, ready to hand to
     *  SunmiPrinterHelper.printBitmap(). [scalePercent] (1-100) controls what
     *  fraction of the printer's full dot width the image should occupy - e.g.
     *  60 prints the image at 60% width, leaving the rest of the line for the
     *  alignment (left/center/right) set via SunmiPrinterHelper.setAlignment
     *  to take effect. [brightness] is -100..100, see adjustBrightness(). */
    fun prepareForPrint(
        src: Bitmap,
        printerWidthPx: Int,
        scalePercent: Int = 100,
        brightness: Int = 0
    ): Bitmap {
        val targetWidth = (printerWidthPx * scalePercent.coerceIn(1, 100) / 100f)
            .roundToInt()
            .coerceAtLeast(1)
        val scaled = scaleToPrinterWidth(src, targetWidth)
        val brightened = adjustBrightness(scaled, brightness)
        return ditherForThermalPrint(brightened)
    }
}
