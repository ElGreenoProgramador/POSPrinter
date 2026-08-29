package com.cadrega.posprinter

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cadrega.posprinter.databinding.ActivityCompositeJobBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

class CompositeJobActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompositeJobBinding
    private val actions = mutableListOf<PrintAction>()
    private lateinit var adapter: CompositeJobAdapter
    
    private lateinit var printer: SunmiPrinterHelper
    private lateinit var settingsManager: SettingsManager
    private lateinit var historyManager: PrintHistoryManager
    private lateinit var frameExtractor: VideoFrameExtractor
    
    private var lastSelectedVideoUri: Uri? = null
    private var adjustResultCont: CancellableContinuation<List<ImageAdjustSettings>?>? = null
    private var lastAdjustData: Intent? = null
    
    private val adjustLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        lastAdjustData = result.data
        val settingsList = if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data != null) {
                val scales = data.getIntegerArrayListExtra(ImageAdjustActivity.RESULT_SCALES) ?: emptyList<Int>()
                val brightnesses = data.getIntegerArrayListExtra(ImageAdjustActivity.RESULT_BRIGHTNESSES) ?: emptyList<Int>()
                val alignments = data.getIntegerArrayListExtra(ImageAdjustActivity.RESULT_ALIGNMENTS) ?: emptyList<Int>()
                val gammas = data.getFloatArrayExtra(ImageAdjustActivity.RESULT_GAMMAS)
                val algorithms = data.getIntArrayExtra(ImageAdjustActivity.RESULT_ALGORITHMS)
                
                scales.indices.map { i ->
                    ImageAdjustSettings(
                        scales[i], 
                        brightnesses[i], 
                        alignments[i],
                        gammas?.getOrNull(i) ?: 1.0f,
                        PrintImageUtils.DitherAlgorithm.entries[algorithms?.getOrNull(i) ?: 0]
                    )
                }
            } else emptyList()
        } else null
        ImageAdjustActivity.bitmapsToAdjust = emptyList()
        adjustResultCont?.resume(settingsList)
        adjustResultCont = null
    }

    private val textLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = result.data?.getStringExtra(TextAdjustActivity.RESULT_TEXT)
            if (!text.isNullOrBlank()) {
                actions.add(PrintAction.Text(text))
                updateUI()
            }
        }
    }

    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handlePhotoPicked(it) }
    }
    
    private val pickMultiplePhotosLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) handleBatchPhotosPicked(uris)
    }

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleVideoPicked(it) }
    }

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val frames = VideoFramePickerActivity.selectedFrames
            val uri = lastSelectedVideoUri
            if (frames.isNotEmpty() && (uri != null)) {
                handleFramesSelected(frames)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompositeJobBinding.inflate(layoutInflater)
        setContentView(binding.root)

        printer = SunmiPrinterHelper(this)
        settingsManager = SettingsManager(this)
        historyManager = PrintHistoryManager(this)
        frameExtractor = VideoFrameExtractor(this)

        setupRecyclerView()
        
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        binding.addActionButton.setOnClickListener {
            showAddActionDialog()
        }
        
        binding.launchJobButton.setOnClickListener {
            launchFinalJob()
        }
        
        updateUI()
    }

    private fun setupRecyclerView() {
        adapter = CompositeJobAdapter(actions, { viewHolder ->
            itemTouchHelper.startDrag(viewHolder)
        }, { position ->
            actions.removeAt(position)
            updateUI()
        })
        
        binding.actionRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.actionRecyclerView.adapter = adapter
        
        itemTouchHelper.attachToRecyclerView(binding.actionRecyclerView)
    }

    private val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            adapter.onItemMove(viewHolder.adapterPosition, target.adapterPosition)
            return true
        }
        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
    })

    private fun showAddActionDialog() {
        val options = arrayOf(
            getString(R.string.print_photo),
            getString(R.string.batch_print),
            getString(R.string.print_video),
            getString(R.string.print_text)
        )
        val icons = intArrayOf(
            android.R.drawable.ic_menu_gallery,
            android.R.drawable.ic_input_add,
            android.R.drawable.ic_menu_slideshow,
            android.R.drawable.ic_menu_edit
        )

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.select_dialog_item, android.R.id.text1, options) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val tv = v.findViewById<TextView>(android.R.id.text1)
                tv.setCompoundDrawablesWithIntrinsicBounds(icons[position], 0, 0, 0)
                tv.compoundDrawablePadding = (16 * resources.displayMetrics.density).toInt()
                return v
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Action")
            .setAdapter(adapter) { _, which ->
                when (which) {
                    0 -> pickPhotoLauncher.launch("image/*")
                    1 -> pickMultiplePhotosLauncher.launch("image/*")
                    2 -> pickVideoLauncher.launch("video/*")
                    3 -> launchTextEditor()
                }
            }
            .show()
    }

    private fun launchTextEditor() {
        val intent = Intent(this, TextAdjustActivity::class.java).apply {
            putExtra(TextAdjustActivity.EXTRA_IS_FOR_COMPOSITE, true)
        }
        textLauncher.launch(intent)
    }

    private fun handlePhotoPicked(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { loadBitmap(uri) }
            if (bitmap == null) {
                toast("Could not load image")
                return@launch
            }
            
            val settings = showImageAdjustDialogs(listOf(bitmap), ImageAdjustActivity.FEATURE_TYPE_PHOTO)
            if (settings != null) {
                actions.add(PrintAction.Image(listOf(bitmap), "Photo", ImageAdjustActivity.FEATURE_TYPE_PHOTO, settings))
                updateUI()
            }
        }
    }
    
    private fun handleBatchPhotosPicked(uris: List<Uri>) {
        lifecycleScope.launch {
            val bitmaps = uris.mapNotNull { withContext(Dispatchers.IO) { loadBitmap(it) } }
            if (bitmaps.isEmpty()) {
                toast("Could not load images")
                return@launch
            }
            
            val settings = showImageAdjustDialogs(bitmaps, ImageAdjustActivity.FEATURE_TYPE_BATCH)
            if (settings != null) {
                val gap = lastAdjustData?.getIntExtra(ImageAdjustActivity.RESULT_GAP_MM, 2) ?: 2
                actions.add(PrintAction.Image(bitmaps, "Batch of ${bitmaps.size}", ImageAdjustActivity.FEATURE_TYPE_BATCH, settings, gap))
                updateUI()
            }
        }
    }
    
    private fun handleVideoPicked(uri: Uri) {
        lastSelectedVideoUri = uri
        val intent = Intent(this, VideoFramePickerActivity::class.java).apply {
            putExtra("video_uri", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        videoPickerLauncher.launch(intent)
    }
    
    private fun handleFramesSelected(frames: List<VideoFramePickerActivity.SelectedFrame>) {
        val bitmaps = frames.map { it.bitmap }
        lifecycleScope.launch {
            val settings = showImageAdjustDialogs(bitmaps, ImageAdjustActivity.FEATURE_TYPE_VIDEO)
            if (settings != null) {
                val gap = lastAdjustData?.getIntExtra(ImageAdjustActivity.RESULT_GAP_MM, 2) ?: 2
                actions.add(PrintAction.Image(bitmaps, "Video frames (${bitmaps.size})", ImageAdjustActivity.FEATURE_TYPE_VIDEO, settings, gap))
                updateUI()
            }
            VideoFramePickerActivity.selectedFrames = emptyList()
        }
    }

    private suspend fun showImageAdjustDialogs(
        previews: List<Bitmap>,
        featureType: String
    ): List<ImageAdjustSettings>? =
        suspendCancellableCoroutine { cont ->
            adjustResultCont = cont
            ImageAdjustActivity.bitmapsToAdjust = previews
            val intent = Intent(this, ImageAdjustActivity::class.java).apply {
                putExtra(ImageAdjustActivity.EXTRA_FEATURE_TYPE, featureType)
                putExtra(ImageAdjustActivity.EXTRA_IS_FOR_COMPOSITE, true)
            }
            adjustLauncher.launch(intent)
        }

    private fun updateUI() {
        adapter.notifyDataSetChanged()
        binding.emptyView.visibility = if (actions.isEmpty()) View.VISIBLE else View.GONE
        binding.launchJobButton.isEnabled = actions.isNotEmpty()
    }

    private fun launchFinalJob() {
        lifecycleScope.launch(Dispatchers.Main) {
            if (!printer.isConnected) {
                toast("Printer not connected")
            }

            toast("Starting composite job...")
            val finalPreparedBitmaps = mutableListOf<Bitmap>()
            val compositeDescription = mutableListOf<String>()

            try {
                for (action in actions) {
                    when (action) {
                        is PrintAction.Text -> {
                            val prepared = withContext(Dispatchers.Default) {
                                PrintImageUtils.renderMarkdownToBitmap(action.content, settingsManager.printerWidth, settingsManager.fontScale)
                            }
                            finalPreparedBitmaps.add(prepared)
                            printer.printBitmap(prepared)
                            compositeDescription.add("Text")
                        }
                        is PrintAction.Image -> {
                            for ((index, bitmap) in action.bitmaps.withIndex()) {
                                val s = action.settings?.getOrNull(index) ?: ImageAdjustSettings(100, 0, SunmiPrinterHelper.ALIGN_CENTER)
                                val prepared = withContext(Dispatchers.Default) {
                                    PrintImageUtils.prepareForPrint(
                                        bitmap, settingsManager.printerWidth, s.scalePercent, s.brightness, s.gamma, s.algorithm
                                    )
                                }
                                finalPreparedBitmaps.add(prepared)
                                printer.setAlignment(s.alignment)
                                printer.printBitmap(prepared)
                                if (index < action.bitmaps.size - 1) {
                                    printer.feedPaper(action.gapMm)
                                }
                            }
                            compositeDescription.add(when(action.featureType) {
                                ImageAdjustActivity.FEATURE_TYPE_BATCH -> "Batch"
                                ImageAdjustActivity.FEATURE_TYPE_VIDEO -> "Video"
                                else -> "Photo"
                            })
                        }
                    }
                    printer.feedPaper(2)
                }
                printer.feedPaper(settingsManager.feedLines)
                
                // Save to history as a Composite job
                val historyDesc = "Composite: " + compositeDescription.joinToString(", ")
                historyManager.saveItem(
                    PrintHistoryItem(type = "Composite", description = historyDesc),
                    finalPreparedBitmaps
                )
                
                toast("Job finished")
                finish()
            } catch (e: Exception) {
                Log.e("CompositeJob", "Print failed", e)
                toast("Print failed: ${e.message}")
            }
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            android.graphics.BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            null
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
