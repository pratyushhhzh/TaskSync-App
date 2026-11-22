package com.example.tasksync.ui.tasks

import android.content.Context
import org.json.JSONArray

/** Stores only the home-screen display order; task data remains in Room unchanged. */
internal object HomeTaskOrderStore {
    private const val PREFERENCES = "tasksync_home_task_order"
    private const val ORDER_KEY = "task_ids"

    fun load(context: Context): List<String> {
        val encoded = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ORDER_KEY, null) ?: return emptyList()
        return runCatching {
            val values = JSONArray(encoded)
            List(values.length()) { index -> values.getString(index) }.distinct()
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, taskIds: List<String>) {
        val encoded = JSONArray(taskIds.distinct()).toString()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ORDER_KEY, encoded)
            .apply()
    }
}
