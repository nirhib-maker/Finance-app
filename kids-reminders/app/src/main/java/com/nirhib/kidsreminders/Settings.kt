package com.nirhib.kidsreminders

import android.content.Context
import com.nirhib.kidsreminders.core.Assistant
import com.nirhib.kidsreminders.core.ItemExtractor
import com.nirhib.kidsreminders.core.LlmClient
import com.nirhib.kidsreminders.core.LlmExtractor
import com.nirhib.kidsreminders.core.LlmProvider
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

    /** להוסיף ליומן בלי לשאול. ברירת מחדל: לא – הכול מחכה לאישור בסיכום הערב. */
    var autoAddToCalendar: Boolean
        get() = prefs.getBoolean("autoAddCal", false)
        set(v) = prefs.edit().putBoolean("autoAddCal", v).apply()

    /** התראה מיידית על כל פריט חדש. ברירת מחדל: לא – רק הודעת ערב אחת. */
    var notifyEachItem: Boolean
        get() = prefs.getBoolean("notifyEach", false)
        set(v) = prefs.edit().putBoolean("notifyEach", v).apply()

    /** שעת סיכום הערב (0-23). */
    var digestHour: Int
        get() = prefs.getInt("digestHour", 20)
        set(v) = prefs.edit().putInt("digestHour", v).apply()

    var aiEnabled: Boolean
        get() = prefs.getBoolean("aiEnabled", false)
        set(v) = prefs.edit().putBoolean("aiEnabled", v).apply()

    /** איזה מודל שפה לשמש: Claude או Gemini. */
    var provider: LlmProvider
        get() = LlmProvider.parse(prefs.getString("provider", null))
        set(v) = prefs.edit().putString("provider", v.name).apply()

    // מפתח ומודל נשמרים לכל ספק בנפרד (המפתחות הישנים "apiKey"/"model" שייכים ל-Claude)
    private fun keyPref(p: LlmProvider) = if (p == LlmProvider.CLAUDE) "apiKey" else "apiKey_${p.name}"
    private fun modelPref(p: LlmProvider) = if (p == LlmProvider.CLAUDE) "model" else "model_${p.name}"

    fun apiKeyFor(p: LlmProvider): String = prefs.getString(keyPref(p), "") ?: ""
    fun setApiKeyFor(p: LlmProvider, v: String) = prefs.edit().putString(keyPref(p), v.trim()).apply()

    fun modelFor(p: LlmProvider): String =
        prefs.getString(modelPref(p), null)?.takeIf { it.isNotBlank() } ?: p.defaultModel
    fun setModelFor(p: LlmProvider, v: String) = prefs.edit().putString(modelPref(p), v.trim()).apply()

    val apiKey: String get() = apiKeyFor(provider)
    val model: String get() = modelFor(provider)

    fun client(): LlmClient = provider.client(apiKey, model)
    fun extractor(): LlmExtractor = ItemExtractor(client())
    fun assistant(): Assistant = Assistant(client())

    val useAi: Boolean get() = aiEnabled && apiKey.isNotBlank()
}
