package com.nirhib.kidsreminders

import android.content.Context
import android.util.Log
import com.nirhib.kidsreminders.core.AssistantItem
import com.nirhib.kidsreminders.core.AssistantOp
import com.nirhib.kidsreminders.core.AssistantPrompt
import com.nirhib.kidsreminders.core.ChatTurn
import com.nirhib.kidsreminders.core.ExtractedItem
import com.nirhib.kidsreminders.core.ItemState
import com.nirhib.kidsreminders.core.ItemType
import com.nirhib.kidsreminders.core.KidMessage
import com.nirhib.kidsreminders.core.RuleExtractor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** הלוגיקה המרכזית: הודעה נכנסת -> פריטים -> (אישור) -> יומן. */
object Actions {

    /** נקרא עבור כל הודעה חדשה מקבוצה שסומנה. */
    fun onIncoming(context: Context, kid: String, group: String, sender: String, text: String, time: Long) {
        val store = Store.get(context)
        val settings = Settings(context)
        val msg = store.addMessage(kid, group, sender, text, time) ?: return

        if (settings.useAi) {
            // מחכים כמה דקות כדי לאסוף כמה הודעות יחד לבקשה אחת למודל השפה
            Work.scheduleAi(context)
        } else {
            processWithRules(context, listOf(msg))
        }
    }

    /**
     * מנתח את כל ההודעות שעוד לא נותחו: עם בינה מלאכותית אם מופעלת, אחרת (או אם נכשלה) לפי מילות מפתח.
     * מחזיר false אם הבינה המלאכותית נכשלה וכדאי לנסות שוב מאוחר יותר.
     */
    fun processPending(context: Context, allowRulesFallback: Boolean): Boolean {
        val store = Store.get(context)
        val settings = Settings(context)
        val pending = store.unprocessedMessages()
        if (pending.isEmpty()) return true
        if (!settings.useAi) {
            processWithRules(context, pending)
            return true
        }
        return try {
            val result = settings.extractor().extract(
                pending.map { KidMessage(it.kid, it.toIncoming()) }, settings.kids, LocalDate.now(),
            )
            handleItems(context, result.items, settings.provider.name.lowercase())
            result.summary?.let { store.addSummary(it) }
            store.markProcessed(pending.map { it.id })
            true
        } catch (e: Exception) {
            Log.w("Actions", "AI extraction failed", e)
            if (allowRulesFallback) processWithRules(context, pending)
            allowRulesFallback
        }
    }

    fun processWithRules(context: Context, messages: List<StoredMessage>) {
        val store = Store.get(context)
        val items = messages.flatMap { RuleExtractor.extract(it.toIncoming(), it.kid) }
        handleItems(context, items, "rules")
        store.markProcessed(messages.map { it.id })
    }

    /** פריטים חדשים: נשמרים ומחכים לאישור (אלא אם בחרת "להוסיף בלי לשאול"). */
    fun handleItems(context: Context, items: List<ExtractedItem>, source: String) {
        val store = Store.get(context)
        val settings = Settings(context)
        for (item in items) {
            val stored = store.addItem(item, source) ?: continue
            val calendarWorthy = item.date != null && item.type != ItemType.INFO
            var added = false
            if (calendarWorthy && settings.autoAddToCalendar && CalendarHelper.hasPermission(context)) {
                added = addToCalendar(context, stored.id)
            }
            if (settings.notifyEachItem) Notifier.notifyItem(context, store.item(stored.id)!!, added)
        }
    }

    fun addToCalendar(context: Context, id: String): Boolean {
        val store = Store.get(context)
        val s = store.item(id) ?: return false
        if (s.eventId >= 0) return true
        val eventId = CalendarHelper.insert(context, Settings(context), s.item)
        if (eventId < 0) return false
        store.update(id) { it.status = ItemStatus.ADDED; it.eventId = eventId }
        return true
    }

