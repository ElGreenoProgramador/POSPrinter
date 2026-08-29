package com.cadrega.posprinter

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cadrega.posprinter.databinding.ActivityVideoFramePickerBinding
import com.cadrega.posprinter.databinding.ItemVideoFrameBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoFramePickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoFramePickerBinding
    private lateinit var frameExtractor: VideoFrameExtractor
    private val frames = mutableListOf<FrameItem>()

    data class SelectedFrame(val bitmap: Bitmap, val timestampMs: Long)

    companion object {
        var selectedFrames: List<SelectedFrame> = emptyList()
    }

    data class FrameItem(val bitmap: Bitmap, val timestampMs: Long, var isSelected: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoFramePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        frameExtractor = VideoFrameExtractor(this)
        val videoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("video_uri", Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("video_uri")
        } ?: run {
            Log.e("VideoFramePicker", "No video URI provided")
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        val adapter = FrameAdapter(frames) {
            updateConfirmButton()
        }
        binding.frameRecyclerView.layoutManager = GridLayoutManager(this, 3)
        binding.frameRecyclerView.adapter = adapter

        lifecycleScope.launch {
            binding.loadingIndicator.visibility = android.view.View.VISIBLE
            try {
                // Default sampling: every 500ms for selection
                frameExtractor.extractFrames(videoUri, 500L) { index, _, bitmap ->
                    withContext(Dispatchers.Main) {
                        frames.add(FrameItem(bitmap, index * 500L))
                        adapter.notifyItemInserted(frames.size - 1)
                    }
                    true
                }
                if (frames.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Log.w("VideoFramePicker", "No frames extracted from video")
                        Toast.makeText(this@VideoFramePickerActivity, "No frames could be extracted from this video", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("VideoFramePicker", "Error extracting frames", e)
            } finally {
                withContext(Dispatchers.Main) {
                    binding.loadingIndicator.visibility = android.view.View.GONE
                }
            }
        }

        binding.confirmButton.setOnClickListener {
            selectedFrames = frames.filter { it.isSelected }.map { SelectedFrame(it.bitmap, it.timestampMs) }
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun updateConfirmButton() {
        val count = frames.count { it.isSelected }
        binding.confirmButton.isEnabled = count > 0
        binding.confirmButton.text = if (count > 0) "Process $count Frame(s)" else "Process Selected Frames"
    }

    class FrameAdapter(private val items: List<FrameItem>, private val onSelectionChanged: () -> Unit) :
        RecyclerView.Adapter<FrameAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemVideoFrameBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemVideoFrameBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.binding.frameImageView.setImageBitmap(item.bitmap)
            holder.binding.frameCheckBox.isChecked = item.isSelected
            holder.binding.timestampText.text = formatTimestamp(item.timestampMs)
            
            // Visual feedback for selection
            holder.binding.selectionOverlay.visibility = if (item.isSelected) View.VISIBLE else View.GONE
            val strokeColor = if (item.isSelected) {
                holder.itemView.context.getColor(R.color.primary)
            } else {
                val typedValue = android.util.TypedValue()
                holder.itemView.context.theme.resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, typedValue, true)
                typedValue.data
            }
            holder.binding.root.strokeColor = strokeColor
            holder.binding.root.strokeWidth = if (item.isSelected) 8 else 2

            holder.itemView.setOnClickListener {
                item.isSelected = !item.isSelected
                notifyItemChanged(position)
                onSelectionChanged()
            }
        }

        override fun getItemCount() = items.size

        private fun formatTimestamp(ms: Long): String {
            val seconds = (ms / 1000) % 60
            val minutes = (ms / (1000 * 60)) % 60
            return String.format("%02d:%02d", minutes, seconds)
        }
    }
}
