package com.cadrega.posprinter

import android.content.Context

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("pos_printer_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_PRINTER_WIDTH = "printer_width"
        const val KEY_HISTORY_LIMIT = "history_limit"
        const val KEY_FEED_LINES = "feed_lines"
        const val KEY_FONT_SCALE = "font_scale"
        
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
}
