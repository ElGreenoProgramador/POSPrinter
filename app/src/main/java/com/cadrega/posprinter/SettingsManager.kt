package com.cadrega.posprinter

import android.content.Context

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("pos_printer_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_PRINTER_WIDTH = "printer_width"
        const val KEY_HISTORY_LIMIT = "history_limit"
        const val KEY_FEED_LINES = "feed_lines"
        const val KEY_FONT_SCALE = "font_scale"
        
        // Photo Defaults
        const val KEY_PHOTO_SCALE = "photo_scale"
        const val KEY_PHOTO_BRIGHTNESS = "photo_brightness"
        const val KEY_PHOTO_GAMMA = "photo_gamma"
        const val KEY_PHOTO_ALIGN = "photo_align"
        const val KEY_PHOTO_DITHER = "photo_dither"

        // Batch Defaults
        const val KEY_BATCH_SCALE = "batch_scale"
        const val KEY_BATCH_BRIGHTNESS = "batch_brightness"
        const val KEY_BATCH_GAMMA = "batch_gamma"
        const val KEY_BATCH_ALIGN = "batch_align"
        const val KEY_BATCH_DITHER = "batch_dither"
        const val KEY_BATCH_GAP = "batch_gap"

        // Video Defaults
        const val KEY_VIDEO_SCALE = "video_scale"
        const val KEY_VIDEO_BRIGHTNESS = "video_brightness"
        const val KEY_VIDEO_GAMMA = "video_gamma"
        const val KEY_VIDEO_ALIGN = "video_align"
        const val KEY_VIDEO_DITHER = "video_dither"
        const val KEY_VIDEO_GAP = "video_gap"

        const val DEFAULT_PRINTER_WIDTH = SunmiPrinterHelper.PRINTER_WIDTH_58MM
        const val DEFAULT_HISTORY_LIMIT = 30
        const val DEFAULT_FEED_LINES = 4
        const val DEFAULT_FONT_SCALE = 1.0f
    }

    var printerWidth: Int
        get() = prefs.getInt(KEY_PRINTER_WIDTH, DEFAULT_PRINTER_WIDTH)
        set(value) = prefs.edit().putInt(KEY_PRINTER_WIDTH, value).apply()

    var historyLimit: Int
        get() = prefs.getInt(KEY_HISTORY_LIMIT, DEFAULT_HISTORY_LIMIT)
        set(value) = prefs.edit().putInt(KEY_HISTORY_LIMIT, value).apply()

    var feedLines: Int
        get() = prefs.getInt(KEY_FEED_LINES, DEFAULT_FEED_LINES)
        set(value) = prefs.edit().putInt(KEY_FEED_LINES, value).apply()

    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, DEFAULT_FONT_SCALE)
        set(value) = prefs.edit().putFloat(KEY_FONT_SCALE, value).apply()

    // Photo Getters/Setters
    var photoScale: Int
        get() = prefs.getInt(KEY_PHOTO_SCALE, 100)
        set(value) = prefs.edit().putInt(KEY_PHOTO_SCALE, value).apply()
    var photoBrightness: Int
        get() = prefs.getInt(KEY_PHOTO_BRIGHTNESS, 0)
        set(value) = prefs.edit().putInt(KEY_PHOTO_BRIGHTNESS, value).apply()
    var photoGamma: Float
        get() = prefs.getFloat(KEY_PHOTO_GAMMA, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PHOTO_GAMMA, value).apply()
    var photoAlign: Int
        get() = prefs.getInt(KEY_PHOTO_ALIGN, SunmiPrinterHelper.ALIGN_CENTER)
        set(value) = prefs.edit().putInt(KEY_PHOTO_ALIGN, value).apply()
    var photoDither: Int
        get() = prefs.getInt(KEY_PHOTO_DITHER, PrintImageUtils.DitherAlgorithm.FloydSteinberg.ordinal)
        set(value) = prefs.edit().putInt(KEY_PHOTO_DITHER, value).apply()

    // Batch Getters/Setters
    var batchScale: Int
        get() = prefs.getInt(KEY_BATCH_SCALE, 100)
        set(value) = prefs.edit().putInt(KEY_BATCH_SCALE, value).apply()
    var batchBrightness: Int
        get() = prefs.getInt(KEY_BATCH_BRIGHTNESS, 0)
        set(value) = prefs.edit().putInt(KEY_BATCH_BRIGHTNESS, value).apply()
    var batchGamma: Float
        get() = prefs.getFloat(KEY_BATCH_GAMMA, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_BATCH_GAMMA, value).apply()
    var batchAlign: Int
        get() = prefs.getInt(KEY_BATCH_ALIGN, SunmiPrinterHelper.ALIGN_CENTER)
        set(value) = prefs.edit().putInt(KEY_BATCH_ALIGN, value).apply()
    var batchDither: Int
        get() = prefs.getInt(KEY_BATCH_DITHER, PrintImageUtils.DitherAlgorithm.FloydSteinberg.ordinal)
        set(value) = prefs.edit().putInt(KEY_BATCH_DITHER, value).apply()
    var batchGap: Int
        get() = prefs.getInt(KEY_BATCH_GAP, 2)
        set(value) = prefs.edit().putInt(KEY_BATCH_GAP, value).apply()

    // Video Getters/Setters
    var videoScale: Int
        get() = prefs.getInt(KEY_VIDEO_SCALE, 100)
        set(value) = prefs.edit().putInt(KEY_VIDEO_SCALE, value).apply()
    var videoBrightness: Int
        get() = prefs.getInt(KEY_VIDEO_BRIGHTNESS, 0)
        set(value) = prefs.edit().putInt(KEY_VIDEO_BRIGHTNESS, value).apply()
    var videoGamma: Float
        get() = prefs.getFloat(KEY_VIDEO_GAMMA, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_VIDEO_GAMMA, value).apply()
    var videoAlign: Int
        get() = prefs.getInt(KEY_VIDEO_ALIGN, SunmiPrinterHelper.ALIGN_CENTER)
        set(value) = prefs.edit().putInt(KEY_VIDEO_ALIGN, value).apply()
    var videoDither: Int
        get() = prefs.getInt(KEY_VIDEO_DITHER, PrintImageUtils.DitherAlgorithm.FloydSteinberg.ordinal)
        set(value) = prefs.edit().putInt(KEY_VIDEO_DITHER, value).apply()
    var videoGap: Int
        get() = prefs.getInt(KEY_VIDEO_GAP, 2)
        set(value) = prefs.edit().putInt(KEY_VIDEO_GAP, value).apply()
}
