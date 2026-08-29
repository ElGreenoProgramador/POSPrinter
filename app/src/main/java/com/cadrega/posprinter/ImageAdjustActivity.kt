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
    private lateinit var settingsManager: SettingsManager
    private var printerWidthPx = SunmiPrinterHelper.PRINTER_WIDTH_58MM
    
    private var currentIndex = 0
    private var bitmaps: List<Bitmap> = emptyList()
    private var isReadOnly = false

    companion object {
        var bitmapsToAdjust: List<Bitmap> = emptyList()
        const val EXTRA_READ_ONLY = "extra_read_only"
        
        const val RESULT_SCALES = "result_scales"
        const val RESULT_BRIGHTNESSES = "result_brightnesses"
        const val RESULT_ALIGNMENTS = "result_alignments"
        const val RESULT_GAMMAS = "result_gammas"
        const val RESULT_ALGORITHMS = "result_algorithms"
        const val RESULT_GAP_MM = "result_gap_mm"
    }

    private val scales = mutableListOf<Int>()
    private val brightnesses = mutableListOf<Int>()
    private val gammas = mutableListOf<Float>()
    private val algorithms = mutableListOf<PrintImageUtils.DitherAlgorithm>()
    private val alignments = mutableListOf<Int>()
    private var gapMm = 2

    // Initial state for Undo
    private var initialScales = listOf<Int>()
    private var initialBrightnesses = listOf<Int>()
    private var initialGammas = listOf<Float>()
    private var initialAlgorithms = listOf<PrintImageUtils.DitherAlgorithm>()
    private var initialAlignments = listOf<Int>()
    private var initialGapMm = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageAdjustBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager(this)
        printerWidthPx = settingsManager.printerWidth

        bitmaps = bitmapsToAdjust
        if (bitmaps.isEmpty()) {
            finish()
            return
        }

        isReadOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, false)

        // Initialize settings
        for (i in bitmaps.indices) {
            scales.add(100)
            brightnesses.add(0)
            gammas.add(1.0f)
            algorithms.add(PrintImageUtils.DitherAlgorithm.FloydSteinberg)
            alignments.add(SunmiPrinterHelper.ALIGN_CENTER)
        }
        captureInitialState()

        setupToolbar()
        setupButtons()
        setupControls()
        
        if (bitmaps.size > 1 && !isReadOnly) {
            binding.batchControlsContainer.visibility = View.VISIBLE
            binding.gapContainer.visibility = View.VISIBLE
        }

        updateUIForCurrentIndex()
    }

    private fun captureInitialState() {
        initialScales = scales.toList()
        initialBrightnesses = brightnesses.toList()
        initialGammas = gammas.toList()
        initialAlgorithms = algorithms.toList()
        initialAlignments = alignments.toList()
        initialGapMm = gapMm
    }

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.menu_adjust)
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_undo) {
                performUndo()
                true
            } else false
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        if (isReadOnly) {
            binding.toolbar.title = if (bitmaps.size > 1) "History Batch (${bitmaps.size})" else "History Preview"
        } else if (bitmaps.size > 1) {
            binding.toolbar.title = "Adjust Batch (${bitmaps.size})"
        }
    }

    private fun setupButtons() {
        binding.editSettingsButton.setOnClickListener {
            isReadOnly = false
            binding.editSettingsButton.visibility = View.GONE
            binding.originalImageContainer.visibility = View.VISIBLE
            binding.settingsContainer.visibility = View.VISIBLE
            binding.printButton.text = "Print"
            if (bitmaps.size > 1) {
                binding.toolbar.title = "Adjust Batch (${bitmaps.size})"
                binding.batchControlsContainer.visibility = View.VISIBLE
                binding.gapContainer.visibility = View.VISIBLE
            } else {
                binding.toolbar.title = "Adjust Print Settings"
            }
            updateUIForCurrentIndex()
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
                putExtra(RESULT_GAMMAS, gammas.toFloatArray())
                putExtra(RESULT_ALGORITHMS, algorithms.map { it.ordinal }.toIntArray())
                putExtra(RESULT_GAP_MM, gapMm)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        binding.cancelButton.setOnClickListener { finish() }
    }

    private fun setupControls() {
        binding.scaleSeekBar.max = 90
        binding.scaleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 10
                binding.scaleLabel.text = "Scale: $value%"
                updateSetting { scales[currentIndex] = value; if (!binding.customSettingsSwitch.isChecked) scales.indices.forEach { scales[it] = value } }
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
                updateSetting { brightnesses[currentIndex] = value; if (!binding.customSettingsSwitch.isChecked) brightnesses.indices.forEach { brightnesses[it] = value } }
                if (fromUser) scheduleLivePreviewUpdate()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.gammaSeekBar.max = 200
        binding.gammaSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress + 20) / 120f
                binding.gammaLabel.text = String.format(java.util.Locale.getDefault(), "Gamma: %.2f", value)
                updateSetting { gammas[currentIndex] = value; if (!binding.customSettingsSwitch.isChecked) gammas.indices.forEach { gammas[it] = value } }
                if (fromUser) scheduleLivePreviewUpdate()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.gapSeekBar.max = 10
        binding.gapSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                gapMm = progress
                binding.gapLabel.text = "Frame Gap: $progress mm"
                if (fromUser) scheduleLivePreviewUpdate()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val ditherNames = PrintImageUtils.DitherAlgorithm.entries.map { it.name }
        val ditherAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, ditherNames)
        ditherAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.ditherSpinner.adapter = ditherAdapter
        binding.ditherSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val algo = PrintImageUtils.DitherAlgorithm.entries[position]
                updateSetting { algorithms[currentIndex] = algo; if (!binding.customSettingsSwitch.isChecked) algorithms.indices.forEach { algorithms[it] = algo } }
                scheduleLivePreviewUpdate()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.alignmentRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val align = when (checkedId) {
                R.id.alignLeftRadio -> SunmiPrinterHelper.ALIGN_LEFT
                R.id.alignRightRadio -> SunmiPrinterHelper.ALIGN_RIGHT
                else -> SunmiPrinterHelper.ALIGN_CENTER
            }
            updateSetting { alignments[currentIndex] = align; if (!binding.customSettingsSwitch.isChecked) alignments.indices.forEach { alignments[it] = align } }
            scheduleLivePreviewUpdate()
        }
    }

    private fun updateSetting(action: () -> Unit) {
        if (isReadOnly) return
        action()
    }

    private fun performUndo() {
        scales.clear(); scales.addAll(initialScales)
        brightnesses.clear(); brightnesses.addAll(initialBrightnesses)
        gammas.clear(); gammas.addAll(initialGammas)
        algorithms.clear(); algorithms.addAll(initialAlgorithms)
        alignments.clear(); alignments.addAll(initialAlignments)
        gapMm = initialGapMm
        updateUIForCurrentIndex()
    }

    private fun updateUIForCurrentIndex() {
        val bitmap = bitmaps[currentIndex]
        if (isReadOnly) {
            binding.originalImageContainer.visibility = View.GONE
            binding.settingsContainer.visibility = View.GONE
            binding.batchControlsContainer.visibility = View.GONE
            binding.editSettingsButton.visibility = View.VISIBLE
            binding.printButton.text = "Re-print"
        } else {
            binding.originalImageContainer.visibility = View.VISIBLE
            binding.settingsContainer.visibility = View.VISIBLE
            binding.editSettingsButton.visibility = View.GONE
            binding.printButton.text = "Print"
            
            binding.originalImage.setImageBitmap(bitmap)
            binding.scaleSeekBar.progress = scales[currentIndex] - 10
            binding.scaleLabel.text = "Scale: ${scales[currentIndex]}%"
            binding.brightnessSeekBar.progress = brightnesses[currentIndex] + 100
            binding.brightnessLabel.text = "Brightness: ${brightnesses[currentIndex]}"
            binding.gammaSeekBar.progress = (gammas[currentIndex] * 120 - 20).toInt()
            binding.gammaLabel.text = String.format(java.util.Locale.getDefault(), "Gamma: %.2f", gammas[currentIndex])
            binding.ditherSpinner.setSelection(algorithms[currentIndex].ordinal)
            binding.gapSeekBar.progress = gapMm
            binding.gapLabel.text = "Frame Gap: $gapMm mm"

            when (alignments[currentIndex]) {
                SunmiPrinterHelper.ALIGN_LEFT -> binding.alignLeftRadio.isChecked = true
                SunmiPrinterHelper.ALIGN_RIGHT -> binding.alignRightRadio.isChecked = true
                else -> binding.alignCenterRadio.isChecked = true
            }
        }

        binding.indexLabel.text = "${currentIndex + 1} / ${bitmaps.size}"
        binding.prevButton.isEnabled = currentIndex > 0
        binding.nextButton.isEnabled = currentIndex < bitmaps.size - 1
        binding.navigationContainer.visibility = if (bitmaps.size > 1) View.VISIBLE else View.GONE

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
        
        previewJob = lifecycleScope.launch {
            if (!isReadOnly) delay(120)
            val composed = withContext(Dispatchers.Default) {
                val printReady = if (isReadOnly) {
                    PrintImageUtils.prepareForPrint(bitmap, printerWidthPx, 100, 0, 1.0f, PrintImageUtils.DitherAlgorithm.FloydSteinberg)
                } else {
                    PrintImageUtils.prepareForPrint(bitmap, printerWidthPx, scales[currentIndex], brightnesses[currentIndex], gammas[currentIndex], algorithms[currentIndex])
                }
                
                val currentMm = printReady.height / 8.0
                var totalMm = 0.0
                
                if (isReadOnly) {
                    totalMm = bitmaps.sumOf { 
                        val ready = PrintImageUtils.prepareForPrint(it, printerWidthPx, 100, 0, 1.0f, PrintImageUtils.DitherAlgorithm.FloydSteinberg)
                        ready.height / 8.0 
                    }
                } else {
                    for (i in bitmaps.indices) {
                        val mm = if (i == currentIndex) currentMm else calculateLengthMm(bitmaps[i], scales[i])
                        totalMm += mm
                        if (i < bitmaps.size - 1) totalMm += gapMm
                    }
                }
                
                val composed = PrintImageUtils.composePrintPreview(printReady, printerWidthPx, if (isReadOnly) SunmiPrinterHelper.ALIGN_CENTER else alignments[currentIndex])
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
