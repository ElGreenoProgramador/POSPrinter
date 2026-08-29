package com.cadrega.posprinter

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cadrega.posprinter.databinding.ActivityImageAdjustBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ImageAdjustActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageAdjustBinding
    private val printerWidthPx = SunmiPrinterHelper.PRINTER_WIDTH_58MM
    
    private var currentIndex = 0
    private var bitmaps: List<Bitmap> = emptyList()
    private var isReadOnly = false

    companion object {
        var bitmapsToAdjust: List<Bitmap> = emptyList()
        const val EXTRA_READ_ONLY = "extra_read_only"
        
        const val RESULT_SCALES = "result_scales"
        const val RESULT_BRIGHTNESSES = "result_brightnesses"
        const val RESULT_ALIGNMENTS = "result_alignments"
    }

    private val scales = mutableListOf<Int>()
    private val brightnesses = mutableListOf<Int>()
    private val alignments = mutableListOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageAdjustBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bitmaps = bitmapsToAdjust
        if (bitmaps.isEmpty()) {
            finish()
            return
        }

        isReadOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, false)

        // Initialize settings for each bitmap
        for (i in bitmaps.indices) {
            scales.add(100)
            brightnesses.add(0)
            alignments.add(SunmiPrinterHelper.ALIGN_CENTER)
        }

        if (isReadOnly) {
            binding.toolbar.title = if (bitmaps.size > 1) "History Batch (${bitmaps.size})" else "History Preview"
            binding.originalImageContainer.visibility = View.GONE
            binding.settingsContainer.visibility = View.GONE
            binding.printButton.text = "Re-print"
            binding.editSettingsButton.visibility = View.VISIBLE
        }

        binding.editSettingsButton.setOnClickListener {
            isReadOnly = false
            binding.editSettingsButton.visibility = View.GONE
            binding.originalImageContainer.visibility = View.VISIBLE
            binding.settingsContainer.visibility = View.VISIBLE
            binding.printButton.text = "Print"
            binding.toolbar.title = if (bitmaps.size > 1) "Adjust Batch (${bitmaps.size})" else "Adjust Print Settings"
            updateUIForCurrentIndex()
        }

        if (bitmaps.size > 1) {
            binding.navigationContainer.visibility = View.VISIBLE
            if (!isReadOnly) binding.toolbar.title = "Adjust Batch (${bitmaps.size})"
        }

        updateUIForCurrentIndex()

        binding.scaleSeekBar.max = 90
        binding.scaleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 10
                binding.scaleLabel.text = "Scale: $value%"
                scales[currentIndex] = value
                if (fromUser) scheduleLivePreviewUpdate()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.brightnessSeekBar.max = 200
        binding.brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress - 100
                binding.brightnessLabel.text = "Brightness: $value"
                brightnesses[currentIndex] = value
                if (fromUser) scheduleLivePreviewUpdate()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.alignmentRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val align = when (checkedId) {
                R.id.alignLeftRadio -> SunmiPrinterHelper.ALIGN_LEFT
                R.id.alignRightRadio -> SunmiPrinterHelper.ALIGN_RIGHT
                else -> SunmiPrinterHelper.ALIGN_CENTER
            }
            alignments[currentIndex] = align
            scheduleLivePreviewUpdate()
        }

        binding.prevButton.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex--
                updateUIForCurrentIndex()
            }
        }

        binding.nextButton.setOnClickListener {
            if (currentIndex < bitmaps.size - 1) {
                currentIndex++
                updateUIForCurrentIndex()
            }
        }

        binding.printButton.setOnClickListener {
            val resultIntent = Intent().apply {
                putIntegerArrayListExtra(RESULT_SCALES, ArrayList(scales))
                putIntegerArrayListExtra(RESULT_BRIGHTNESSES, ArrayList(brightnesses))
                putIntegerArrayListExtra(RESULT_ALIGNMENTS, ArrayList(alignments))
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        binding.cancelButton.setOnClickListener { finish() }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun updateUIForCurrentIndex() {
        val bitmap = bitmaps[currentIndex]
        if (!isReadOnly) {
            binding.originalImage.setImageBitmap(bitmap)
            binding.scaleSeekBar.progress = scales[currentIndex] - 10
            binding.scaleLabel.text = "Scale: ${scales[currentIndex]}%"
            binding.brightnessSeekBar.progress = brightnesses[currentIndex] + 100
            binding.brightnessLabel.text = "Brightness: ${brightnesses[currentIndex]}"
            when (alignments[currentIndex]) {
                SunmiPrinterHelper.ALIGN_LEFT -> binding.alignLeftRadio.isChecked = true
                SunmiPrinterHelper.ALIGN_RIGHT -> binding.alignRightRadio.isChecked = true
                else -> binding.alignCenterRadio.isChecked = true
            }
        }

        binding.indexLabel.text = "${currentIndex + 1} / ${bitmaps.size}"
        binding.prevButton.isEnabled = currentIndex > 0
        binding.nextButton.isEnabled = currentIndex < bitmaps.size - 1

        scheduleLivePreviewUpdate()
    }

    private fun calculateLengthMm(bitmap: Bitmap, scalePercent: Int): Double {
        val targetWidth = (printerWidthPx * scalePercent.coerceIn(1, 100) / 100f)
            .roundToInt()
            .coerceAtLeast(1)
        val targetHeight = (bitmap.height.toFloat() * targetWidth / bitmap.width).roundToInt().coerceAtLeast(1)
        return targetHeight / 8.0
    }

    private var previewJob: Job? = null
    private fun scheduleLivePreviewUpdate() {
        previewJob?.cancel()
        val bitmap = bitmaps[currentIndex]
        val scale = scales[currentIndex]
        val brightness = brightnesses[currentIndex]
        val alignment = alignments[currentIndex]
        
        previewJob = lifecycleScope.launch {
            if (!isReadOnly) delay(120)
            val composed = withContext(Dispatchers.Default) {
                // We ALWAYS dither in the preview side to show what it will look like printed
                val printReady = if (isReadOnly) {
                    // In read-only mode, we still show a dithered simulation of the original bitmap
                    PrintImageUtils.prepareForPrint(bitmap, printerWidthPx, 100, 0)
                } else {
                    PrintImageUtils.prepareForPrint(bitmap, printerWidthPx, scale, brightness)
                }
                
                val currentMm = printReady.height / 8.0
                var totalMm = 0.0
                
                if (isReadOnly) {
                    totalMm = bitmaps.sumOf { 
                        val ready = PrintImageUtils.prepareForPrint(it, printerWidthPx, 100, 0)
                        ready.height / 8.0 
                    }
                } else {
                    for (i in bitmaps.indices) {
                        if (i == currentIndex) {
                            totalMm += currentMm
                        } else {
                            totalMm += calculateLengthMm(bitmaps[i], scales[i])
                        }
                    }
                }
                
                val composed = PrintImageUtils.composePrintPreview(printReady, printerWidthPx, if (isReadOnly) SunmiPrinterHelper.ALIGN_CENTER else alignment)
                Triple(composed, currentMm, totalMm)
            }
            binding.adjustPreviewImage.setImageBitmap(composed.first)
            
            val label = if (bitmaps.size > 1) {
                String.format(java.util.Locale.getDefault(), "Frame length: %.1f mm | Total: %.1f mm", composed.second, composed.third)
            } else {
                String.format(java.util.Locale.getDefault(), "Estimated paper length: %.1f mm", composed.second)
            }
            binding.paperLengthEstimation.text = label
        }
    }
}
