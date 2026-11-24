package com.nirhib.boiler

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
import com.nirhib.boiler.core.ScheduleSync
import com.nirhib.boiler.core.Settings
import com.nirhib.boiler.core.SwitcherClient
import com.nirhib.boiler.core.parseOpenMeteo
import com.nirhib.boiler.core.planDays
import com.nirhib.boiler.core.weatherUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.round

/**
 * One sync: fetch the forecast, plan, and write the heating windows of the next 24 hours into the
 * switch's own one-time schedules. Works only when the phone is on the home Wi-Fi (the switch
 * answers on the local network only); otherwise it just records that the switch was not reachable.
 */
object Sync {
    private const val TAG = "BoilerSync"
    private val IL: ZoneId = ZoneId.of("Asia/Jerusalem")

    /** Synchronized: the hourly job and the "sync now" button must not write schedules at the same time. */
    @Synchronized
    fun run(context: Context): Store.SyncResult {
        val store = Store(context)
        val result = try {
            runInner(store)
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            Store.SyncResult(System.currentTimeMillis(), false, friendly(e))
        }
        store.lastSync = result
        return result
    }

    private fun friendly(e: Exception) = when (e) {
        is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
            "המתג לא ענה – כנראה הטלפון לא ב-Wi-Fi של הבית. התזמונים הקודמים נשארו במתג."
        is java.net.UnknownHostException -> "אין חיבור לאינטרנט (תחזית מזג האוויר)"
        else -> "שגיאה: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun runInner(store: Store): Store.SyncResult {
        val now = System.currentTimeMillis()
        val base = store.settings() ?: return Store.SyncResult(now, false, "פתחו את האפליקציה פעם אחת כדי לשמור הגדרות")
        val sw = base.switcher
        if (!sw.configured) return Store.SyncResult(now, false, "המתג עוד לא הוגדר – ⚙️ הגדרות → חיפוש המתג ברשת")
        // plan with the heater power really measured, when known (same as the web UI does)
        val s = store.measuredHeaterKw?.let { base.copy(tank = base.tank.copy(heaterKw = it)) } ?: base

        val weather = parseOpenMeteo(Settings.mapper.readTree(httpGet(weatherUrl(s.location))))
        val nowIl = LocalDateTime.now(IL)
        val days = planDays(weather, s, nowIl.toLocalDate().toString())

        SwitcherClient(sw.ip, sw.id, sw.key, zone = ZoneId.systemDefault(), timeoutMs = 4000).use { client ->
            val out = ScheduleSync.apply(client, days, nowIl, store.programmed)
            store.programmed = out.programmed
            val state = out.state
            if (state.on && state.watts > 300) { // caught it heating: remember the real heater power
                val kw = state.watts / 1000.0
                val prev = store.measuredHeaterKw
                store.measuredHeaterKw = round((if (prev == null) kw else prev * 0.5 + kw * 0.5) * 100) / 100
            }
            val msg = if (!out.verified) "חלק מהתזמונים לא אומתו במתג – נסו שוב"
                else "המתג מתוכנת ל-${out.programmed.size} הפעלות ב-24 השעות הקרובות"
            return Store.SyncResult(now, out.verified, msg, Store.StateDto(state.on, state.watts), out.skipped)
        }
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        try {
            if (c.responseCode != 200) throw IllegalStateException("weather HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    // ---- scheduling the background job ----
    private const val PERIODIC = "boiler-sync"
    private const val NOW = "boiler-sync-now"
    private val wifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

    /** About once an hour, only on Wi-Fi (mobile data could never reach the switch anyway). */
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(wifi).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Soon, e.g. after the settings changed. */
    fun soon(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(wifi).setInitialDelay(5, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Sync.run(applicationContext)
        Result.success() // never retry aggressively: not being at home is normal
    }
}
