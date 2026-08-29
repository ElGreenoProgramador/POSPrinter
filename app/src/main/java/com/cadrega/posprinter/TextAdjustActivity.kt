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
import androidx.activity.addCallback
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
        const val EXTRA_IS_FOR_COMPOSITE = "extra_is_for_composite"
    }

    private var initialText = ""
    private var hasChanges = false

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
                hasChanges = false
                true
            } else false
        }
        binding.toolbar.setNavigationOnClickListener { handleBack() }
        
        if (intent.getBooleanExtra(EXTRA_IS_FOR_COMPOSITE, false)) {
            binding.printButton.text = "Add"
        }

        binding.loadFileButton.setOnClickListener {
            pickTextFileLauncher.launch("text/*")
        }

        binding.textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                hasChanges = s?.toString() != initialText
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
        
        onBackPressedDispatcher.addCallback(this) { handleBack() }
    }

    private fun handleBack() {
        if (hasChanges) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Discard Changes?")
                .setMessage("You have unsaved edits. Are you sure you want to go back?")
                .setPositiveButton("Discard") { _, _ -> finish() }
                .setNegativeButton("Keep Editing", null)
                .show()
        } else {
            finish()
        }
    }

    private fun loadTextFromFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                binding.loadingIndicator.visibility = View.VISIBLE
            }
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val text = inputStream.bufferedReader().use { it.readText() }
                    val limitedText = if (text.length > 50000) text.substring(0, 50000) else text
                    withContext(Dispatchers.Main) {
                        binding.textInput.setText(limitedText)
                        hasChanges = true
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
                PrintImageUtils.renderMarkdownToBitmap(text, printerWidthPx, settingsManager.fontScale)
            }
            binding.textPreviewImage.setImageBitmap(previewBitmap)
        }
    }
}
