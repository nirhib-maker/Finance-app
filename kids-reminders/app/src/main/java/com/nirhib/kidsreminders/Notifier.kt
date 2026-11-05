package com.nirhib.kidsreminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.nirhib.kidsreminders.core.Hebrew
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Notifier {
    const val CH_ITEMS = "items"
    const val CH_DIGEST = "digest"
    const val CH_INFO = "info"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ITEMS, "פריטים חדשים מהקבוצות", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_DIGEST, "סיכום ערב", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "מידע ללא תאריך", NotificationManager.IMPORTANCE_LOW))
    }

    private fun canPost(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(context: Context, itemId: String, act: String, label: String): Notification.Action {
        val intent = Intent(context, ActionReceiver::class.java).setAction(act).putExtra("id", itemId)
        val pi = PendingIntent.getBroadcast(context, (itemId + act).hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null as android.graphics.drawable.Icon?, label, pi).build()
    }

    private val dm = DateTimeFormatter.ofPattern("d/M")

    fun whenText(s: StoredItem): String {
        val d = s.item.date ?: return "ללא תאריך"
        val day = Hebrew.relativeDay(d, LocalDate.now())
        val withDate = if (day == "היום" || day == "מחר") "$day (${d.format(dm)})" else day
        return withDate + (s.item.time?.let { " בשעה $it" } ?: "")
    }

    /** התראה על פריט חדש. added=true: כבר נוסף ליומן (עם כפתור ביטול). */
    fun notifyItem(context: Context, s: StoredItem, added: Boolean) {
        if (!canPost(context)) return
        val i = s.item
        val channel = if (i.date == null) CH_INFO else CH_ITEMS
        val title = (if (added) "נוסף ליומן: " else "") + i.calendarTitle()
        val b = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(whenText(s))
            .setStyle(Notification.BigTextStyle().bigText(whenText(s) + "\n" + i.details))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        if (added) {
            b.addAction(action(context, s.id, ActionReceiver.UNDO, "בטל"))
        } else if (i.date != null) {
            b.addAction(action(context, s.id, ActionReceiver.ADD, "הוסף ליומן"))
            b.addAction(action(context, s.id, ActionReceiver.DISMISS, "התעלם"))
        }
        context.getSystemService(NotificationManager::class.java).notify(s.id.hashCode(), b.build())
    }

    const val ASSISTANT_ID = 1
    const val KEY_REPLY = "reply"

    private fun openChat(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 1,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, "CHAT")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** הודעת הערב / תשובת העוזר – תמיד באותה התראה, עם כפתור "השב" שאפשר לכתוב בו. */
    fun notifyAssistant(context: Context, title: String, text: String, showAddAll: Boolean, showRetry: Boolean = false) {
        if (!canPost(context)) return
        val replyIntent = Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.REPLY)
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val replyPi = PendingIntent.getBroadcast(context, 10, replyIntent, mutable or PendingIntent.FLAG_UPDATE_CURRENT)
        val remoteInput = android.app.RemoteInput.Builder(KEY_REPLY)
            .setLabel("למשל: הוסף 1 ו-3, בלי הטיול")
            .build()
        val reply = Notification.Action.Builder(null as android.graphics.drawable.Icon?, "השב", replyPi)
            .addRemoteInput(remoteInput)
            .build()

        val b = Notification.Builder(context, CH_DIGEST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull { it.isNotBlank() } ?: text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openChat(context))
            .setOnlyAlertOnce(false)
            .addAction(reply)
        if (showRetry) {
            val retry = PendingIntent.getBroadcast(context, 12,
                Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.RETRY),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "🔁 שלח שוב", retry).build())
        }
        if (showAddAll) {
            val addAll = PendingIntent.getBroadcast(context, 11,
                Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.ADD_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "הוסף הכל", addAll).build())
        }
        context.getSystemService(NotificationManager::class.java).notify(ASSISTANT_ID, b.build())
    }

    /** מעדכן את ההתראה מיד אחרי שליחת תשובה (כדי שהמסך לא ייתקע על "שולח…"). */
    fun notifyWorking(context: Context, userText: String) {
        if (!canPost(context)) return
        val n = Notification.Builder(context, CH_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⏳ מטפל בזה…")
            .setContentText(userText)
            .setContentIntent(openChat(context))
            .setOnlyAlertOnce(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ASSISTANT_ID, n)
    }

    fun cancel(context: Context, itemId: String) =
        context.getSystemService(NotificationManager::class.java).cancel(itemId.hashCode())
}

/** הכפתורים שבהתראות: הוסף ליומן / בטל / התעלם. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ADD = "com.nirhib.kidsreminders.ADD"
        const val UNDO = "com.nirhib.kidsreminders.UNDO"
        const val DISMISS = "com.nirhib.kidsreminders.DISMISS"
        const val REPLY = "com.nirhib.kidsreminders.REPLY"
        const val ADD_ALL = "com.nirhib.kidsreminders.ADD_ALL"
        const val RETRY = "com.nirhib.kidsreminders.RETRY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            REPLY -> {
                val text = android.app.RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    Notifier.notifyWorking(context, text)
                    Work.chatFromNotification(context, text)
                }
                return
            }
            RETRY -> {
                val text = Store.get(context).lastFailedText() ?: return
                Notifier.notifyWorking(context, text)
                Work.chatFromNotification(context, text)
                return
            }
        }
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    ADD_ALL -> {
                        val n = Actions.addAllPending(context)
                        val msg = "✓ נוספו ליומן $n פריטים."
                        Store.get(context).addChat(false, msg)
                        Notifier.notifyAssistant(context, "🤖 עוזר", msg, showAddAll = false)
                    }
                    else -> {
                        val id = intent.getStringExtra("id") ?: return@Thread
                        when (intent.action) {
                            ADD -> Actions.addToCalendar(context, id)
                            UNDO, DISMISS -> Actions.dismiss(context, id)
                        }
                        Notifier.cancel(context, id)
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
