package com.nirhib.kidsreminders.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** הודעה שהתקבלה מקבוצת וואטסאפ (מתוך נוטיפיקציה). */
data class IncomingMessage(
    val group: String,
    val sender: String,
    val text: String,
    val receivedAt: LocalDateTime,
)

enum class ItemType(val emoji: String, val hebrew: String) {
    EXAM("📝", "מבחן"),
    BRING("🎒", "להביא"),
    EVENT("🎉", "אירוע"),
    CHANGE("⚠️", "שינוי"),
    PAYMENT("💰", "תשלום"),
    FORM("✍️", "טופס/אישור"),
    INFO("ℹ️", "מידע");

    companion object {
        fun parse(value: String?): ItemType =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: INFO
    }
}

/** פריט חשוב שחולץ מהודעה: מבחן, דבר שצריך להביא, אירוע וכו'. */
data class ExtractedItem(
    val kid: String,
    val type: ItemType,
    val title: String,
    val date: LocalDate?,
    val time: LocalTime?,
    val details: String,
) {
    /** מפתח לזיהוי כפילויות (אותו פריט שהגיע פעמיים / מכמה הודעות). */
    fun dedupKey(): String =
        listOf(kid, type.name, date?.toString() ?: "-", normalize(title)).joinToString("|")

    fun calendarTitle(): String = "${type.emoji} $kid: $title"

    companion object {
        fun normalize(s: String): String =
            s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}

/** תוצאת ניתוח של קבוצת הודעות. */
data class ExtractionResult(
    val items: List<ExtractedItem>,
    /** סיכום קצר בעברית (קיים רק כשמשתמשים ב-Claude). */
    val summary: String? = null,
)
