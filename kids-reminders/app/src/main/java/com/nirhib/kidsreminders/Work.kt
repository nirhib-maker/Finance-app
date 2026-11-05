package com.nirhib.kidsreminders

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object Work {
    private const val AI = "ai-batch"
    private const val DIGEST = "evening-digest"

    /** מתזמן ניתוח במודל השפה בעוד 3 דקות (כל הודעה חדשה דוחה את הטיימר – כך נאספות הודעות יחד). */
    fun scheduleAi(context: Context, delayMinutes: Long = 3) {
        val req = OneTimeWorkRequestBuilder<AiWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(AI, ExistingWorkPolicy.REPLACE, req)
    }

    /** סיכום ערב יומי בשעה שנבחרה. */
    fun scheduleDigest(context: Context, replace: Boolean = false) {
        val hour = Settings(context).digestHour
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(hour, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val req = PeriodicWorkRequestBuilder<DigestWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DIGEST,
            if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    fun chatFromNotification(context: Context, text: String) {
        val req = OneTimeWorkRequestBuilder<ChatWorker>()
            .setInputData(androidx.work.workDataOf("text" to text))
            .build()
        WorkManager.getInstance(context).enqueue(req)
    }

    fun runDigestNow(context: Context) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<DigestWorker>().build())
    }
}

/** מנתח ברקע את ההודעות שהצטברו (בלי להקפיץ התראות). אם הבינה המלאכותית נכשלת – מנסה שוב, ואז נופל לחוקים. */
class AiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val lastAttempt = runAttemptCount >= 2
        if (Actions.processPending(applicationContext, allowRulesFallback = lastAttempt)) Result.success()
        else Result.retry()
    }
}

/** הודעת הערב: סיכום היום + מה מחכה לאישור, עם אפשרות לענות ישירות מההתראה. */
class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val text = Actions.eveningDigest(ctx)
        if (text != null) {
            Store.get(ctx).addChat(false, text)
            val hasPending = Actions.assistantItems(ctx).any {
                it.state == com.nirhib.kidsreminders.core.ItemState.PENDING && it.item.date != null
            }
            Notifier.notifyAssistant(ctx, "🌙 סיכום ערב", text, showAddAll = hasPending)
        }
        Result.success()
    }
}

/** תשובה שנכתבה ישירות בהתראה: שולחים לעוזר ומציגים את התשובה באותה התראה. */
class ChatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val text = inputData.getString("text") ?: return@withContext Result.success()
        val answer = Actions.chat(applicationContext, text)
        Notifier.notifyAssistant(applicationContext, "🤖 עוזר", answer, showAddAll = false)
        Result.success()
    }
}
