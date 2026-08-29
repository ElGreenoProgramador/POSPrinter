package com.cadrega.posprinter

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cadrega.posprinter.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class ImageAdjustSettings(val scalePercent: Int, val brightness: Int, val alignment: Int)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var printer: SunmiPrinterHelper
    private lateinit var frameExtractor: VideoFrameExtractor
    private lateinit var historyManager: PrintHistoryManager
    private lateinit var historyAdapter: PrintHistoryAdapter
    private var lastSelectedVideoUri: Uri? = null

    // Most POSPrinter units use 58mm paper -> ~384px wide print head.
    // Change to SunmiPrinterHelper.PRINTER_WIDTH_80MM if your unit takes 80mm paper.
    private val printerWidthPx = SunmiPrinterHelper.PRINTER_WIDTH_58MM

    /** User's chosen scale (% of printer width), brightness (-100..100), and alignment for an image/frame print. */

    private var adjustResultCont: CancellableContinuation<List<ImageAdjustSettings>?>? = null
    private val adjustLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val settingsList = if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data != null) {
                val scales = data.getIntegerArrayListExtra(ImageAdjustActivity.RESULT_SCALES) ?: emptyList<Int>()
                val brightnesses = data.getIntegerArrayListExtra(ImageAdjustActivity.RESULT_BRIGHTNESSES) ?: emptyList<Int>()
                val alignments = data.getIntegerArrayListExtra(ImageAdjustActivity.RESULT_ALIGNMENTS) ?: emptyList<Int>()
                
                scales.indices.map { i ->
                    ImageAdjustSettings(scales[i], brightnesses[i], alignments[i])
                }
            } else emptyList()
        } else null
        ImageAdjustActivity.bitmapsToAdjust = emptyList() // Clear cache
        adjustResultCont?.resume(settingsList)
        adjustResultCont = null
    }

    private val pickPhotoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { onPhotoPicked(it) }
        }

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { onVideoPicked(it) }
        }


    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val frames = VideoFramePickerActivity.selectedFrames
            val uri = lastSelectedVideoUri
            if (frames.isNotEmpty() && (uri != null)) {
                onFramesSelected(uri, frames)
            }
        }
    }

    private val textLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = result.data?.getStringExtra(TextAdjustActivity.RESULT_TEXT)
            if (!text.isNullOrBlank()) {
                if (text.contains("#") || text.contains("- ") || text.contains("**")) {
                    printMarkdown(text)
                } else {
                    printRichText(text, SunmiPrinterHelper.ALIGN_LEFT, bold = false, fontSize = 24f)
                }
            }
        }
    }

    private val pickMultiplePhotosLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) onBatchPhotosPicked(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        printer = SunmiPrinterHelper(this)
        frameExtractor = VideoFrameExtractor(this)
        historyManager = PrintHistoryManager(this)
        historyAdapter = PrintHistoryAdapter(historyManager) { item, status ->
            when (status) {
                is PrintHistoryManager.RelaunchStatus.Ready -> relaunchPrintJob(item)
                is PrintHistoryManager.RelaunchStatus.MissingFiles -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Cannot Relaunch")
                        .setMessage("Some or all required image files (${status.missingCount}) have been removed from storage or cleaned by the system.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                is PrintHistoryManager.RelaunchStatus.MissingData -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Cannot Relaunch")
                        .setMessage("The source data for this print job is no longer available.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        binding.historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
        }
        refreshHistory()

        binding.clearHistoryButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear History")
                .setMessage("Are you sure you want to delete all printing history? This cannot be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    historyManager.clearHistory()
                    refreshHistory()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        printer.connect(
            onConnected = { runOnUiThread { binding.statusText.text = "Printer connected" } },
            onDisconnected = { runOnUiThread { binding.statusText.text = "Printer disconnected" } }
        )

        binding.cardPrintPhoto.setOnClickListener {
            pickPhotoLauncher.launch("image/*")
        }
        binding.cardBatchPrint.setOnClickListener {
            pickMultiplePhotosLauncher.launch("image/*")
        }
        binding.cardPrintVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }
        binding.cardPrintText.setOnClickListener {
            val intent = Intent(this, TextAdjustActivity::class.java)
            textLauncher.launch(intent)
        }
    }

    override fun onDestroy() {
        printer.disconnect()
        super.onDestroy()
    }

    private fun refreshHistory() {
        historyAdapter.updateItems(historyManager.getHistory())
    }

    // ---------- Photo printing ----------

    private fun onPhotoPicked(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = loadBitmap(uri) ?: run {
                withContext(Dispatchers.Main) {
                    toast("Couldn't load that image")
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (!printer.isConnected) {
                    toast("Printer not connected - showing preview only")
                }

                lifecycleScope.launch {
                    val settings = showImageAdjustDialog(bitmap) ?: return@launch // user cancelled

                    binding.progressText.text = "Preparing image..."
                    binding.progressCard.visibility = android.view.View.VISIBLE
                    val prepared = withContext(Dispatchers.Default) {
                        PrintImageUtils.prepareForPrint(
                            bitmap, printerWidthPx, settings.scalePercent, settings.brightness
                        )
                    }

                    binding.progressText.text = "Printing..."
                    printer.setAlignment(settings.alignment)
                    val result = printer.printBitmap(prepared)
                    printer.feedPaper(4)
                    binding.progressCard.visibility = android.view.View.GONE
                    if (result.isSuccess) {
                        binding.progressText.text = "Done"
                        val extraData = JSONObject().apply {
                            put("uri", uri.toString())
                        }.toString()
                        historyManager.saveItem(
                            PrintHistoryItem(type = "Photo", description = "Single photo print", extraData = extraData), 
                            listOf(bitmap) // Save original for potential re-adjustment
                        )
                        refreshHistory()
                    } else {
                        binding.progressText.text = "Print failed: ${result.exceptionOrNull()?.message}"
                    }
                }
            }
        }
    }

    private fun onBatchPhotosPicked(uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (!printer.isConnected) {
                toast("Printer not connected - showing preview only")
            }

            binding.progressCard.visibility = android.view.View.VISIBLE
            binding.progressText.text = "Loading images..."
            
            val bitmaps = uris.mapNotNull { uri ->
                withContext(Dispatchers.IO) { loadBitmap(uri) }
            }
            
            if (bitmaps.isEmpty()) {
                binding.progressBar.visibility = android.view.View.GONE
                toast("Couldn't load images")
                return@launch
            }

            val settingsList = showImageAdjustDialogs(bitmaps) ?: run {
                binding.progressBar.visibility = android.view.View.GONE
                return@launch
            }

            var printedCount = 0
            val lastPreparedBitmaps = mutableListOf<Bitmap>()
            
            for ((index, bitmap) in bitmaps.withIndex()) {
                val settings = settingsList[index]
                binding.progressText.text = "Printing image ${index + 1}/${bitmaps.size}..."

                val prepared = withContext(Dispatchers.Default) {
                    PrintImageUtils.prepareForPrint(
                        bitmap, printerWidthPx, settings.scalePercent, settings.brightness
                    )
                }
                lastPreparedBitmaps.add(prepared)

                printer.setAlignment(settings.alignment)
                val result = printer.printBitmap(prepared)
                if (result.isSuccess) {
                    printedCount++
                    printer.feedPaper(2)
                }
            }
            
            printer.feedPaper(4)
            binding.progressCard.visibility = android.view.View.GONE
            if (printedCount > 0) {
                binding.progressText.text = "Done - printed $printedCount/${bitmaps.size} image(s)"
                val extraData = JSONObject().apply {
                    put("uris", JSONArray(uris.map { it.toString() }))
                }.toString()
                historyManager.saveItem(
                    PrintHistoryItem(type = "Batch", description = "Batch of $printedCount images", extraData = extraData), 
                    bitmaps // Save originals
                )
                refreshHistory()
            } else {
                binding.progressText.text = "Batch cancelled or no images printed"
            }
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? = try {
        val targetMaxSide = printerWidthPx * 3 // Slightly larger for better adjustment quality
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.isMutableRequired = true
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val srcWidth = info.size.width
                val srcHeight = info.size.height
                if ((srcWidth > targetMaxSide) || (srcHeight > targetMaxSide)) {
                    val scale = if (srcWidth > srcHeight) {
                        targetMaxSide.toFloat() / srcWidth
                    } else {
                        targetMaxSide.toFloat() / srcHeight
                    }
                    decoder.setTargetSize((srcWidth * scale).toInt(), (srcHeight * scale).toInt())
                }
            }
        } else {
            // Legacy downsampling
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { 
                android.graphics.BitmapFactory.decodeStream(it, null, options)
            }
            
            var inSampleSize = 1
            if (options.outHeight > targetMaxSide || options.outWidth > targetMaxSide) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= targetMaxSide && halfWidth / inSampleSize >= targetMaxSide) {
                    inSampleSize *= 2
                }
            }
            
            android.graphics.BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                this.inPreferredConfig = Bitmap.Config.ARGB_8888
            }.let { finalOptions ->
                contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, finalOptions)
                }
            }
        }
    } catch (ignored: Exception) {
        null
    } catch (ignored: OutOfMemoryError) {
        null
    }

    private suspend fun showImageAdjustDialog(preview: Bitmap): ImageAdjustSettings? {
        val list = showImageAdjustDialogs(listOf(preview))
        return list?.firstOrNull()
    }

    private suspend fun showImageAdjustDialogs(previews: List<Bitmap>, isReadOnly: Boolean = false): List<ImageAdjustSettings>? =
        suspendCancellableCoroutine { cont ->
            adjustResultCont = cont
            ImageAdjustActivity.bitmapsToAdjust = previews
            val intent = Intent(this, ImageAdjustActivity::class.java).apply {
                putExtra(ImageAdjustActivity.EXTRA_READ_ONLY, isReadOnly)
            }
            adjustLauncher.launch(intent)
        }

    // ---------- Video (frame-by-frame) printing ----------

    private fun onVideoPicked(uri: Uri) {
        lastSelectedVideoUri = uri
        val intent = Intent(this, VideoFramePickerActivity::class.java).apply {
            putExtra("video_uri", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        videoPickerLauncher.launch(intent)
    }

    private fun onFramesSelected(videoUri: Uri, frames: List<VideoFramePickerActivity.SelectedFrame>) {
        val bitmaps = frames.map { it.bitmap }
        lifecycleScope.launch(Dispatchers.Main) {
            if (!printer.isConnected) {
                toast("Printer not connected - showing preview only")
            }

            binding.progressCard.visibility = android.view.View.VISIBLE
            
            val settingsList = showImageAdjustDialogs(bitmaps) ?: run {
                binding.progressCard.visibility = android.view.View.GONE
                return@launch
            }

            var printedCount = 0
            val lastPreparedBitmaps = mutableListOf<Bitmap>()

            for ((index, bitmap) in bitmaps.withIndex()) {
                val settings = settingsList[index]
                binding.progressText.text = "Printing frame ${index + 1}/${bitmaps.size}..."

                val prepared = withContext(Dispatchers.Default) {
                    PrintImageUtils.prepareForPrint(
                        bitmap, printerWidthPx, settings.scalePercent, settings.brightness
                    )
                }
                lastPreparedBitmaps.add(prepared)

                printer.setAlignment(settings.alignment)
                val result = printer.printBitmap(prepared)
                if (result.isSuccess) {
                    printedCount++
                    printer.feedPaper(2)
                }
            }

            printer.feedPaper(4)
            binding.progressCard.visibility = android.view.View.GONE
            if (printedCount > 0) {
                binding.progressText.text = "Done - printed $printedCount/${bitmaps.size} frame(s)"
                val extraData = JSONObject().apply {
                    put("uri", videoUri.toString())
                    put("timestamps", JSONArray(frames.map { it.timestampMs }))
                }.toString()
                historyManager.saveItem(
                    PrintHistoryItem(type = "Video", description = "Video frames print ($printedCount)", extraData = extraData), 
                    bitmaps // Save originals
                )
                refreshHistory()
            } else {
                binding.progressText.text = "Video printing cancelled or no frames printed"
            }
            VideoFramePickerActivity.selectedFrames = emptyList() // Clear cache
        }
    }

    // ---------- Rich text printing ----------


    private fun printMarkdown(text: String, saveToHistory: Boolean = true) {
        lifecycleScope.launch {
            binding.progressText.text = "Printing Markdown..."
            binding.progressCard.visibility = android.view.View.VISIBLE
            val result = printer.printMarkdown(text)
            printer.feedPaper(3)
            binding.progressCard.visibility = android.view.View.GONE
            if (result.isSuccess) {
                binding.progressText.text = "Done"
                if (saveToHistory) {
                    historyManager.saveItem(
                        PrintHistoryItem(type = "Text", description = text.take(30) + "...", extraData = text), 
                        emptyList()
                    )
                    refreshHistory()
                }
            } else {
                binding.progressText.text = "Print failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    private fun printRichText(text: String, alignment: Int, bold: Boolean, fontSize: Float, saveToHistory: Boolean = true) {
        lifecycleScope.launch {
            binding.progressText.text = "Printing text..."
            binding.progressCard.visibility = android.view.View.VISIBLE
            val result = printer.printRichText(text, alignment, bold, fontSize)
            printer.feedPaper(3)
            binding.progressCard.visibility = android.view.View.GONE
            if (result.isSuccess) {
                binding.progressText.text = "Done"
                if (saveToHistory) {
                    historyManager.saveItem(
                        PrintHistoryItem(type = "Text", description = text.take(30) + "...", extraData = text), 
                        emptyList()
                    )
                    refreshHistory()
                }
            } else {
                binding.progressText.text = "Print failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    private fun relaunchPrintJob(item: PrintHistoryItem) {
        lifecycleScope.launch(Dispatchers.Main) {
            Log.i("MainActivity", "Relaunching job: ${item.id} of type ${item.type}")
            
            when (item.type) {
                "Text" -> {
                    val text = item.extraData ?: return@launch
                    val intent = Intent(this@MainActivity, TextAdjustActivity::class.java).apply {
                        putExtra(TextAdjustActivity.EXTRA_TEXT, text)
                    }
                    textLauncher.launch(intent)
                }
                "Photo", "Batch", "Video" -> {
                    // Try to reacquire from original media
                    val originalBitmaps = tryReacquireOriginals(item)
                    
                    val bitmaps = if (originalBitmaps.isNotEmpty()) {
                        Log.i("MainActivity", "Reacquired originals from source media")
                        originalBitmaps
                    } else {
                        Log.i("MainActivity", "Falling back to stored history bitmaps")
                        withContext(Dispatchers.IO) {
                            item.imagePaths.mapNotNull { historyManager.loadBitmap(it) }
                        }
                    }

                    if (bitmaps.isEmpty()) {
                        Log.e("MainActivity", "Relaunch failed: No bitmaps loaded")
                        toast("Could not load stored or original images")
                        return@launch
                    }

                    // Open history preview - now with "Adjust Parameters" possibility
                    val settingsList = showImageAdjustDialogs(bitmaps, isReadOnly = true) ?: return@launch

                    if (!printer.isConnected) {
                        toast("Printer not connected - showing preview only")
                    }
                    
                    binding.progressCard.visibility = android.view.View.VISIBLE
                    binding.progressText.text = "Printing ${item.type}..."

                    try {
                        for ((index, bitmap) in bitmaps.withIndex()) {
                            val settings = settingsList[index]
                            binding.progressText.text = "Printing item ${index + 1}/${bitmaps.size}..."
                            
                            val prepared = withContext(Dispatchers.Default) {
                                PrintImageUtils.prepareForPrint(
                                    bitmap, printerWidthPx, settings.scalePercent, settings.brightness
                                )
                            }
                            
                            printer.setAlignment(settings.alignment)
                            val result = printer.printBitmap(prepared)
                            if (result.isSuccess) {
                                printer.feedPaper(2)
                            }
                        }
                        printer.feedPaper(4)
                        binding.progressText.text = "Done"
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Relaunch failed", e)
                        toast("Relaunch failed: ${e.message}")
                    } finally {
                        binding.progressCard.visibility = android.view.View.GONE
                    }
                }
            }
        }
    }

    private suspend fun tryReacquireOriginals(item: PrintHistoryItem): List<Bitmap> {
        val extraData = item.extraData ?: return emptyList()
        return try {
            val json = JSONObject(extraData)
            when (item.type) {
                "Photo" -> {
                    val uri = Uri.parse(json.getString("uri"))
                    listOfNotNull(withContext(Dispatchers.IO) { loadBitmap(uri) })
                }
                "Batch" -> {
                    val urisArr = json.getJSONArray("uris")
                    val bitmaps = mutableListOf<Bitmap>()
                    for (i in 0 until urisArr.length()) {
                        val uri = Uri.parse(urisArr.getString(i))
                        withContext(Dispatchers.IO) { loadBitmap(uri) }?.let { bitmaps.add(it) }
                    }
                    if (bitmaps.size == urisArr.length()) bitmaps else emptyList()
                }
                "Video" -> {
                    val uri = Uri.parse(json.getString("uri"))
                    val timestampsArr = json.getJSONArray("timestamps")
                    val timestamps = mutableListOf<Long>()
                    for (i in 0 until timestampsArr.length()) {
                        timestamps.add(timestampsArr.getLong(i))
                    }
                    frameExtractor.extractSpecificFrames(uri, timestamps)
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to reacquire originals: ${e.message}")
            emptyList()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
