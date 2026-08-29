package com.cadrega.posprinter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class PrintHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: String,
    val description: String,
    val imagePaths: List<String> = emptyList(),
    val extraData: String? = null
)

class PrintHistoryManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("print_history", Context.MODE_PRIVATE)
    private val historyDir = File(context.filesDir, "history_previews").apply { mkdirs() }
    private val settingsManager = SettingsManager(context)

    fun saveItem(item: PrintHistoryItem, bitmaps: List<Bitmap>) {
        val paths = bitmaps.mapIndexed { index, bitmap ->
            saveBitmap(bitmap, "${item.id}_$index")
        }
        val updatedItem = item.copy(imagePaths = paths)
        
        val history = getHistory().toMutableList()
        history.add(0, updatedItem)
        
        // Keep history within limit
        val limit = settingsManager.historyLimit
        if (history.size > limit) {
            for (i in limit until history.size) {
                history[i].imagePaths.forEach { File(it).delete() }
            }
            while (history.size > limit) {
                history.removeAt(limit)
            }
        }
        
        saveHistory(history)
    }

    fun getHistory(): List<PrintHistoryItem> {
        val json = prefs.getString("history_json", "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<PrintHistoryItem>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val pathArray = obj.optJSONArray("imagePaths")
            val paths = mutableListOf<String>()
            if (pathArray != null) {
                for (j in 0 until pathArray.length()) {
                    paths.add(pathArray.getString(j))
                }
            }
            list.add(PrintHistoryItem(
                obj.getString("id"),
                obj.getLong("timestamp"),
                obj.getString("type"),
                obj.getString("description"),
                paths,
                if (obj.isNull("extraData")) null else obj.getString("extraData")
            ))
        }
        return list
    }

    fun clearHistory() {
        historyDir.listFiles()?.forEach { it.delete() }
        prefs.edit().putString("history_json", "[]").apply()
    }

    private fun saveBitmap(bitmap: Bitmap, name: String): String {
        val file = File(historyDir, "$name.png")
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return file.absolutePath
    }

    private fun saveHistory(list: List<PrintHistoryItem>) {
        val array = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("timestamp", item.timestamp)
                put("type", item.type)
                put("description", item.description)
                put("imagePaths", JSONArray(item.imagePaths))
                put("extraData", item.extraData)
            }
            array.put(obj)
        }
        prefs.edit().putString("history_json", array.toString()).apply()
    }

    fun loadBitmap(path: String): Bitmap? {
        return BitmapFactory.decodeFile(path)
    }

    sealed class RelaunchStatus {
        object Ready : RelaunchStatus()
        data class MissingFiles(val missingCount: Int) : RelaunchStatus()
        object MissingData : RelaunchStatus()
    }

    fun getRelaunchStatus(item: PrintHistoryItem): RelaunchStatus {
        return when (item.type) {
            "Text" -> if (item.extraData != null) RelaunchStatus.Ready else RelaunchStatus.MissingData
            "Photo", "Batch", "Video" -> {
                if (item.imagePaths.isEmpty()) return RelaunchStatus.MissingFiles(0)
                val missing = item.imagePaths.count { !File(it).exists() }
                if (missing == 0) RelaunchStatus.Ready else RelaunchStatus.MissingFiles(missing)
            }
            else -> RelaunchStatus.MissingData
        }
    }
}
