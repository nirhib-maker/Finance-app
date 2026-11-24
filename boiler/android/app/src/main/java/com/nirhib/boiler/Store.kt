package com.nirhib.boiler

import android.content.Context
import com.fasterxml.jackson.module.kotlin.readValue
import com.nirhib.boiler.core.ScheduleSync
import com.nirhib.boiler.core.Settings

/** Everything the app remembers, in one SharedPreferences file. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("boiler", Context.MODE_PRIVATE)
    private val json = Settings.mapper

    data class SyncResult(
        val at: Long, val ok: Boolean, val message: String,
        val state: StateDto? = null, val skipped: List<ScheduleSync.Slot> = emptyList(),
    )
    data class StateDto(val on: Boolean, val watts: Int)

    /** Settings JSON exactly as the web UI saved it (the UI owns the format). */
    var settingsJson: String?
        get() = prefs.getString("settings", null)
        set(v) = prefs.edit().putString("settings", v).apply()

    fun settings(): Settings? = settingsJson?.let { runCatching { Settings.fromJson(it) }.getOrNull() }

    /** Schedules this app created in the switch (the only ones it may ever delete). */
    var programmed: List<ScheduleSync.Programmed>
        get() = prefs.getString("programmed", null)?.let { runCatching { json.readValue<List<ScheduleSync.Programmed>>(it) }.getOrNull() } ?: emptyList()
        set(v) = prefs.edit().putString("programmed", json.writeValueAsString(v)).apply()

    var lastSync: SyncResult?
        get() = prefs.getString("lastSync", null)?.let { runCatching { json.readValue<SyncResult>(it) }.getOrNull() }
        set(v) = prefs.edit().putString("lastSync", json.writeValueAsString(v)).apply()

    /** Heater power measured whenever a sync happens to catch the boiler heating (kW). */
    var measuredHeaterKw: Double?
        get() = prefs.getFloat("heaterKw", 0f).takeIf { it > 0 }?.toDouble()
        set(v) = prefs.edit().putFloat("heaterKw", (v ?: 0.0).toFloat()).apply()
}
