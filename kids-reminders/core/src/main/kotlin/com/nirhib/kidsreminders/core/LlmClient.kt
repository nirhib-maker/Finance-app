package com.nirhib.kidsreminders.core

/**
 * לקוח מודל שפה: שולח הנחיית מערכת + טקסט, ומקבל בחזרה JSON שתואם לסכמה.
 * הסכמה נכתבת פעם אחת ב-JSON Schema רגיל; כל ספק ממיר אותה לפורמט שלו.
 */
interface LlmClient {
    /** קריאה סינכרונית – יש להריץ ברקע (לא על ה-UI thread). מחזיר את מחרוזת ה-JSON. */
    fun json(system: String, user: String, schema: Map<String, Any>): String
}

enum class LlmProvider(val label: String, val defaultModel: String, val keyHint: String, val keyUrl: String) {
    CLAUDE("Claude (Anthropic)", "claude-opus-5-5", "sk-ant-…", "console.anthropic.com"),
    GEMINI("Gemini (Google)", "gemini-3.6-flash", "AIza…", "aistudio.google.com");

    fun client(apiKey: String, model: String): LlmClient = when (this) {
        CLAUDE -> ClaudeClient(apiKey, model)
        GEMINI -> GeminiClient(apiKey, model)
    }

    companion object {
        fun parse(v: String?): LlmProvider = entries.firstOrNull { it.name == v } ?: CLAUDE
    }
}

/** עזרים לבניית JSON Schema קריא. כל השדות חובה; ערך "ריק" מסומן במחרוזת ריקה / 0. */
object Schema {
    fun obj(vararg fields: Pair<String, Map<String, Any>>): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to fields.map { it.first },
        "properties" to linkedMapOf(*fields),
    )

    fun str(): Map<String, Any> = mapOf("type" to "string")
    fun int(): Map<String, Any> = mapOf("type" to "integer")
    fun enum(values: List<String>): Map<String, Any> = mapOf("type" to "string", "enum" to values)
    fun array(items: Map<String, Any>): Map<String, Any> = mapOf("type" to "array", "items" to items)
}
