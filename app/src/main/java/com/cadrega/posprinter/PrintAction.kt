package com.cadrega.posprinter

import android.graphics.Bitmap
import java.util.UUID

sealed class PrintAction {
    val id: String = UUID.randomUUID().toString()

    data class Text(
        val content: String
    ) : PrintAction()

    data class Image(
        val bitmaps: List<Bitmap>,
        val description: String,
        val featureType: String, // "photo", "batch", "video"
        val settings: List<ImageAdjustSettings>? = null,
        val gapMm: Int = 2
    ) : PrintAction()
}
