package com.nirhib.kidsreminders

import android.content.Context
import com.nirhib.kidsreminders.core.ExtractedItem
import com.nirhib.kidsreminders.core.IncomingMessage
import com.nirhib.kidsreminders.core.ItemType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class StoredMessage(
    val id: String,
    val kid: String,
    val group: String,
    val sender: String,
    val text: String,
    val time: Long,
    var processed: Boolean,
) {
    fun toIncoming() = IncomingMessage(
        group, sender, text, LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault()),
    )
}

enum class ItemStatus { PENDING, ADDED, DISMISSED }

data class StoredItem(
    val id: String,
    val item: ExtractedItem,
    var status: ItemStatus,
    var eventId: Long,
    val createdAt: Long,
    val source: String,
)

data class StoredSummary(val time: Long, val text: String)

/**
 * אחסון מקומי פשוט בקובץ JSON. רק הודעות מקבוצות שסומנו נשמרות; מקבוצות אחרות נשמר רק השם
 * (כדי שתוכל לבחור אותן במסך ההגדרות).
 */
class Store private constructor(private val file: File) {

    companion object {
        private const val KEEP_DAYS = 30L
        @Volatile private var instance: Store? = null

        fun get(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(File(context.applicationContext.filesDir, "store.json")).also { instance = it }
            }
    }

    private val messages = mutableListOf<StoredMessage>()
    private val items = mutableListOf<StoredItem>()
    private val seenGroups = linkedMapOf<String, Long>()
    private val seenKeys = LinkedHashSet<String>()
    private val summaries = mutableListOf<StoredSummary>()

    init {
        load()
    }

    // ---------- הודעות ----------

    /** מחזיר את ההודעה שנשמרה, או null אם כבר ראינו אותה (וואטסאפ שולח את אותה הודעה כמה פעמים). */
    @Synchronized
    fun addMessage(kid: String, group: String, sender: String, text: String, time: Long): StoredMessage? {
        val key = "$group|$sender|$time|${text.hashCode()}"
        if (!seenKeys.add(key)) return null
        while (seenKeys.size > 3000) seenKeys.remove(seenKeys.first())
        val m = StoredMessage(UUID.randomUUID().toString(), kid, group, sender, text, time, false)
        messages.add(m)
        save()
        return m
    }

    @Synchronized fun unprocessedMessages(): List<StoredMessage> = messages.filter { !it.processed }

    @Synchronized
    fun markProcessed(ids: Collection<String>) {
        messages.filter { it.id in ids }.forEach { it.processed = true }
        save()
    }

    @Synchronized fun recentMessages(days: Long = 3): List<StoredMessage> {
        val since = System.currentTimeMillis() - days * 86_400_000L
        return messages.filter { it.time >= since }.sortedByDescending { it.time }
    }

    @Synchronized
    fun noteGroup(group: String) {
        seenGroups[group] = System.currentTimeMillis()
        save()
    }

    @Synchronized fun knownGroups(): List<String> =
        seenGroups.entries.sortedByDescending { it.value }.map { it.key }

    // ---------- פריטים ----------

    /** מוסיף פריט אם הוא חדש. מחזיר null אם כבר קיים פריט זהה. */
    @Synchronized
    fun addItem(item: ExtractedItem, source: String): StoredItem? {
        val key = item.dedupKey()
        if (items.any { it.item.dedupKey() == key }) return null
        // אותו ילד, אותו סוג ואותו תאריך עם כותרת דומה – כנראה תזכורת חוזרת לאותו דבר
        if (item.date != null && items.any {
                it.item.kid == item.kid && it.item.type == item.type && it.item.date == item.date &&
                    similar(it.item.title, item.title)
            }) return null
        val s = StoredItem(UUID.randomUUID().toString(), item, ItemStatus.PENDING, -1, System.currentTimeMillis(), source)
        items.add(s)
        save()
        return s
    }

    @Synchronized fun item(id: String): StoredItem? = items.firstOrNull { it.id == id }

    @Synchronized
    fun update(id: String, block: (StoredItem) -> Unit) {
        items.firstOrNull { it.id == id }?.let(block)
        save()
    }

