package com.cadrega.posprinter

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cadrega.posprinter.databinding.ItemPrintActionBinding
import java.util.Collections

class CompositeJobAdapter(
    private val items: MutableList<PrintAction>,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<CompositeJobAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemPrintActionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPrintActionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        
        when (item) {
            is PrintAction.Text -> {
                holder.binding.actionTypeText.text = "Text"
                holder.binding.actionDescText.text = item.content.take(50)
                holder.binding.actionPreviewImage.setImageResource(android.R.drawable.ic_menu_edit)
            }
            is PrintAction.Image -> {
                holder.binding.actionTypeText.text = when(item.featureType) {
                    ImageAdjustActivity.FEATURE_TYPE_BATCH -> "Batch"
                    ImageAdjustActivity.FEATURE_TYPE_VIDEO -> "Video"
                    else -> "Photo"
                }
                holder.binding.actionDescText.text = item.description
                
                val iconRes = when(item.featureType) {
                    ImageAdjustActivity.FEATURE_TYPE_BATCH -> android.R.drawable.ic_input_add
                    ImageAdjustActivity.FEATURE_TYPE_VIDEO -> android.R.drawable.ic_menu_slideshow
                    else -> android.R.drawable.ic_menu_gallery
                }
                
                if (item.bitmaps.isNotEmpty()) {
                    holder.binding.actionPreviewImage.setImageBitmap(item.bitmaps.first())
                } else {
                    holder.binding.actionPreviewImage.setImageResource(iconRes)
                }
            }
        }

        holder.binding.dragHandle.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
            }
            false
        }

        holder.binding.deleteActionButton.setOnClickListener {
            onDelete(holder.adapterPosition)
        }
    }

    override fun getItemCount() = items.size

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(items, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(items, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
    }
}
