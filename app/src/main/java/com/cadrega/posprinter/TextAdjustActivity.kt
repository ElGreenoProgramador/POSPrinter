package com.cadrega.posprinter

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cadrega.posprinter.databinding.ActivityTextAdjustBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TextAdjustActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextAdjustBinding
    private lateinit var settingsManager: SettingsManager
    private var printerWidthPx = SunmiPrinterHelper.PRINTER_WIDTH_58MM
    
    private val pickTextFileLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri?.let { 
                loadTextFromFile(it) 
            }
        }

    companion object {
        const val EXTRA_TEXT = "extra_text"
        const val RESULT_TEXT = "result_text"
    }

    private var initialText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextAdjustBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsManager = SettingsManager(this)
        printerWidthPx = settingsManager.printerWidth

        initialText = intent.getStringExtra(EXTRA_TEXT) ?: ""
        binding.textInput.setText(initialText)

        binding.toolbar.inflateMenu(R.menu.menu_adjust)
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_undo) {
                binding.textInput.setText(initialText)
                true
            } else false
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.loadFileButton.setOnClickListener {
            pickTextFileLauncher.launch("text/*")
        }

        binding.textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                schedulePreviewUpdate()
            }
        })

        binding.printButton.setOnClickListener {
            val text = binding.textInput.text.toString()
            val resultIntent = Intent().apply {
                putExtra(RESULT_TEXT, text)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        schedulePreviewUpdate()
    }

    private fun loadTextFromFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                binding.loadingIndicator.visibility = View.VISIBLE
            }
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val text = inputStream.bufferedReader().use { it.readText() }
                    // Limit text to 50k chars for smoother editing
                    val limitedText = if (text.length > 50000) text.substring(0, 50000) else text
                    withContext(Dispatchers.Main) {
                        binding.textInput.setText(limitedText)
                    }
                }
            } catch (e: Exception) {
                Log.e("TextAdjust", "Error loading file", e)
            } finally {
                withContext(Dispatchers.Main) {
                    binding.loadingIndicator.visibility = View.GONE
                }
            }
        }
    }

    private var previewJob: Job? = null
    private fun schedulePreviewUpdate() {
        previewJob?.cancel()
        val text = binding.textInput.text.toString()
        
        previewJob = lifecycleScope.launch {
            delay(500) // Debounce typing
            val previewBitmap = withContext(Dispatchers.Default) {
                renderMarkdownPreview(text)
            }
            binding.textPreviewImage.setImageBitmap(previewBitmap)
        }
    }

    private fun renderMarkdownPreview(text: String): Bitmap {
        val scale = settingsManager.fontScale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 24f * scale
        }
        
        val lines = text.split("\n")
        val renderedLines = mutableListOf<RenderedLine>()
        
        var totalHeight = 30f
        
        for (line in lines) {
            val trimmed = line.trim()
            val style = when {
                trimmed.startsWith("# ") -> RenderStyle(trimmed.substring(2), 38f * scale, true, SunmiPrinterHelper.ALIGN_CENTER)
                trimmed.startsWith("## ") -> RenderStyle(trimmed.substring(3), 32f * scale, true, SunmiPrinterHelper.ALIGN_CENTER)
                trimmed.startsWith("### ") -> RenderStyle(trimmed.substring(4), 28f * scale, true, SunmiPrinterHelper.ALIGN_LEFT)
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> RenderStyle(" • " + trimmed.substring(2), 24f * scale, false, SunmiPrinterHelper.ALIGN_LEFT)
                trimmed.contains("**") -> RenderStyle(trimmed.replace("**", ""), 24f * scale, true, SunmiPrinterHelper.ALIGN_LEFT)
                else -> RenderStyle(line, 24f * scale, false, SunmiPrinterHelper.ALIGN_LEFT)
            }
            
            paint.textSize = style.size
            paint.isFakeBoldText = style.bold
            
            val maxWidth = printerWidthPx - 20f
            if (style.text.isEmpty()) {
                totalHeight += 24f
            } else {
                var start = 0
                while (start < style.text.length) {
                    val count = paint.breakText(style.text, start, style.text.length, true, maxWidth, null)
                    val part = style.text.substring(start, start + count)
                    renderedLines.add(RenderedLine(part, style.size, style.bold, style.align))
                    totalHeight += style.size + 6f
                    start += count
                }
            }
        }
        
        val bitmap = Bitmap.createBitmap(printerWidthPx, totalHeight.toInt().coerceAtLeast(100), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        
        var y = 40f
        for (rl in renderedLines) {
            paint.textSize = rl.size
            paint.isFakeBoldText = rl.bold
            val textWidth = paint.measureText(rl.text)
            val x = when (rl.align) {
                SunmiPrinterHelper.ALIGN_CENTER -> (printerWidthPx - textWidth) / 2
                SunmiPrinterHelper.ALIGN_RIGHT -> printerWidthPx - textWidth - 10f
                else -> 10f
            }
            canvas.drawText(rl.text, x, y, paint)
            y += rl.size + 6f
        }
        
        return PrintImageUtils.ditherForThermalPrint(bitmap)
    }

    private data class RenderStyle(val text: String, val size: Float, val bold: Boolean, val align: Int)
    private data class RenderedLine(val text: String, val size: Float, val bold: Boolean, val align: Int)
}
