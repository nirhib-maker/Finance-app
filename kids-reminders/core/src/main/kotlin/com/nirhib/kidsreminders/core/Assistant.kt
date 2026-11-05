package com.nirhib.kidsreminders.core

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

enum class ItemState { PENDING, IN_CALENDAR }

/** פריט כפי שהעוזר רואה אותו: מספר קצר וקבוע (#3) שהמשתמש יכול להתייחס אליו. */
data class AssistantItem(val num: Int, val item: ExtractedItem, val state: ItemState)

data class ChatTurn(val fromUser: Boolean, val text: String, val time: LocalDateTime)

enum class AssistantOp { ADD, REMOVE, UPDATE, CREATE, CREATE_AND_ADD }

/** פעולה שהעוזר מבקש לבצע. שדות ריקים = "לא לשנות". */
data class AssistantAction(
    val op: AssistantOp,
    val num: Int,
    val kid: String?,
    val type: ItemType?,
    val title: String?,
    val date: LocalDate?,
    val time: LocalTime?,
    val details: String?,
)

data class AssistantReply(val reply: String, val actions: List<AssistantAction>)

/**
 * העוזר: מבין מה ההורה כותב ("תוסיף את 1 ו-3", "תזיז את האסיפה ל-19:00", "בלי הטיול")
 * ומחזיר תשובה + רשימת פעולות שהאפליקציה מבצעת. עובד עם כל LlmClient.
 */
class Assistant(private val client: LlmClient) {

    fun respond(
        userText: String,
        items: List<AssistantItem>,
        history: List<ChatTurn>,
        summaries: List<String>,
        kids: List<String>,
        now: LocalDateTime,
    ): AssistantReply {
        val prompt = AssistantPrompt.state(items, summaries, kids, now) +
            AssistantPrompt.history(history) +
            "\nNew message from the parent:\n" + userText.trim()
        return AssistantPrompt.parseReply(client.json(AssistantPrompt.CHAT_SYSTEM, prompt, AssistantPrompt.CHAT_SCHEMA), kids)
    }

    /** הודעת הערב: סיכום היום + פריטים שמחכים לאישור. */
    fun eveningDigest(
        items: List<AssistantItem>,
        summaries: List<String>,
        kids: List<String>,
        now: LocalDateTime,
    ): String {
        val json = client.json(
            AssistantPrompt.DIGEST_SYSTEM,
            AssistantPrompt.state(items, summaries, kids, now),
            AssistantPrompt.DIGEST_SCHEMA,
        )
        return mapper.readTree(json).path("text").asText().trim()
    }

    private companion object {
        val mapper = ObjectMapper()
    }
}

object AssistantPrompt {
    private val mapper = ObjectMapper()
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    val CHAT_SYSTEM = """
        You are the assistant inside a family app that tracks the WhatsApp groups of the user's
        children's kindergarten and schools in Israel. The user is a parent. You chat in Hebrew:
        short, warm and practical (1-3 sentences). The app shows you the current list of items, each
        with a number (#N), and today's summaries of the groups.

        Each turn return JSON: "reply" (your Hebrew answer) and "actions" (what the app should do):
        - ADD: add item #num to the calendar.
        - REMOVE: remove item #num (deletes it from the calendar if it is there, and hides it).
        - UPDATE: change item #num. Fill only the fields that change, leave the others "".
          If the item is already in the calendar, the app updates the calendar event too.
        - CREATE: create a new item from the user's words (num = 0). Fill kid, type, title and, when
          known, date/time/details. CREATE_AND_ADD does the same and also adds it to the calendar.

        Rules:
        - Do only what the user asked. "כן"/"הוסף הכל"/"את כולם" means ADD for every PENDING item that has a date.
        - Items without a date cannot be added to the calendar: ask for the date instead.
        - If the request is ambiguous (which item? which child? what date?), ask a short question and return no actions.
        - Resolve relative dates against today's date. Dates YYYY-MM-DD, times HH:mm, "" when unknown.
        - "kid" must be one of the children's names. "type" is one of ${ItemType.entries.joinToString("/") { it.name }} or "".
        - Do not list the results of the actions in your reply: the app appends what actually happened.
          You may say what you are doing ("מוסיף את המבחן של רומי").
        - Questions about the groups ("מה יש מחר?", "מה הגננת כתבה?") are answered from the items and summaries.
    """.trimIndent()