    @Synchronized fun deleteItem(id: String) {
        items.removeAll { it.id == id }
        save()
    }

    /** פריטים פעילים: מהיום והלאה, או בלי תאריך ונוצרו בשבוע האחרון. */
    @Synchronized
    fun activeItems(today: LocalDate = LocalDate.now()): List<StoredItem> =
        items.filter { it.status != ItemStatus.DISMISSED }
            .filter {
                val d = it.item.date
                if (d != null) !d.isBefore(today)
                else it.createdAt > System.currentTimeMillis() - 7 * 86_400_000L
            }
            .sortedWith(compareBy<StoredItem> { it.item.date == null }.thenBy { it.item.date?.toEpochDay() ?: 0L })

    // ---------- סיכומים ----------

    @Synchronized
    fun addSummary(text: String) {
        summaries.add(StoredSummary(System.currentTimeMillis(), text))
        while (summaries.size > 20) summaries.removeAt(0)
        save()
    }

    @Synchronized fun summaries(): List<StoredSummary> = summaries.sortedByDescending { it.time }

    // ---------- שמירה וטעינה ----------

    private fun similar(a: String, b: String): Boolean {
        val wa = ExtractedItem.normalize(a).split(" ").filter { it.length > 1 }.toSet()
        val wb = ExtractedItem.normalize(b).split(" ").filter { it.length > 1 }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return false
        return wa.intersect(wb).size.toDouble() / minOf(wa.size, wb.size) >= 0.6
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        messages.removeAll { it.time < cutoff && it.processed }
        val oldDate = LocalDate.now().minusDays(KEEP_DAYS)
        items.removeAll { (it.item.date?.isBefore(oldDate) ?: (it.createdAt < cutoff)) }
    }

    private fun save() {
        prune()
        val root = JSONObject()
        root.put("messages", JSONArray().apply {
            messages.forEach {
                put(JSONObject().put("id", it.id).put("kid", it.kid).put("group", it.group)
                    .put("sender", it.sender).put("text", it.text).put("time", it.time).put("processed", it.processed))
            }
        })
        root.put("items", JSONArray().apply {
            items.forEach {
                val i = it.item
                put(JSONObject().put("id", it.id).put("kid", i.kid).put("type", i.type.name).put("title", i.title)
                    .put("date", i.date?.toString() ?: "").put("time", i.time?.toString() ?: "")
                    .put("details", i.details).put("status", it.status.name).put("eventId", it.eventId)
                    .put("createdAt", it.createdAt).put("source", it.source))
            }
        })
        root.put("groups", JSONObject().apply { seenGroups.forEach { (k, v) -> put(k, v) } })
        root.put("keys", JSONArray(seenKeys.toList()))
        root.put("summaries", JSONArray().apply {
            summaries.forEach { put(JSONObject().put("time", it.time).put("text", it.text)) }
        })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    private fun load() {
        if (!file.exists()) return
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        root.optJSONArray("messages")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                messages.add(StoredMessage(o.getString("id"), o.getString("kid"), o.getString("group"),
                    o.optString("sender"), o.getString("text"), o.getLong("time"), o.optBoolean("processed")))
            }
        }
        root.optJSONArray("items")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val item = ExtractedItem(
                    kid = o.getString("kid"),
                    type = ItemType.parse(o.optString("type")),
                    title = o.getString("title"),
                    date = o.optString("date").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) },
                    time = o.optString("time").takeIf { it.isNotEmpty() }?.let { LocalTime.parse(it) },
                    details = o.optString("details"),
                )
                items.add(StoredItem(o.getString("id"), item,
                    runCatching { ItemStatus.valueOf(o.optString("status")) }.getOrDefault(ItemStatus.PENDING),
                    o.optLong("eventId", -1), o.optLong("createdAt"), o.optString("source")))
            }
        }
        root.optJSONObject("groups")?.let { g -> g.keys().forEach { seenGroups[it] = g.getLong(it) } }
        root.optJSONArray("keys")?.let { a -> for (i in 0 until a.length()) seenKeys.add(a.getString(i)) }
        root.optJSONArray("summaries")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                summaries.add(StoredSummary(o.getLong("time"), o.getString("text")))
            }
        }
    }
}
