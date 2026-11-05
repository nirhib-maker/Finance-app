package com.nirhib.kidsreminders

import android.app.Notification
import android.app.Person
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nirhib.kidsreminders.core.WhatsAppTitle
import java.util.concurrent.Executors

/**
 * מאזין לנוטיפיקציות של וואטסאפ. מעבד רק קבוצות שסומנו במסך ההגדרות;
 * מקבוצות אחרות נשמר רק השם (כדי שאפשר יהיה לבחור אותן).
 */
class WhatsAppListener : NotificationListenerService() {

    companion object {
        private val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val executor = Executors.newSingleThreadExecutor()
    }

    private data class Msg(val sender: String, val text: String, val time: Long)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in PACKAGES) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val postTime = sbn.postTime
        executor.execute { runCatching { handle(extras, postTime) } }
    }

    private fun handle(extras: Bundle, postTime: Long) {
        val settings = Settings(this)
        val store = Store.get(this)
        val groupKids = settings.groupKids

        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || conversationTitle != null

        val messages = readMessagingStyle(extras)
        val group: String
        val msgs: List<Msg>

        if (messages.isNotEmpty() && isGroup) {
            group = WhatsAppTitle.normalizeGroup(conversationTitle ?: title)
            msgs = messages
        } else {
            // פורמט ישן: הכותרת "קבוצה: שולח" או "שולח @ קבוצה", הטקסט הוא ההודעה עצמה
            val split = WhatsAppTitle.splitGroupSender(title, groupKids.keys)
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return
            if (split != null) {
                group = split.first
                msgs = listOf(Msg(split.second, text, postTime))
            } else if (title.contains(" @ ")) {
                group = WhatsAppTitle.normalizeGroup(title)
                msgs = listOf(Msg(title.substringBefore(" @ ").trim(), text, postTime))
            } else {
                return // צ'אט פרטי – לא מעניין אותנו
            }
        }

        if (group.isBlank()) return
        store.noteGroup(group)
        val kid = groupKids[group] ?: return

        for (m in msgs) {
            if (WhatsAppTitle.isNoise(m.text)) continue
            Actions.onIncoming(this, kid, group, m.sender, m.text, m.time)
        }
    }

    /** קורא את רשימת ההודעות מ-MessagingStyle (וואטסאפ שם שם את ההודעות האחרונות בצ'אט). */
    @Suppress("DEPRECATION")
    private fun readMessagingStyle(extras: Bundle): List<Msg> {
        val arr = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        return arr.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
            val person = b.getParcelable<Person>("sender_person")
            val sender = person?.name?.toString() ?: b.getCharSequence("sender")?.toString() ?: ""
            Msg(sender, text, b.getLong("time", System.currentTimeMillis()))
        }
    }
}
