package com.example.sunmiprinter

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts frames from a video for sequential thermal printing.
 *
 * A thermal printer physically cannot keep up with a video's native frame
 * rate (a 30fps clip has 1800 frames/minute; each thermal print takes at
 * least ~1 second). "Frame by frame" printing therefore means: walk the
 * video's timeline and print one frame per sampling interval, in order -
 * this class exposes [frameIntervalMs] so the caller controls how dense
 * that is (e.g. every 200ms for a flipbook-style effect, or every 1000ms
 * to conserve paper).
 */
class VideoFrameExtractor(private val context: Context) {

    data class VideoInfo(val durationMs: Long, val rotationDegrees: Int)

    fun getVideoInfo(uri: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            return VideoInfo(duration, rotation)
        } finally {
            retriever.release()
        }
    }

    /**
     * Extracts one frame every [frameIntervalMs] across the whole video and
     * invokes [onFrame] for each, in timeline order. Runs on Dispatchers.IO.
     * [onFrame] returning false stops extraction early (e.g. user cancelled).
     */
    suspend fun extractFrames(
        uri: Uri,
        frameIntervalMs: Long,
        onFrame: suspend (frameIndex: Int, totalFrames: Int, bitmap: Bitmap) -> Boolean
    ) = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val info = getVideoInfo(uri)
            if (info.durationMs <= 0) return@withContext

            val totalFrames = (info.durationMs / frameIntervalMs).toInt().coerceAtLeast(1)
            var t = 0L
            var index = 0
            while (t < info.durationMs) {
                val frame = retriever.getFrameAtTime(
                    t * 1000, // microseconds
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                if (frame != null) {
                    val keepGoing = onFrame(index, totalFrames, frame)
                    if (!keepGoing) break
                }
                index++
                t += frameIntervalMs
            }
        } finally {
            retriever.release()
        }
    }
}
