package com.cadrega.posprinter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.roundToInt

/**
 * Thermal printers only understand black-and-white dots. The hardware's native printBitmap()
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

    /** Converts to grayscale, applies brightness, then Floyd-Steinberg dithers to pure black/white. */
    fun ditherForThermalPrint(src: Bitmap, brightness: Int = 0): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val delta = brightness.coerceIn(-100, 100) * 2.55f
        val gray = FloatArray(w * h)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b + delta
        }

        val outPixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val old = gray[idx]
                val new = if (old < 128f) 0f else 255f
                val err = old - new
                
                outPixels[idx] = if (new < 1f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

                if (x + 1 < w) gray[idx + 1] += err * 7f / 16f
                if (y + 1 < h) {
                    if (x - 1 >= 0) gray[idx + w - 1] += err * 3f / 16f
                    gray[idx + w] += err * 5f / 16f
                    if (x + 1 < w) gray[idx + w + 1] += err * 1f / 16f
                }
            }
        }
        
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
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
        val canvas = Bitmap.createBitmap(canvasWidthPx, printedBitmap.height, Bitmap.Config.RGB_565)
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

    /** Full pipeline: scale, then dither (with brightness), ready to hand to
     *  SunmiPrinterHelper.printBitmap(). [scalePercent] (1-100) controls what
     *  fraction of the printer's full dot width the image should occupy - e.g.
     *  60 prints the image at 60% width, leaving the rest of the line for the
     *  alignment (left/center/right) set via SunmiPrinterHelper.setAlignment
     *  to take effect. [brightness] is -100..100, shifts threshold lighter/darker. */
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
        return ditherForThermalPrint(scaled, brightness)
    }
}