    /** מוחק מהיומן (אם נוסף) ומסמן כלא רלוונטי. */
    fun dismiss(context: Context, id: String) {
        val store = Store.get(context)
        val s = store.item(id) ?: return
        CalendarHelper.delete(context, s.eventId)
        store.update(id) { it.status = ItemStatus.DISMISSED; it.eventId = -1 }
    }

    /** מעדכן פריט; אם הוא ביומן – מחליף את האירוע. */
    fun updateItem(context: Context, id: String, newItem: ExtractedItem): Boolean {
        val store = Store.get(context)
        val s = store.item(id) ?: return false
        store.update(id) { it.item = newItem }
        if (s.eventId >= 0) {
            CalendarHelper.delete(context, s.eventId)
            store.update(id) { it.eventId = -1; it.status = ItemStatus.PENDING }
            return addToCalendar(context, id)
        }
        return true
    }

    /** "הוסף הכל": כל הפריטים שמחכים לאישור ויש להם תאריך. מחזיר כמה נוספו. */
    fun addAllPending(context: Context): Int =
        Store.get(context).activeItems()
            .filter { it.status == ItemStatus.PENDING && it.item.date != null && it.item.type != ItemType.INFO }
            .count { addToCalendar(context, it.id) }

    fun assistantItems(context: Context): List<AssistantItem> =
        Store.get(context).activeItems().map {
            AssistantItem(it.num, it.item, if (it.status == ItemStatus.ADDED) ItemState.IN_CALENDAR else ItemState.PENDING)
        }

