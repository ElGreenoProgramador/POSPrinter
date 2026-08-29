package com.cadrega.posprinter

import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cadrega.posprinter.databinding.ActivitySettingsBinding

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

        // Paper Width
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
                // Heuristic: T-series and large tablets are usually 80mm
                if (model.contains("T2") || model.contains("T1") || model.contains("S2")) {
                    settingsManager.printerWidth = SunmiPrinterHelper.PRINTER_WIDTH_80MM
                    Toast.makeText(this, "Detected 80mm printer ($model)", Toast.LENGTH_SHORT).show()
                } else {
                    settingsManager.printerWidth = SunmiPrinterHelper.PRINTER_WIDTH_58MM
                    Toast.makeText(this, "Detected 58mm printer ($model)", Toast.LENGTH_SHORT).show()
                }
                updateWidthUI()
            } else {
                Toast.makeText(this, "Printer not connected - could not detect", Toast.LENGTH_SHORT).show()
            }
        }

        // Feed Lines
        binding.feedLinesSeekBar.progress = settingsManager.feedLines
        binding.feedLinesLabel.text = "Paper Feed Lines: ${settingsManager.feedLines}"
        binding.feedLinesSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(1)
                settingsManager.feedLines = value
                binding.feedLinesLabel.text = "Paper Feed Lines: $value"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // History Limit
        binding.historyLimitSeekBar.progress = settingsManager.historyLimit
        binding.historyLimitLabel.text = "History Item Limit: ${settingsManager.historyLimit}"
        binding.historyLimitSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(5)
                settingsManager.historyLimit = value
                binding.historyLimitLabel.text = "History Item Limit: $value"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Font Scale
        val currentScale = (settingsManager.fontScale * 50).toInt()
        binding.fontScaleSeekBar.progress = currentScale
        binding.fontScaleLabel.text = "Text Font Scale: ${(settingsManager.fontScale * 100).toInt()}%"
        binding.fontScaleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress + 25) / 50f // 0.5 to 2.5
                settingsManager.fontScale = value
                binding.fontScaleLabel.text = "Text Font Scale: ${(value * 100).toInt()}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
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
