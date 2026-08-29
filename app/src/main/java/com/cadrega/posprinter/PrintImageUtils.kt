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

    enum class DitherAlgorithm {
        FloydSteinberg, Atkinson, Stucki, Burkes, Sierra, JarvisJudiceNinke, Ordered
    }

    /** Converts to grayscale, applies brightness and gamma, then dithers to pure black/white. */
    fun ditherForThermalPrint(src: Bitmap, brightness: Int = 0, gamma: Float = 1.0f, algorithm: DitherAlgorithm = DitherAlgorithm.FloydSteinberg): Bitmap {
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
            
            // Grayscale + Brightness
            var gVal = 0.299f * r + 0.587f * g + 0.114f * b + delta
            
            // Gamma correction
            if (gamma != 1.0f) {
                gVal = Math.pow((gVal.coerceIn(0f, 255f) / 255.0).toDouble(), (1.0 / gamma).toDouble()).toFloat() * 255f
            }
            
            gray[i] = gVal
        }

        val outPixels = IntArray(w * h)
        
        if (algorithm == DitherAlgorithm.Ordered) {
            val bayer = arrayOf(
                intArrayOf(0, 32, 8, 40, 2, 34, 10, 42),
                intArrayOf(48, 16, 56, 24, 50, 18, 58, 26),
                intArrayOf(12, 44, 4, 36, 14, 46, 6, 38),
                intArrayOf(60, 28, 52, 20, 62, 30, 54, 22),
                intArrayOf(3, 35, 11, 43, 1, 33, 9, 41),
                intArrayOf(51, 19, 59, 27, 49, 17, 57, 25),
                intArrayOf(15, 47, 7, 39, 13, 45, 5, 37),
                intArrayOf(63, 31, 55, 23, 61, 29, 53, 21)
            )
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val threshold = bayer[y % 8][x % 8] * 4
                    outPixels[idx] = if (gray[idx] < threshold) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
        } else {
            // Error Diffusion Dithering
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val old = gray[idx]
                    val new = if (old < 128f) 0f else 255f
                    val err = old - new
                    
                    outPixels[idx] = if (new < 1f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

                    when (algorithm) {
                        DitherAlgorithm.FloydSteinberg -> {
                            distributeError(gray, x, y, w, h, err, floatArrayOf(7/16f), intArrayOf(1, 0))
                            distributeError(gray, x, y, w, h, err, floatArrayOf(3/16f, 5/16f, 1/16f), intArrayOf(-1, 1, 0, 1, 1, 1))
                        }
                        DitherAlgorithm.Atkinson -> {
                            distributeError(gray, x, y, w, h, err, floatArrayOf(1/8f, 1/8f), intArrayOf(1, 0, 2, 0))
                            distributeError(gray, x, y, w, h, err, floatArrayOf(1/8f, 1/8f, 1/8f), intArrayOf(-1, 1, 0, 1, 1, 1))
                            distributeError(gray, x, y, w, h, err, floatArrayOf(1/8f), intArrayOf(0, 2))
                        }
                        DitherAlgorithm.Stucki -> {
                            distributeError(gray, x, y, w, h, err, floatArrayOf(8/42f, 4/42f), intArrayOf(1, 0, 2, 0))
                            distributeError(gray, x, y, w, h, err, floatArrayOf(2/42f, 4/42f, 8/42f, 4/42f, 2/42f), intArrayOf(-2, 1, -1, 1, 0, 1, 1, 1, 2, 1))
                            distributeError(gray, x, y, w, h, err, floatArrayOf(1/42f, 2/42f, 4/42f, 2/42f, 1/42f), intArrayOf(-2, 2, -1, 2, 0, 2, 1, 2, 2, 2))
                        }
                        DitherAlgorithm.Burkes -> {
                            distributeError(gray, x, y, w, h, err, floatArrayOf(8/32f, 4/32f), intArrayOf(1, 0, 2, 0))
                            distributeError(gray, x, y, w, h, err, floatArrayOf(2/32f, 4/32f, 8/32f, 4/32f, 2/32f), intArrayOf(-2, 1, -1, 1, 0, 1, 1, 1, 2, 1))
                        }
                        DitherAlgorithm.Sierra -> {
                            distributeError(gray, x, y, w, h, err, floatArrayOf(5/32f, 3/32f), intArrayOf(1, 0, 2, 0))
                            distributeError(gray, x, y, w, h, err, floatArrayOf(2/32f, 4/32f, 5/32f, 4/32f, 2/32f), intArrayOf(-2, 1, -1, 1, 0, 1, 1, 1, 2, 1))
                            distributeError(gray, x, y, w, h, err, floatArrayOf(2/32f, 3/32f, 2/32f), intArrayOf(-1, 2, 0, 2, 1, 2))
                        }
                        DitherAlgorithm.JarvisJudiceNinke -> {
                            distributeError(gray, x, y, w, h, err, floatArrayOf(7/48f, 5/42f), intArrayOf(1, 0, 2, 0))
                            distributeError(gray, x, y, w, h, err, floatArrayOf(3/48f, 5/48f, 7/48f, 5/48f, 3/48f), intArrayOf(-2, 1, -1, 1, 0, 1, 1, 1, 2, 1))
                            distributeError(gray, x, y, w, h, err, floatArrayOf(1/48f, 3/48f, 5/48f, 3/48f, 1/48f), intArrayOf(-2, 2, -1, 2, 0, 2, 1, 2, 2, 2))
                        }
                        else -> {}
                    }
                }
            }
        }
        
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun distributeError(gray: FloatArray, x: Int, y: Int, w: Int, h: Int, err: Float, weights: FloatArray, offsets: IntArray) {
        for (i in weights.indices) {
            val ox = x + offsets[i * 2]
            val oy = y + offsets[i * 2 + 1]
            if (ox in 0 until w && oy in 0 until h) {
                gray[oy * w + ox] += err * weights[i]
            }
        }
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

    /** Full pipeline: scale, then dither (with brightness and gamma), ready to hand to
     *  SunmiPrinterHelper.printBitmap(). [scalePercent] (1-100) controls what
     *  fraction of the printer's full dot width the image should occupy - e.g.
     *  60 prints the image at 60% width, leaving the rest of the line for the
     *  alignment (left/center/right) set via SunmiPrinterHelper.setAlignment
     *  to take effect. [brightness] is -100..100, shifts threshold lighter/darker. */
    fun prepareForPrint(
        src: Bitmap,
        printerWidthPx: Int,
        scalePercent: Int = 100,
        brightness: Int = 0,
        gamma: Float = 1.0f,
        algorithm: DitherAlgorithm = DitherAlgorithm.FloydSteinberg
    ): Bitmap {
        val targetWidth = (printerWidthPx * scalePercent.coerceIn(1, 100) / 100f)
            .roundToInt()
            .coerceAtLeast(1)
        val scaled = scaleToPrinterWidth(src, targetWidth)
        return ditherForThermalPrint(scaled, brightness, gamma, algorithm)
    }
}