    fun startOfToday(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ------------------------------------------------------------------ שיחה עם העוזר

    /** שולח הודעה של ההורה לעוזר, מבצע את הפעולות ומחזיר את התשובה (כולל מה שבוצע בפועל). */
    data class ChatResult(val text: String, val failed: Boolean)

    fun chat(context: Context, userText: String, alreadyRecorded: Boolean = false): ChatResult {
        val store = Store.get(context)
        val settings = Settings(context)
        // alreadyRecorded: המסך כבר שמר את ההודעה של ההורה כדי להציג אותה מיד
        val past = store.chat().let { if (alreadyRecorded) it.dropLast(1) else it }
        // הודעות שגיאה (וההודעה שנכשלה לפניהן) לא נשלחות למודל כהיסטוריה
        val clean = past.filterIndexed { i, e -> e.retryText == null && past.getOrNull(i + 1)?.retryText == null }
        val history = clean.map {
            ChatTurn(it.fromUser, it.text, LocalDateTime.ofInstant(Instant.ofEpochMilli(it.time), ZoneId.systemDefault()))
        }
        if (!alreadyRecorded) store.addChat(true, userText)

        var failed = false
        val answer = if (!settings.useAi) {
            simpleCommand(context, userText)
        } else try {
            processPending(context, allowRulesFallback = false)
            val reply = settings.assistant().respond(
                userText, assistantItems(context), history,
                store.summariesSince(startOfToday() - 86_400_000L), settings.kids, LocalDateTime.now(),
            )
            val results = reply.actions.mapNotNull { execute(context, it) }
            listOf(reply.reply, results.joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n\n")
                .ifBlank { "לא הבנתי, אפשר לנסח שוב?" }
        } catch (e: Exception) {
            Log.w("Actions", "assistant failed", e)
            failed = true
            "✗ לא הצלחתי להגיע ל-${settings.provider.label} (ניסיתי כמה פעמים): ${e.message?.take(150)}"
        }
        store.addChat(false, answer, retryText = if (failed) userText else null)
        return ChatResult(answer, failed)
    }

    /** בלי בינה מלאכותית – מבינים רק "הוסף הכל" ו"הוסף 1,3". */
    private fun simpleCommand(context: Context, text: String): String {
        val t = text.trim()
        if (t.contains("הכל") || t.contains("הכול") || t == "כן") {
            val n = addAllPending(context)
            return "✓ נוספו ליומן $n פריטים."
        }
        val nums = Regex("\\d+").findAll(t).map { it.value.toInt() }.toList()
        if (t.startsWith("הוסף") && nums.isNotEmpty()) {
            return nums.mapNotNull { execute(context, com.nirhib.kidsreminders.core.AssistantAction(AssistantOp.ADD, it, null, null, null, null, null, null)) }
                .joinToString("\n")
        }
        return "כדי לנהל שיחה חופשית צריך להפעיל בינה מלאכותית בהגדרות. בינתיים אפשר לכתוב \"הוסף הכל\" או \"הוסף 1,3\"."
    }

    private fun execute(context: Context, a: com.nirhib.kidsreminders.core.AssistantAction): String? {
        val store = Store.get(context)
        val settings = Settings(context)
        fun label(s: StoredItem) = "#${s.num} ${s.item.kid}: ${s.item.title}"

        when (a.op) {
            AssistantOp.ADD -> {
                val s = store.itemByNum(a.num) ?: return "✗ לא מצאתי את #${a.num}"
                if (s.item.date == null) return "✗ ל-${label(s)} אין תאריך – לאיזה יום להוסיף?"
                if (!CalendarHelper.hasPermission(context)) return "✗ אין הרשאה ליומן (הגדרות ← גישה ליומן)"
                return if (addToCalendar(context, s.id)) "✓ נוסף ליומן: ${label(s)} (${Notifier.whenText(store.item(s.id)!!)})"
                else "✗ לא הצלחתי להוסיף את ${label(s)}"
            }
            AssistantOp.REMOVE -> {
                val s = store.itemByNum(a.num) ?: return "✗ לא מצאתי את #${a.num}"
                val wasInCalendar = s.eventId >= 0
                dismiss(context, s.id)
                return "🗑 הוסר${if (wasInCalendar) " (גם מהיומן)" else ""}: ${label(s)}"
            }
            AssistantOp.UPDATE -> {
                val s = store.itemByNum(a.num) ?: return "✗ לא מצאתי את #${a.num}"
                val old = s.item
                val updated = old.copy(
                    kid = a.kid ?: old.kid,
                    type = a.type ?: old.type,
                    title = a.title ?: old.title,
                    date = a.date ?: old.date,
                    time = a.time ?: old.time,
                    details = a.details ?: old.details,
                )
                if (updated == old) return null
                updateItem(context, s.id, updated)
                val now = store.item(s.id)!!
                return "✏️ עודכן: ${label(now)} (${Notifier.whenText(now)})" +
                    if (now.status == ItemStatus.ADDED) " – גם ביומן" else ""
            }
            AssistantOp.CREATE, AssistantOp.CREATE_AND_ADD -> {
                val title = a.title ?: return "✗ חסר תיאור לפריט החדש"
                val kid = a.kid ?: settings.kids.singleOrNull() ?: return "✗ לא ברור לאיזו ילדה זה שייך"
                val item = ExtractedItem(kid, a.type ?: ItemType.INFO, title, a.date, a.time, a.details ?: "")
                val s = store.addItem(item, "chat") ?: return "כבר קיים פריט כזה."
                if (a.op == AssistantOp.CREATE_AND_ADD && item.date != null && addToCalendar(context, s.id)) {
                    return "✓ נוצר ונוסף ליומן: ${label(s)} (${Notifier.whenText(store.item(s.id)!!)})"
                }
                return "➕ נוצר: ${label(s)}" + if (item.date == null) " (בלי תאריך)" else ""
            }
        }
    }

    /** הודעת הערב (סיכום + שאלה מה להוסיף). null אם אין מה לספר. */
    fun eveningDigest(context: Context): String? {
        val settings = Settings(context)
        val store = Store.get(context)
        processPending(context, allowRulesFallback = true)
        val items = assistantItems(context)
        val summaries = store.summariesSince(startOfToday())
        if (settings.useAi && (items.isNotEmpty() || summaries.isNotEmpty())) {
            try {
                val text = settings.assistant().eveningDigest(items, summaries, settings.kids, LocalDateTime.now())
                return text.ifBlank { null }
            } catch (e: Exception) {
                Log.w("Actions", "AI digest failed, using template", e)
            }
        }
        return AssistantPrompt.templateDigest(items, LocalDate.now())
    }
}
