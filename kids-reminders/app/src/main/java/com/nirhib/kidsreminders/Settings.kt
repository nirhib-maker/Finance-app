package com.nirhib.kidsreminders

import android.content.Context
import com.nirhib.kidsreminders.core.ClaudeExtractor
import org.json.JSONObject

/** הגדרות המשתמש (SharedPreferences פרטיים של האפליקציה). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var kids: List<String>
        get() = (prefs.getString("kids", null) ?: "מילי,רומי,לני")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("kids", v.joinToString(",")).apply()

    /** שם קבוצה -> שם הילדה. רק קבוצות שמופיעות כאן נקראות. */
    var groupKids: Map<String, String>
        get() {
            val json = JSONObject(prefs.getString("groupKids", "{}")!!)
            return json.keys().asSequence().associateWith { json.getString(it) }
        }
        set(v) = prefs.edit().putString("groupKids", JSONObject(v).toString()).apply()

    fun assignGroup(group: String, kid: String?) {
        groupKids = groupKids.toMutableMap().apply { if (kid == null) remove(group) else put(group, kid) }
    }

    var calendarId: Long
        get() = prefs.getLong("calendarId", -1L)
        set(v) = prefs.edit().putLong("calendarId", v).apply()

    var calendarName: String
        get() = prefs.getString("calendarName", "") ?: ""
        set(v) = prefs.edit().putString("calendarName", v).apply()

    /** להוסיף ליומן אוטומטית (עם אפשרות ביטול מההתראה), או לשאול קודם. */
    var autoAddToCalendar: Boolean
        get() = prefs.getBoolean("autoAdd", true)
        set(v) = prefs.edit().putBoolean("autoAdd", v).apply()

    /** שעת סיכום הערב (0-23). */
    var digestHour: Int
        get() = prefs.getInt("digestHour", 20)
        set(v) = prefs.edit().putInt("digestHour", v).apply()

    var aiEnabled: Boolean
        get() = prefs.getBoolean("aiEnabled", false)
        set(v) = prefs.edit().putBoolean("aiEnabled", v).apply()

    var apiKey: String
        get() = prefs.getString("apiKey", "") ?: ""
        set(v) = prefs.edit().putString("apiKey", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", null)?.takeIf { it.isNotBlank() } ?: ClaudeExtractor.DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    val useAi: Boolean get() = aiEnabled && apiKey.isNotBlank()
}
