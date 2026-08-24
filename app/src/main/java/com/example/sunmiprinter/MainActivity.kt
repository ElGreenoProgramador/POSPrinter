package com.example.sunmiprinter

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.sunmiprinter.databinding.ActivityMainBinding
import com.example.sunmiprinter.databinding.DialogImageAdjustBinding
import com.example.sunmiprinter.databinding.DialogRichTextBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var printer: SunmiPrinterHelper
    private lateinit var frameExtractor: VideoFrameExtractor

    // Most Sunmi V2 units use 58mm paper -> ~384px wide print head.
    // Change to SunmiPrinterHelper.PRINTER_WIDTH_80MM if your unit takes 80mm paper.
    private val printerWidthPx = SunmiPrinterHelper.PRINTER_WIDTH_58MM

    /** User's chosen scale (% of printer width), brightness (-100..100), and alignment for an image/frame print. */
    private data class ImageAdjustSettings(val scalePercent: Int, val brightness: Int, val alignment: Int)

    private val pickPhotoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { onPhotoPicked(it) }
        }

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { onVideoPicked(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        printer = SunmiPrinterHelper(this)
        frameExtractor = VideoFrameExtractor(this)

        printer.connect(
            onConnected = { runOnUiThread { binding.statusText.text = "Printer connected \u2713" } },
            onDisconnected = { runOnUiThread { binding.statusText.text = "Printer disconnected" } }
        )

        binding.pickPhotoButton.setOnClickListener {
            pickPhotoLauncher.launch("image/*")
        }
        binding.pickVideoButton.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }
        binding.printTextButton.setOnClickListener {
            showRichTextDialog()
        }
    }

    override fun onDestroy() {
        printer.disconnect()
        super.onDestroy()
    }

    // ---------- Photo printing ----------

    private fun onPhotoPicked(uri: Uri) {
        val bitmap = loadBitmap(uri) ?: run {
            toast("Couldn't load that image")
            return
        }
        binding.previewImage.setImageBitmap(bitmap)

        if (!printer.isConnected) {
            toast("Printer not connected - is this running on a Sunmi device?")
            return
        }

        lifecycleScope.launch {
            val settings = showImageAdjustDialog(bitmap) ?: return@launch // user cancelled

            binding.progressText.text = "Preparing image..."
            val prepared = PrintImageUtils.prepareForPrint(
                bitmap, printerWidthPx, settings.scalePercent, settings.brightness
            )

            binding.progressText.text = "Printing..."
            printer.setAlignment(settings.alignment)
            val result = printer.printBitmap(prepared)
            printer.feedPaper(4)
            binding.progressText.text = if (result.isSuccess) "Done \u2713"
            else "Print failed: ${result.exceptionOrNull()?.message}"
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Shows the scale/brightness/alignment adjustment dialog with a live
     * preview of exactly what will be printed (scaled, brightness-adjusted,
     * dithered to 1-bit, and positioned on the paper per the chosen
     * alignment). Suspends until the user taps Print (returns their choice)
     * or Cancel/dismisses (returns null).
     */
    private suspend fun showImageAdjustDialog(preview: Bitmap): ImageAdjustSettings? =
        suspendCancellableCoroutine { cont ->
            val dialogBinding = DialogImageAdjustBinding.inflate(layoutInflater)

            dialogBinding.scaleSeekBar.max = 90
            dialogBinding.scaleSeekBar.progress = 90
            dialogBinding.scaleLabel.text = "Scale: 100%"

            dialogBinding.brightnessSeekBar.max = 200
            dialogBinding.brightnessSeekBar.progress = 100
            dialogBinding.brightnessLabel.text = "Brightness: 0"

            fun currentAlignment(): Int = when (dialogBinding.alignmentRadioGroup.checkedRadioButtonId) {
                R.id.alignLeftRadio -> SunmiPrinterHelper.ALIGN_LEFT
                R.id.alignRightRadio -> SunmiPrinterHelper.ALIGN_RIGHT
                else -> SunmiPrinterHelper.ALIGN_CENTER
            }

            // Debounced live preview: regenerates the exact printer-ready bitmap
            // (scale -> brightness -> dither) and composes it onto a paper-width
            // canvas at the chosen alignment, so what's shown is what will print.
            var previewJob: Job? = null
            fun scheduleLivePreviewUpdate() {
                previewJob?.cancel()
                val scalePercent = dialogBinding.scaleSeekBar.progress + 10
                val brightness = dialogBinding.brightnessSeekBar.progress - 100
                val alignment = currentAlignment()
                previewJob = lifecycleScope.launch {
                    delay(120) // debounce rapid slider drags
                    val composed = withContext(Dispatchers.Default) {
                        val printReady = PrintImageUtils.prepareForPrint(
                            preview, printerWidthPx, scalePercent, brightness
                        )
                        PrintImageUtils.composePrintPreview(printReady, printerWidthPx, alignment)
                    }
                    dialogBinding.adjustPreviewImage.setImageBitmap(composed)
                }
            }

            dialogBinding.scaleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    dialogBinding.scaleLabel.text = "Scale: ${progress + 10}%"
                    if (fromUser) scheduleLivePreviewUpdate()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            dialogBinding.brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    dialogBinding.brightnessLabel.text = "Brightness: ${progress - 100}"
                    if (fromUser) scheduleLivePreviewUpdate()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            dialogBinding.alignmentRadioGroup.setOnCheckedChangeListener { _, _ -> scheduleLivePreviewUpdate() }

            scheduleLivePreviewUpdate() // initial preview at defaults

            var resumed = false
            val dialog = AlertDialog.Builder(this)
                .setTitle("Adjust before printing")
                .setView(dialogBinding.root)
                .setPositiveButton("Print") { _, _ ->
                    val scalePercent = dialogBinding.scaleSeekBar.progress + 10
                    val brightness = dialogBinding.brightnessSeekBar.progress - 100
                    val alignment = currentAlignment()
                    if (!resumed) {
                        resumed = true
                        cont.resume(ImageAdjustSettings(scalePercent, brightness, alignment))
                    }
                }
                .setNegativeButton("Cancel") { _, _ ->
                    if (!resumed) { resumed = true; cont.resume(null) }
                }
                .setOnCancelListener {
                    if (!resumed) { resumed = true; cont.resume(null) }
                }
                .create()

            cont.invokeOnCancellation {
                previewJob?.cancel()
                dialog.dismiss()
            }
            dialog.show()
        }

    // ---------- Video (frame-by-frame) printing ----------

    private fun onVideoPicked(uri: Uri) {
        if (!printer.isConnected) {
            toast("Printer not connected - is this running on a Sunmi device?")
            return
        }
        lifecycleScope.launch {
            val intervalMs = promptFrameInterval() ?: return@launch

            var settings: ImageAdjustSettings? = null
            frameExtractor.extractFrames(uri, intervalMs) { index, _, frame ->
                if (index == 0) {
                    // Switch to Main dispatcher and suspend until dialog completes
                    settings = withContext(Dispatchers.Main) {
                        showImageAdjustDialog(frame)
                    }
                }
                false
            }
            val chosenSettings = settings ?: return@launch

            printVideoFrames(uri, intervalMs, chosenSettings)
        }
    }

    private suspend fun promptFrameInterval(): Long? = suspendCancellableCoroutine { cont ->
        val input = EditText(this).apply {
            hint = "e.g. 300"
            setText("300")
        }
        var resumed = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Frame interval (ms)")
            .setMessage(
                "Thermal printers can't keep up with real video frame rates, so " +
                    "frames are sampled at this interval across the clip and printed " +
                    "in order, one after another. Smaller = more frames = more detail " +
                    "but far more paper/time."
            )
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val intervalMs = input.text.toString().toLongOrNull()?.coerceAtLeast(50) ?: 300L
                if (!resumed) { resumed = true; cont.resume(intervalMs) }
            }
            .setNegativeButton("Cancel") { _, _ ->
                if (!resumed) { resumed = true; cont.resume(null) }
            }
            .setOnCancelListener {
                if (!resumed) { resumed = true; cont.resume(null) }
            }
            .create()
        cont.invokeOnCancellation { dialog.dismiss() }
        dialog.show()
    }

    private fun printVideoFrames(uri: Uri, intervalMs: Long, settings: ImageAdjustSettings) {
        lifecycleScope.launch {
            binding.progressText.text = "Extracting & printing frames..."
            var printedCount = 0
            try {
                printer.setAlignment(settings.alignment)
                frameExtractor.extractFrames(uri, intervalMs) { index, total, frame ->
                    // UI updates must happen on main thread
                    runOnUiThread {
                        binding.previewImage.setImageBitmap(frame)
                        binding.progressText.text = "Printing frame ${index + 1}/$total"
                    }

                    // Heavy work can stay on background thread
                    val prepared = PrintImageUtils.prepareForPrint(
                        frame, printerWidthPx, settings.scalePercent, settings.brightness
                    )
                    val result = printer.printBitmap(prepared)
                    if (result.isSuccess) printedCount++

                    printer.isConnected
                }
                printer.feedPaper(4)

                // Final UI update
                runOnUiThread {
                    binding.progressText.text = "Done - printed $printedCount frame(s) \u2713"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressText.text = "Video printing failed: ${e.message}"
                    toast(e.message.orEmpty())
                }
            }
        }
    }

    // ---------- Rich text printing ----------

    private fun showRichTextDialog() {
        if (!printer.isConnected) {
            toast("Printer not connected - is this running on a Sunmi device?")
            return
        }
        val dialogBinding = DialogRichTextBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle("Print custom text")
            .setView(dialogBinding.root)
            .setPositiveButton("Print") { _, _ ->
                val text = dialogBinding.richTextInput.text.toString()
                if (text.isBlank()) {
                    toast("Enter some text first")
                    return@setPositiveButton
                }
                val bold = dialogBinding.boldCheckBox.isChecked
                val fontSize = when (dialogBinding.fontSizeRadioGroup.checkedRadioButtonId) {
                    R.id.fontSmallRadio -> 20f
                    R.id.fontLargeRadio -> 32f
                    R.id.fontXLargeRadio -> 40f
                    else -> 24f // Medium
                }
                val alignment = when (dialogBinding.textAlignmentRadioGroup.checkedRadioButtonId) {
                    R.id.textAlignCenterRadio -> SunmiPrinterHelper.ALIGN_CENTER
                    R.id.textAlignRightRadio -> SunmiPrinterHelper.ALIGN_RIGHT
                    else -> SunmiPrinterHelper.ALIGN_LEFT
                }
                printRichText(text, alignment, bold, fontSize)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun printRichText(text: String, alignment: Int, bold: Boolean, fontSize: Float) {
        lifecycleScope.launch {
            binding.progressText.text = "Printing text..."
            val result = printer.printRichText(text, alignment, bold, fontSize)
            printer.feedPaper(3)
            binding.progressText.text = if (result.isSuccess) "Done \u2713"
            else "Print failed: ${result.exceptionOrNull()?.message}"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
