package com.cadrega.posprinter

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cadrega.posprinter.databinding.ActivitySettingsBinding
import com.cadrega.posprinter.databinding.ViewFeatureDefaultsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager
    private lateinit var printerHelper: SunmiPrinterHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager(this)
        printerHelper = SunmiPrinterHelper(this)
        printerHelper.connect(onConnected = {})

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupHardwareSettings()
        setupApplicationSettings()
        
        // Setup Feature Defaults
        setupFeatureDefaults(
            binding.photoDefaults,
            "Photo",
            { settingsManager.photoScale }, { settingsManager.photoScale = it },
            { settingsManager.photoBrightness }, { settingsManager.photoBrightness = it },
            { settingsManager.photoGamma }, { settingsManager.photoGamma = it },
            { settingsManager.photoDither }, { settingsManager.photoDither = it },
            { settingsManager.photoAlign }, { settingsManager.photoAlign = it }
        )

        setupFeatureDefaults(
            binding.batchDefaults,
            "Batch",
            { settingsManager.batchScale }, { settingsManager.batchScale = it },
            { settingsManager.batchBrightness }, { settingsManager.batchBrightness = it },
            { settingsManager.batchGamma }, { settingsManager.batchGamma = it },
            { settingsManager.batchDither }, { settingsManager.batchDither = it },
            { settingsManager.batchAlign }, { settingsManager.batchAlign = it },
            { settingsManager.batchGap }, { settingsManager.batchGap = it }
        )

        setupFeatureDefaults(
            binding.videoDefaults,
            "Video",
            { settingsManager.videoScale }, { settingsManager.videoScale = it },
            { settingsManager.videoBrightness }, { settingsManager.videoBrightness = it },
            { settingsManager.videoGamma }, { settingsManager.videoGamma = it },
            { settingsManager.videoDither }, { settingsManager.videoDither = it },
            { settingsManager.videoAlign }, { settingsManager.videoAlign = it },
            { settingsManager.videoGap }, { settingsManager.videoGap = it }
        )
    }

    private fun setupHardwareSettings() {
        updateWidthUI()
        binding.widthRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            settingsManager.printerWidth = if (checkedId == R.id.width80Radio) {
                SunmiPrinterHelper.PRINTER_WIDTH_80MM
            } else {
                SunmiPrinterHelper.PRINTER_WIDTH_58MM
            }
        }

        binding.detectWidthButton.setOnClickListener {
            val model = printerHelper.getPrinterModel()
            if (model != null) {
                if (model.contains("T2") || model.contains("T1") || model.contains("S2")) {
                    settingsManager.printerWidth = SunmiPrinterHelper.PRINTER_WIDTH_80MM
                    Toast.makeText(this, "Detected 80mm ($model)", Toast.LENGTH_SHORT).show()
                } else {
                    settingsManager.printerWidth = SunmiPrinterHelper.PRINTER_WIDTH_58MM
                    Toast.makeText(this, "Detected 58mm ($model)", Toast.LENGTH_SHORT).show()
                }
                updateWidthUI()
            } else {
                Toast.makeText(this, "Printer not connected", Toast.LENGTH_SHORT).show()
            }
        }

        binding.feedLinesSeekBar.progress = settingsManager.feedLines
        binding.feedLinesLabel.text = "Post-print feed lines: ${settingsManager.feedLines}"
        binding.feedLinesSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(1)
                settingsManager.feedLines = value
                binding.feedLinesLabel.text = "Post-print feed lines: $value"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupApplicationSettings() {
        binding.historyLimitSeekBar.progress = settingsManager.historyLimit
        binding.historyLimitLabel.text = "History Limit: ${settingsManager.historyLimit}"
        binding.historyLimitSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(5)
                settingsManager.historyLimit = value
                binding.historyLimitLabel.text = "History Limit: $value"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val currentScale = (settingsManager.fontScale * 50).toInt()
        binding.fontScaleSeekBar.progress = currentScale
        binding.fontScaleLabel.text = "Text Font Scale: ${(settingsManager.fontScale * 100).toInt()}%"
        binding.fontScaleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress + 25) / 50f
                settingsManager.fontScale = value
                binding.fontScaleLabel.text = "Text Font Scale: ${(value * 100).toInt()}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupFeatureDefaults(
        sub: ViewFeatureDefaultsBinding,
        name: String,
        getScale: () -> Int, setScale: (Int) -> Unit,
        getBrightness: () -> Int, setBrightness: (Int) -> Unit,
        getGamma: () -> Float, setGamma: (Float) -> Unit,
        getDither: () -> Int, setDither: (Int) -> Unit,
        getAlign: () -> Int, setAlign: (Int) -> Unit,
        getGap: (() -> Int)? = null, setGap: ((Int) -> Unit)? = null
    ) {
        // Scale
        sub.scaleSeekBar.progress = getScale() - 10
        sub.scaleLabel.text = "Default Scale: ${getScale()}%"
        sub.scaleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress + 10
                setScale(value)
                sub.scaleLabel.text = "Default Scale: $value%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Brightness
        sub.brightnessSeekBar.progress = getBrightness() + 100
        sub.brightnessLabel.text = "Default Brightness: ${getBrightness()}"
        sub.brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress - 100
                setBrightness(value)
                sub.brightnessLabel.text = "Default Brightness: $value"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Gamma
        sub.gammaSeekBar.progress = (getGamma() * 120 - 20).toInt()
        sub.gammaLabel.text = String.format("Default Gamma: %.2f", getGamma())
        sub.gammaSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress + 20) / 120f
                setGamma(value)
                sub.gammaLabel.text = String.format("Default Gamma: %.2f", value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Dither
        val ditherNames = PrintImageUtils.DitherAlgorithm.entries.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ditherNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sub.ditherSpinner.adapter = adapter
        sub.ditherSpinner.setSelection(getDither())
        sub.ditherSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                setDither(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Align
        when (getAlign()) {
            SunmiPrinterHelper.ALIGN_LEFT -> sub.alignLeftRadio.isChecked = true
            SunmiPrinterHelper.ALIGN_RIGHT -> sub.alignRightRadio.isChecked = true
            else -> sub.alignCenterRadio.isChecked = true
        }
        sub.alignmentRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val align = when (checkedId) {
                R.id.alignLeftRadio -> SunmiPrinterHelper.ALIGN_LEFT
                R.id.alignRightRadio -> SunmiPrinterHelper.ALIGN_RIGHT
                else -> SunmiPrinterHelper.ALIGN_CENTER
            }
            setAlign(align)
        }

        // Gap
        if (getGap != null && setGap != null) {
            sub.gapContainer.visibility = View.VISIBLE
            sub.gapSeekBar.progress = getGap()
            sub.gapLabel.text = "Default Gap: ${getGap()} mm"
            sub.gapSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    setGap(progress)
                    sub.gapLabel.text = "Default Gap: $progress mm"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    private fun updateWidthUI() {
        if (settingsManager.printerWidth == SunmiPrinterHelper.PRINTER_WIDTH_80MM) {
            binding.width80Radio.isChecked = true
        } else {
            binding.width58Radio.isChecked = true
        }
    }

    override fun onDestroy() {
        printerHelper.disconnect()
        super.onDestroy()
    }
}
