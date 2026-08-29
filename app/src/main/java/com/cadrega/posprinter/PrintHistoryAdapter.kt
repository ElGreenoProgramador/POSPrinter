package com.cadrega.posprinter

import android.content.res.ColorStateList
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cadrega.posprinter.databinding.ItemPrintHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PrintHistoryAdapter(
    private val historyManager: PrintHistoryManager,
    private val onAction: (PrintHistoryItem, PrintHistoryManager.RelaunchStatus) -> Unit
) : RecyclerView.Adapter<PrintHistoryAdapter.ViewHolder>() {

    private var items = listOf<PrintHistoryItem>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun updateItems(newItems: List<PrintHistoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(val binding: ItemPrintHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPrintHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.historyTypeText.text = item.type
        holder.binding.historyDescText.text = "${item.description} • ${dateFormat.format(Date(item.timestamp))}"
        
        val bitmap = item.imagePaths.firstOrNull()?.let { historyManager.loadBitmap(it) }
        holder.binding.historyPreviewImage.setImageBitmap(bitmap)

        val status = historyManager.getRelaunchStatus(item)
        updateStatusIcon(holder, status)

        holder.itemView.setOnClickListener {
            Log.d("PrintHistoryAdapter", "Item clicked: ${item.id}, status: $status")
            onAction(item, status)
        }
    }

    private fun updateStatusIcon(holder: ViewHolder, status: PrintHistoryManager.RelaunchStatus) {
        val (icon, color) = when (status) {
            is PrintHistoryManager.RelaunchStatus.Ready -> {
                android.R.drawable.ic_input_add to Color.parseColor("#4CAF50") // Green plus
            }
            is PrintHistoryManager.RelaunchStatus.MissingFiles -> {
                android.R.drawable.stat_notify_error to Color.parseColor("#F44336") // Red error
            }
            is PrintHistoryManager.RelaunchStatus.MissingData -> {
                android.R.drawable.ic_dialog_alert to Color.parseColor("#FF9800") // Orange alert
            }
        }
        holder.binding.statusIcon.setImageResource(icon)
        holder.binding.statusIcon.imageTintList = ColorStateList.valueOf(color)
    }

    override fun getItemCount() = items.size
}
