package com.nirhib.kidsreminders.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** הודעה + שם הילד/ה שהקבוצה שלה שייכת לו/ה. */
data class KidMessage(val kid: String, val message: IncomingMessage)

/**
 * חילוץ וסיכום בעזרת Claude (דרך ה-API של Anthropic, צריך מפתח API).
 * מבין הקשר הרבה יותר טוב מהחוקים: "תזכורת למה שכתבתי אתמול", "בשבוע הבא אחרי החג" וכו'.
 */
class ClaudeExtractor(
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
    client: AnthropicClient? = null,
) {
    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private val mapper = ObjectMapper()

        private val SYSTEM_PROMPT = """
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

        private val SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("summary", "items"),
            "properties" to mapOf(
                "summary" to mapOf("type" to "string"),
                "items" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "additionalProperties" to false,
                        "required" to listOf("kid", "type", "title", "date", "time", "details"),
                        "properties" to mapOf(
                            "kid" to mapOf("type" to "string"),
                            "type" to mapOf(
                                "type" to "string",
                                "enum" to ItemType.entries.map { it.name },
                            ),
                            "title" to mapOf("type" to "string"),
                            "date" to mapOf("type" to "string"),
                            "time" to mapOf("type" to "string"),
                            "details" to mapOf("type" to "string"),
                        ),
                    ),
                ),
            ),
        )

        private val stampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** בונה את הטקסט שנשלח ל-Claude (נפרד כדי שאפשר יהיה לבדוק אותו). */
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

        /** מפענח את ה-JSON שחוזר מ-Claude. */
        fun parseResponse(json: String, kids: List<String>): ExtractionResult {
            val root: JsonNode = mapper.readTree(json)
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

    private val client: AnthropicClient =
        client ?: AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    /** קריאה סינכרונית – יש להריץ ברקע (לא על ה-UI thread). */
    fun extract(messages: List<KidMessage>, kids: List<String>, today: LocalDate): ExtractionResult {
        if (messages.isEmpty()) return ExtractionResult(emptyList())

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(SYSTEM_PROMPT)
            .addUserMessage(buildUserPrompt(messages, kids, today))
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.LOW)
                    .format(
                        BetaJsonOutputFormat.builder()
                            .schema(
                                BetaJsonOutputFormat.Schema.builder()
                                    .apply { SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            // אם המודל מסרב (סיווג בטיחות שגוי), הבקשה עוברת אוטומטית למודל חלופי
            .addBeta(FALLBACK_BETA)
            .fallbacksDefault()
            .build()

        val response = client.beta().messages().create(params)
        if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
            throw IllegalStateException("Claude declined to process this batch")
        }
        val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        return parseResponse(text, kids)
    }
}
