package com.nirhib.kidsreminders

import android.content.Context
import com.nirhib.kidsreminders.core.ExtractedItem
import com.nirhib.kidsreminders.core.ItemType
import com.nirhib.kidsreminders.core.RuleExtractor

/** הלוגיקה המרכזית: הודעה נכנסת -> פריטים -> יומן + התראות. */
object Actions {

    /** נקרא עבור כל הודעה חדשה מקבוצה שסומנה. */
    fun onIncoming(context: Context, kid: String, group: String, sender: String, text: String, time: Long) {
        val store = Store.get(context)
        val settings = Settings(context)
        val msg = store.addMessage(kid, group, sender, text, time) ?: return

        if (settings.useAi) {
            // מחכים כמה דקות כדי לאסוף כמה הודעות יחד לבקשה אחת ל-Claude
            Work.scheduleAi(context)
        } else {
            processWithRules(context, listOf(msg))
        }
    }

    fun processWithRules(context: Context, messages: List<StoredMessage>) {
        val store = Store.get(context)
        val items = messages.flatMap { RuleExtractor.extract(it.toIncoming(), it.kid) }
        handleItems(context, items, "rules")
        store.markProcessed(messages.map { it.id })
    }

    fun handleItems(context: Context, items: List<ExtractedItem>, source: String) {
        val store = Store.get(context)
        val settings = Settings(context)
        for (item in items) {
            val stored = store.addItem(item, source) ?: continue
            val calendarWorthy = item.date != null && item.type != ItemType.INFO
            if (calendarWorthy && settings.autoAddToCalendar && CalendarHelper.hasPermission(context)) {
                val eventId = CalendarHelper.insert(context, settings, item)
                if (eventId >= 0) {
                    store.update(stored.id) { it.status = ItemStatus.ADDED; it.eventId = eventId }
                    Notifier.notifyItem(context, store.item(stored.id)!!, added = true)
                    continue
                }
            }
            Notifier.notifyItem(context, stored, added = false)
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
}
