package com.nirhib.kidsreminders.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** הודעה + שם הילד/ה שהקבוצה שלה שייכת לו/ה. */
data class KidMessage(val kid: String, val message: IncomingMessage)

/** ספק מודל שפה (Claude / Gemini) שמחלץ פריטים ממקבץ הודעות. */
interface LlmExtractor {
    /** קריאה סינכרונית – יש להריץ ברקע (לא על ה-UI thread). */
    fun extract(messages: List<KidMessage>, kids: List<String>, today: LocalDate): ExtractionResult
}

/** חילוץ פריטים ממקבץ הודעות בעזרת מודל שפה (Claude / Gemini). */
class ItemExtractor(private val client: LlmClient) : LlmExtractor {
    override fun extract(messages: List<KidMessage>, kids: List<String>, today: LocalDate): ExtractionResult {
        if (messages.isEmpty()) return ExtractionResult(emptyList())
        val json = client.json(
            ExtractionPrompt.SYSTEM_PROMPT,
            ExtractionPrompt.buildUserPrompt(messages, kids, today),
            ExtractionPrompt.SCHEMA,
        )
        return ExtractionPrompt.parseResponse(json, kids)
    }
}

/** ההנחיות, הסכמה והפענוח – משותפים לכל הספקים. */
object ExtractionPrompt {
    private val mapper = ObjectMapper()

    val SYSTEM_PROMPT = """
        You help a busy parent in Israel keep track of what matters in WhatsApp groups of their
        children's kindergarten and schools. Messages are mostly in Hebrew and come from teachers,
        kindergarten staff and other parents.

        Extract only things the parent must know or act on:
        - EXAM: tests, quizzes, assignments due
        - BRING: things the child must bring / wear on a specific day (costume, white shirt, money, food...)
        - EVENT: trips, parties, ceremonies, parent meetings, performances, special activity days
        - CHANGE: no school, short day, early pickup, closures, strikes, schedule changes
        - PAYMENT: money to pay or transfer
        - FORM: forms or approvals to sign / return
        - INFO: other genuinely important info that has no better type

        Rules:
        - Ignore chit-chat, thanks, birthday wishes, emojis-only messages, lost-and-found and
          questions from other parents unless a teacher's answer in the batch confirms the fact.
        - Resolve relative dates ("מחר", "ביום ג'", "בשבוע הבא") against the timestamp of the message
          that mentions them. Output dates as YYYY-MM-DD and times as HH:mm, or "" when unknown.
        - Merge duplicates (the same thing repeated or reminded) into one item.
        - "title" is a short Hebrew phrase for a calendar entry, without the child's name
          (e.g. "מבחן בחשבון – פרקים 3-4", "להביא תחפושת", "טיול שנתי – להגיע ב-7:30").
        - "details" is one or two Hebrew sentences with everything useful (what to bring, times, amounts).
        - "kid" must be exactly one of the child names given.
        - "summary" is a short Hebrew summary of the batch, organized by child, for a busy parent.
          Empty string if nothing important.
    """.trimIndent()

    val TYPE_VALUES = ItemType.entries.map { it.name }

    val SCHEMA: Map<String, Any> = Schema.obj(
        "summary" to Schema.str(),
        "items" to Schema.array(
            Schema.obj(
                "kid" to Schema.str(),
                "type" to Schema.enum(TYPE_VALUES),
                "title" to Schema.str(),
                "date" to Schema.str(),
                "time" to Schema.str(),
                "details" to Schema.str(),
            )
        ),
    )

    private val stampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun buildUserPrompt(messages: List<KidMessage>, kids: List<String>, today: LocalDate): String =
        buildString {
            append("Today is ").append(today).append(" (")
            append(today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)).append(").\n")
            append("Children: ").append(kids.joinToString(", ")).append("\n\n")
            append("Messages:\n")
            for (km in messages) {
                val m = km.message
                append("---\n")
                append("[child: ").append(km.kid).append("] [group: ").append(m.group).append("] ")
                append("[").append(m.receivedAt.format(stampFormat)).append(" ")
                append(m.receivedAt.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)).append("] ")
                append(m.sender).append(":\n").append(m.text.trim()).append("\n")
            }
        }

    /** מפענח את ה-JSON שחוזר מהמודל ({"summary": ..., "items": [...]}). */
    fun parseResponse(json: String, kids: List<String>): ExtractionResult {
        val cleaned = json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root: JsonNode = mapper.readTree(cleaned)
        val items = root.path("items").mapNotNull { n ->
            val kid = n.path("kid").asText().trim()
            val title = n.path("title").asText().trim()
            if (title.isEmpty()) return@mapNotNull null
            ExtractedItem(
                kid = kids.firstOrNull { it == kid } ?: kids.firstOrNull { kid.contains(it) } ?: kid,
                type = ItemType.parse(n.path("type").asText()),
                title = title,
                date = n.path("date").asText().takeIf { it.isNotBlank() }
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                time = n.path("time").asText().takeIf { it.isNotBlank() }
                    ?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
                details = n.path("details").asText().trim(),
            )
        }
        return ExtractionResult(items, root.path("summary").asText().trim().ifEmpty { null })
    }
}
