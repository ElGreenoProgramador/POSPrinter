package com.cadrega.posprinter

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
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
            Log.d("VideoFrameExtractor", "Setting data source for URI: $uri")
            retriever.setDataSource(context, uri)
            val info = getVideoInfo(uri)
            Log.d("VideoFrameExtractor", "Video info: duration=${info.durationMs}ms, rotation=${info.rotationDegrees}")
            
            if (info.durationMs <= 0) {
                Log.w("VideoFrameExtractor", "Video duration is 0 or invalid")
                return@withContext
            }

            val totalFrames = (info.durationMs / frameIntervalMs).toInt().coerceAtLeast(1)
            var t = 0L
            var index = 0
            
            val targetWidth = 576
            val targetHeight = 576
            
            while (t < info.durationMs) {
                Log.v("VideoFrameExtractor", "Extracting frame at $t ms")
                val frame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(
                        t * 1000, 
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetWidth,
                        targetHeight
                    )
                } else {
                    retriever.getFrameAtTime(
                        t * 1000, 
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                }
                
                if (frame != null) {
                    // Ensure we have a software bitmap that's compatible with all views
                    val softwareBitmap = if (android.os.Build.VERSION.SDK_INT >= 26 && frame.config == android.graphics.Bitmap.Config.HARDWARE) {
                        frame.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    } else {
                        frame
                    }
                    
                    val keepGoing = onFrame(index, totalFrames, softwareBitmap)
                    if (!keepGoing) break
                } else {
                    Log.w("VideoFrameExtractor", "Failed to extract frame at $t ms")
                }
                index++
                t += frameIntervalMs
            }
        } catch (e: Exception) {
            Log.e("VideoFrameExtractor", "Error during frame extraction", e)
        } finally {
            retriever.release()
        }
    }

    suspend fun extractSpecificFrames(
        uri: Uri,
        timestampsMs: List<Long>
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val result = mutableListOf<Bitmap>()
        try {
            retriever.setDataSource(context, uri)
            val targetWidth = 576
            val targetHeight = 576

            for (t in timestampsMs) {
                val frame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(
                        t * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetWidth,
                        targetHeight
                    )
                } else {
                    retriever.getFrameAtTime(
                        t * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                }

                if (frame != null) {
                    val softwareBitmap = if (android.os.Build.VERSION.SDK_INT >= 26 && frame.config == android.graphics.Bitmap.Config.HARDWARE) {
                        frame.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    } else {
                        frame
                    }
                    result.add(softwareBitmap)
                }
            }
        } catch (e: Exception) {
            Log.e("VideoFrameExtractor", "Error extracting specific frames", e)
        } finally {
            retriever.release()
        }
        result
    }
}
