package com.nirhib.kidsreminders

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nirhib.kidsreminders.core.ClaudeExtractor
import com.nirhib.kidsreminders.core.DigestBuilder
import com.nirhib.kidsreminders.core.KidMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object Work {
    private const val AI = "ai-batch"
    private const val DIGEST = "evening-digest"

    /** מתזמן ניתוח ב-Claude בעוד 3 דקות (כל הודעה חדשה דוחה את הטיימר – כך נאספות הודעות יחד). */
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

    fun runDigestNow(context: Context) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<DigestWorker>().build())
    }
}

/** שולח את ההודעות שעוד לא נותחו ל-Claude. אם נכשל – נופל חזרה לחוקים המקומיים. */
class AiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val settings = Settings(ctx)
        val store = Store.get(ctx)
        val pending = store.unprocessedMessages()
        if (pending.isEmpty()) return@withContext Result.success()

        if (!settings.useAi) {
            Actions.processWithRules(ctx, pending)
            return@withContext Result.success()
        }

        try {
            val extractor = ClaudeExtractor(settings.apiKey, settings.model)
            val result = extractor.extract(
                pending.map { KidMessage(it.kid, it.toIncoming()) },
                settings.kids,
                LocalDate.now(),
            )
            Actions.handleItems(ctx, result.items, "claude")
            result.summary?.let { store.addSummary(it) }
            store.markProcessed(pending.map { it.id })
            Result.success()
        } catch (e: Exception) {
            Log.w("AiWorker", "Claude failed, attempt $runAttemptCount", e)
            if (runAttemptCount < 2) {
                Result.retry()
            } else {
                // לא מפספסים כלום: מנתחים עם החוקים המקומיים
                Actions.processWithRules(ctx, pending)
                Result.success()
            }
        }
    }
}

/** סיכום ערב: מה צריך מחר ומה מתקרב השבוע. */
class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val items = Store.get(ctx).activeItems().map { it.item }
        val text = DigestBuilder.build(items, LocalDate.now())
        if (text != null) Notifier.notifyDigest(ctx, "🌙 מה צריך מחר", text)
        Result.success()
    }
}
