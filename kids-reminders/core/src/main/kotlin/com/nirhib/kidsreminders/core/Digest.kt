package com.nirhib.kidsreminders.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Hebrew {
    private val days = mapOf(
        DayOfWeek.SUNDAY to "ראשון", DayOfWeek.MONDAY to "שני", DayOfWeek.TUESDAY to "שלישי",
        DayOfWeek.WEDNESDAY to "רביעי", DayOfWeek.THURSDAY to "חמישי", DayOfWeek.FRIDAY to "שישי",
        DayOfWeek.SATURDAY to "שבת",
    )
    private val dm = DateTimeFormatter.ofPattern("d/M")

    fun dayName(d: LocalDate): String = days.getValue(d.dayOfWeek)

    /** "היום", "מחר", או "יום ג' 12/11" */
    fun relativeDay(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "היום"
        today.plusDays(1) -> "מחר"
        else -> "יום ${dayName(d)} ${d.format(dm)}"
    }
}

/** בונה את סיכום הערב: מה צריך מחר + מה מתקרב השבוע. */
object DigestBuilder {

    fun build(items: List<ExtractedItem>, today: LocalDate, horizonDays: Long = 7): String? {
        val tomorrow = today.plusDays(1)
        val tomorrowItems = items.filter { it.date == tomorrow }
        val upcoming = items.filter { it.date != null && it.date.isAfter(tomorrow) && !it.date.isAfter(today.plusDays(horizonDays)) }
            .sortedBy { it.date }
        if (tomorrowItems.isEmpty() && upcoming.isEmpty()) return null

        return buildString {
            if (tomorrowItems.isNotEmpty()) {
                append("מחר (יום ").append(Hebrew.dayName(tomorrow)).append("):\n")
                tomorrowItems.groupBy { it.kid }.forEach { (kid, list) ->
                    list.forEach { append("• ").append(it.type.emoji).append(' ').append(kid).append(": ").append(it.title).append('\n') }
                }
            }
            if (upcoming.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("בהמשך השבוע:\n")
                upcoming.forEach {
                    append("• ").append(Hebrew.relativeDay(it.date!!, today)).append(" – ")
                        .append(it.type.emoji).append(' ').append(it.kid).append(": ").append(it.title).append('\n')
                }
            }
        }.trimEnd()
    }
}

/** כלים לפענוח כותרות של נוטיפיקציות וואטסאפ. */
object WhatsAppTitle {
    private val countSuffix = Regex("\\s*\\((\\d+)\\s*\\S*\\s*\\S*\\)\\s*$")

    /**
     * "גן חבצלת (3 הודעות)" -> "גן חבצלת"
     * "Sender @ Group" (גרסאות ישנות) -> "Group"
     */
    fun normalizeGroup(title: String): String {
        var t = title.trim()
        t = countSuffix.replace(t, "")
        val at = t.lastIndexOf(" @ ")
        if (at >= 0) t = t.substring(at + 3)
        return t.trim()
    }

    /** "Group: Sender" -> Pair(Group, Sender), אם הכותרת היא בפורמט הזה. */
    fun splitGroupSender(title: String, knownGroups: Collection<String>): Pair<String, String>? {
        val t = normalizeGroup(title)
        for (g in knownGroups.sortedByDescending { it.length }) {
            if (t.startsWith("$g: ")) return g to t.removePrefix("$g: ").trim()
        }
        return null
    }

    private val ignoredTexts = listOf(
        "הודעה זו נמחקה", "This message was deleted", "Checking for new messages",
        "בודק אם יש הודעות חדשות", "new messages", "הודעות חדשות",
    )

    fun isNoise(text: String): Boolean {
        val t = text.trim()
        return t.isEmpty() || ignoredTexts.any { t.equals(it, true) || (t.length < 40 && t.endsWith(it, true)) }
    }
}