    val DIGEST_SYSTEM = """
        You write the parent's single evening notification (sent at 20:00) for a family app that tracks the
        WhatsApp groups of the children's kindergarten and schools in Israel. Write in Hebrew, compact and
        scannable, suitable for a phone notification (at most ~900 characters). Use these parts, skipping empty ones:
        1. "היום בקבוצות:" a very short summary per child of what mattered today (from the summaries).
        2. "מחר:" what each child needs tomorrow (items dated tomorrow, both PENDING and IN_CALENDAR).
        3. "מחכים לאישור:" every PENDING item that has a date, one per line as "#N ילד/ה – כותרת (יום ותאריך)".
           Then one line asking which to add, e.g. "להוסיף ליומן? אפשר לענות: הכל / 1,3 / בלי 2".
        Do not invent anything that is not in the data. If there is nothing at all, return an empty text.
        Return JSON {"text": ...}.
    """.trimIndent()

    val CHAT_SCHEMA: Map<String, Any> = Schema.obj(
        "reply" to Schema.str(),
        "actions" to Schema.array(
            Schema.obj(
                "op" to Schema.enum(AssistantOp.entries.map { it.name }),
                "num" to Schema.int(),
                "kid" to Schema.str(),
                "type" to Schema.str(),
                "title" to Schema.str(),
                "date" to Schema.str(),
                "time" to Schema.str(),
                "details" to Schema.str(),
            )
        ),
    )

    val DIGEST_SCHEMA: Map<String, Any> = Schema.obj("text" to Schema.str())

    fun state(items: List<AssistantItem>, summaries: List<String>, kids: List<String>, now: LocalDateTime): String =
        buildString {
            val today = now.toLocalDate()
            append("Now: ").append(today).append(' ')
            append(today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)).append(' ')
            append(now.format(hm)).append('\n')
            append("Children: ").append(kids.joinToString(", ")).append("\n\n")
            append("Items (#num | child | type | date | time | status | title | details):\n")
            if (items.isEmpty()) append("(none)\n")
            for (a in items.sortedBy { it.num }) {
                val i = a.item
                append('#').append(a.num).append(" | ").append(i.kid).append(" | ").append(i.type.name).append(" | ")
                append(i.date?.let { "$it ${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}" } ?: "-")
                append(" | ").append(i.time?.toString() ?: "-").append(" | ").append(a.state.name)
                append(" | ").append(i.title).append(" | ").append(i.details.replace('\n', ' ').take(300)).append('\n')
            }
            append("\nToday's summaries from the groups:\n")
            if (summaries.isEmpty()) append("(none)\n")
            summaries.forEach { append("- ").append(it.replace('\n', ' ')).append('\n') }
        }

    fun history(turns: List<ChatTurn>): String {
        if (turns.isEmpty()) return ""
        return buildString {
            append("\nConversation so far (oldest first):\n")
            turns.takeLast(20).forEach {
                append(if (it.fromUser) "parent: " else "assistant: ").append(it.text.replace('\n', ' ').take(600)).append('\n')
            }
        }
    }

    fun parseReply(json: String, kids: List<String>): AssistantReply {
        val root = mapper.readTree(json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        fun s(n: com.fasterxml.jackson.databind.JsonNode, f: String) = n.path(f).asText("").trim().ifEmpty { null }
        val actions = root.path("actions").mapNotNull { n ->
            val op = runCatching { AssistantOp.valueOf(n.path("op").asText()) }.getOrNull() ?: return@mapNotNull null
            AssistantAction(
                op = op,
                num = n.path("num").asInt(0),
                kid = s(n, "kid")?.let { k -> kids.firstOrNull { it == k } ?: kids.firstOrNull { k.contains(it) } ?: k },
                type = s(n, "type")?.let { ItemType.parse(it) },
                title = s(n, "title"),
                date = s(n, "date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                time = s(n, "time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
                details = s(n, "details"),
            )
        }
        return AssistantReply(root.path("reply").asText("").trim(), actions)
    }

    /** הודעת ערב בלי בינה מלאכותית (תבנית קבועה). */
    fun templateDigest(items: List<AssistantItem>, today: LocalDate): String? {
        val tomorrow = today.plusDays(1)
        val forTomorrow = items.filter { it.item.date == tomorrow }
        val pending = items.filter { it.state == ItemState.PENDING && it.item.date != null }.sortedBy { it.item.date }
        if (forTomorrow.isEmpty() && pending.isEmpty()) return null
        return buildString {
            if (forTomorrow.isNotEmpty()) {
                append("מחר (יום ").append(Hebrew.dayName(tomorrow)).append("):\n")
                forTomorrow.forEach { append("• ").append(it.item.type.emoji).append(' ').append(it.item.kid).append(": ").append(it.item.title).append('\n') }
            }
            if (pending.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("מחכים לאישור:\n")
                pending.forEach {
                    append('#').append(it.num).append(' ').append(it.item.kid).append(" – ").append(it.item.title)
                    append(" (").append(Hebrew.relativeDay(it.item.date!!, today)).append(")\n")
                }
                append("להוסיף ליומן? אפשר ללחוץ \"הוסף הכל\" או לבחור באפליקציה.")
            }
        }.trimEnd()
    }
}
