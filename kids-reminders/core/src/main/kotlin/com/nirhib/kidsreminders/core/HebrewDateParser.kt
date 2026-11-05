package com.nirhib.kidsreminders.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class ParsedWhen(val date: LocalDate?, val time: LocalTime?)

/**
 * מזהה תאריכים ושעות בטקסט עברי, ביחס לזמן קבלת ההודעה.
 * תומך ב: היום / הערב / מחר / מחרתיים, "ביום שלישי" / "יום ג'", "בשבוע הבא",
 * 12/11, 12.11.26, "ה-12 בנובמבר", "בשעה 8:30", "ב-17:00", "בשעה 8".
 */
object HebrewDateParser {

    private const val NOT_LETTER_BEFORE = "(?<![\\p{L}])"
    private const val NOT_LETTER_AFTER = "(?![\\p{L}])"

    private val relative = Regex(
        NOT_LETTER_BEFORE + "[ולב]?(מחרתיים|מחר|היום|הערב|הבוקר)" + NOT_LETTER_AFTER
    )

    private val weekdayNames = mapOf(
        "ראשון" to DayOfWeek.SUNDAY, "א" to DayOfWeek.SUNDAY,
        "שני" to DayOfWeek.MONDAY, "ב" to DayOfWeek.MONDAY,
        "שלישי" to DayOfWeek.TUESDAY, "ג" to DayOfWeek.TUESDAY,
        "רביעי" to DayOfWeek.WEDNESDAY, "ד" to DayOfWeek.WEDNESDAY,
        "חמישי" to DayOfWeek.THURSDAY, "ה" to DayOfWeek.THURSDAY,
        "שישי" to DayOfWeek.FRIDAY, "ו" to DayOfWeek.FRIDAY,
        "שבת" to DayOfWeek.SATURDAY,
    )

    // "יום שלישי", "ביום ג'", "יום ה׳"
    private val weekday = Regex(
        "יום\\s+(ראשון|שני|שלישי|רביעי|חמישי|שישי|שבת|[אבגדהו](?=['׳\"״]|\\s|$|[,.!?]))" + NOT_LETTER_AFTER
    )

    private val numericDate = Regex(
        "(?<![\\d:./])(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{4}|\\d{2}))?(?![\\d:]|[./]\\d)"
    )

    private val months = linkedMapOf(
        "ינואר" to 1, "פברואר" to 2, "מרץ" to 3, "מרס" to 3, "אפריל" to 4, "מאי" to 5,
        "יוני" to 6, "יולי" to 7, "אוגוסט" to 8, "ספטמבר" to 9, "אוקטובר" to 10,
        "נובמבר" to 11, "דצמבר" to 12,
    )

    private val namedMonthDate = Regex(
        "(?<!\\d)(\\d{1,2})\\s*(?:ב|ל|-)?\\s*(" + months.keys.joinToString("|") + ")" + NOT_LETTER_AFTER
    )

    private val clockTime = Regex("(?<![\\d.])([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)")
    private val hourOnly = Regex("(?:בשעה|שעה)\\s*(\\d{1,2})(?![\\d:])")

    fun parse(text: String, reference: LocalDateTime): ParsedWhen =
        ParsedWhen(parseDate(text, reference.toLocalDate()), parseTime(text))

    fun parseDate(text: String, today: LocalDate): LocalDate? {
        // 1. תאריך מפורש (12/11, 12.11.26, ה-12 בנובמבר) – הכי אמין
        explicitDate(text, today)?.let { return it }

        // 2. יום בשבוע
        weekday.find(text)?.let { m ->
            val dow = weekdayNames[m.groupValues[1]] ?: return@let
            var date = nextOccurrence(today, dow)
            if (text.contains("שבוע הבא") && sameWeek(date, today)) date = date.plusWeeks(1)
            return date
        }

        // 3. מילים יחסיות
        relative.find(text)?.let { m ->
            return when (m.groupValues[1]) {
                "מחרתיים" -> today.plusDays(2)
                "מחר" -> today.plusDays(1)
                else -> today
            }
        }
        return null
    }

    fun parseTime(text: String): LocalTime? {
        clockTime.find(text)?.let { m ->
            return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        hourOnly.find(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h in 0..23) {
                // "בשעה 4" בהקשר של בית ספר/גן הוא כנראה אחה"צ
                val hour = if (h in 1..6) h + 12 else h
                return LocalTime.of(hour, 0)
            }
        }
        return null
    }

    private fun explicitDate(text: String, today: LocalDate): LocalDate? {
        for (m in numericDate.findAll(text)) {
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val yearText = m.groupValues[3]
            // "בשעה 8.30" – זו שעה ולא תאריך
            val before = text.substring(maxOf(0, m.range.first - 6), m.range.first)
            if (before.contains("שעה")) continue
            val year = when {
                yearText.isEmpty() -> null
                yearText.length == 2 -> 2000 + yearText.toInt()
                else -> yearText.toInt()
            }
            build(day, month, year, today)?.let { return it }
        }
        namedMonthDate.find(text)?.let { m ->
            val month = months[m.groupValues[2]] ?: return@let
            build(m.groupValues[1].toInt(), month, null, today)?.let { return it }
        }
        return null
    }

    private fun build(day: Int, month: Int, year: Int?, today: LocalDate): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null
        return try {
            if (year != null) {
                LocalDate.of(year, month, day)
            } else {
                val candidate = LocalDate.of(today.year, month, day)
                // תאריך שעבר לפני יותר מחודש – כנראה הכוונה לשנה הבאה (למשל הודעה בדצמבר על 5/1)
                if (candidate.isBefore(today.minusDays(30))) candidate.plusYears(1) else candidate
            }
        } catch (_: Exception) {
            null
        }
    }

    /** המופע הבא של יום בשבוע, אחרי היום (לא כולל היום). */
    private fun nextOccurrence(today: LocalDate, dow: DayOfWeek): LocalDate {
        var diff = dow.value - today.dayOfWeek.value
        if (diff <= 0) diff += 7
        return today.plusDays(diff.toLong())
    }

    /** שבוע ישראלי: ראשון עד שבת. */
    private fun sameWeek(a: LocalDate, b: LocalDate): Boolean =
        weekStart(a) == weekStart(b)

    private fun weekStart(d: LocalDate): LocalDate =
        d.minusDays((d.dayOfWeek.value % 7).toLong())
}
