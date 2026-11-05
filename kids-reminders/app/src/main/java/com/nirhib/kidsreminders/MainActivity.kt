package com.nirhib.kidsreminders

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.nirhib.kidsreminders.core.LlmProvider
import com.nirhib.kidsreminders.core.Hebrew
import com.nirhib.kidsreminders.core.IncomingMessage
import com.nirhib.kidsreminders.core.KidMessage
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** מסך יחיד עם שלוש לשוניות: סיכום, קבוצות, הגדרות. */
class MainActivity : Activity() {

    private enum class Tab(val label: String) { SUMMARY("סיכום"), CHAT("עוזר"), GROUPS("קבוצות"), SETTINGS("הגדרות") }

    companion object {
        const val EXTRA_TAB = "tab"
    }

    private var tab = Tab.SUMMARY
    private lateinit var content: LinearLayout
    private lateinit var tabBar: LinearLayout
    private val settings by lazy { Settings(this) }
    private val store by lazy { Store.get(this) }
    private val accent = 0xFF5B4BC4.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // כותרת עליונה משלנו (בלי ActionBar) – שם האפליקציה + לשוניות, תמיד גלויה
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(accent)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(tabBar)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        scroll = ScrollView(this).apply { addView(content) }

        // שורת הכתיבה לעוזר – מחוץ לגלילה, צמודה לתחתית (ומעל המקלדת כשהיא פתוחה)
        chatInput = EditText(this).apply {
            hint = "כתוב לעוזר…"
            maxLines = 4
            textSize = 15f
        }
        sendButton = Button(this).apply {
            text = "שלח"
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(accent)
            setOnClickListener { sendChat(chatInput.text.toString()) }
        }
        chatBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(0x1A5B4BC4)
            addView(chatInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sendButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(8) })
            visibility = View.GONE
        }

        root.addView(header)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(chatBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        // באנדרואיד 15 האפליקציה מצוירת על כל המסך – משאירים מקום לשורת הסטטוס ולכפתורי הניווט
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                top = bars.top; bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            header.setPadding(dp(12), top + dp(8), dp(12), dp(10))
            root.setPadding(0, 0, 0, bottom)
            insets
        }
    }

    private lateinit var scroll: ScrollView
    private lateinit var chatBar: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var sendButton: Button

    override fun onResume() {
        super.onResume()
        intent?.getStringExtra(EXTRA_TAB)?.let { name ->
            Tab.entries.firstOrNull { it.name == name }?.let { tab = it }
            intent.removeExtra(EXTRA_TAB)
        }
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun render() {
        tabBar.removeAllViews()
        Tab.entries.forEach { t ->
            tabBar.addView(Button(this).apply {
                text = t.label
                isAllCaps = false
                textSize = 15f
                stateListAnimator = null
                setTextColor(if (t == tab) accent else 0xFFFFFFFF.toInt())
                background = rounded(if (t == tab) 0xFFFFFFFF.toInt() else 0x33FFFFFF)
                setOnClickListener { tab = t; render(); scroll.scrollTo(0, 0) }
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        content.removeAllViews()
        chatBar.visibility = if (tab == Tab.CHAT) View.VISIBLE else View.GONE
        when (tab) {
            Tab.SUMMARY -> renderSummary()
            Tab.CHAT -> renderChat()
            Tab.GROUPS -> renderGroups()
            Tab.SETTINGS -> renderSettings()
        }
    }

    // ------------------------------------------------------------------ סיכום

    private fun renderSummary() {
        val warnings = mutableListOf<Pair<String, () -> Unit>>()
        if (!hasNotificationAccess()) warnings += "האפליקציה עוד לא מורשית לקרוא התראות" to ::openNotificationAccess
        if (settings.groupKids.isEmpty()) warnings += "עוד לא נבחרו קבוצות לעקוב אחריהן" to { tab = Tab.GROUPS; render() }
        if (!CalendarHelper.hasPermission(this)) warnings += "אין הרשאה ליומן" to ::requestCalendar
        warnings.forEach { (msg, fix) ->
            content.addView(card(0x33E8618C).apply {
                addView(text("⚠️ $msg", 15f, bold = true))
                addView(smallButton("לתיקון") { fix() })
            })
        }

        val today = LocalDate.now()
        val items = store.activeItems(today)
        content.addView(header("📅 מה מתקרב"))
        if (items.isEmpty()) content.addView(text("אין כרגע פריטים. כשתגיע הודעה חשובה מהקבוצות היא תופיע כאן.", 14f, dim = true))
        val pendingCount = items.count { it.status == ItemStatus.PENDING && it.item.date != null }
        if (pendingCount > 0) {
            content.addView(smallButton("✓ הוסף ליומן את כל $pendingCount הממתינים") {
                if (!CalendarHelper.hasPermission(this)) { requestCalendar(); return@smallButton }
                background {
                    val n = Actions.addAllPending(this)
                    runOnUiThread { toast("נוספו $n"); render() }
                }
            })
        }

        items.groupBy { it.item.date }.forEach { (date, list) ->
            val label = date?.let { d ->
                val rel = Hebrew.relativeDay(d, today)
                if (rel == "היום" || rel == "מחר") "$rel – יום ${Hebrew.dayName(d)} ${d.format(dm)}" else rel
            } ?: "ללא תאריך"
            content.addView(text(label, 14f, bold = true, color = accent).apply { setPadding(0, dp(10), 0, dp(2)) })
            list.forEach { content.addView(itemCard(it)) }
        }

        store.summaries().firstOrNull()?.let { s ->
            content.addView(header("🤖 סיכום אחרון (בינה מלאכותית) · ${formatTime(s.time)}"))
            content.addView(card().apply { addView(text(s.text, 14f)) })
        }

        val recent = store.recentMessages(3)
        content.addView(header("💬 הודעות אחרונות מהקבוצות"))
        if (recent.isEmpty()) content.addView(text("אין הודעות מ-3 הימים האחרונים.", 14f, dim = true))
        recent.take(40).forEach { m ->
            content.addView(card().apply {
                addView(text("${m.kid} · ${m.group} · ${formatTime(m.time)}", 12f, dim = true))
                addView(text((if (m.sender.isNotBlank()) "${m.sender}: " else "") + m.text.take(400), 14f))
            })
        }

        content.addView(row(
            smallButton("🌙 שלח סיכום עכשיו") { Work.runDigestNow(this); toast("נשלח") },
            smallButton("🧪 בדיקה עם הודעה") { testMessageDialog() },
        ))
    }

    private fun itemCard(s: StoredItem): View = card().apply {
        val i = s.item
        addView(text("#${s.num}  " + i.calendarTitle(), 16f, bold = true))
        val status = when (s.status) {
            ItemStatus.ADDED -> "✓ ביומן"
            else -> if (i.date == null) "ללא תאריך" else "⏳ מחכה לאישור"
        }
        addView(text(Notifier.whenText(s) + " · " + status, 13f, dim = true))
        if (i.details.isNotBlank() && i.details != i.title) addView(text(i.details.take(300), 13f))
        val buttons = mutableListOf<View>()
        if (s.status != ItemStatus.ADDED && i.date != null) {
            buttons += smallButton("הוסף ליומן") {
                if (!CalendarHelper.hasPermission(this@MainActivity)) { requestCalendar(); return@smallButton }
                background {
                    val ok = Actions.addToCalendar(this@MainActivity, s.id)
                    runOnUiThread { toast(if (ok) "נוסף ליומן" else "לא הצלחתי להוסיף – בדוק את היומן בהגדרות"); render() }
                }
            }
        }
        buttons += smallButton(if (s.status == ItemStatus.ADDED) "הסר מהיומן" else "הסתר") {
            background { Actions.dismiss(this@MainActivity, s.id); runOnUiThread { render() } }
        }
        addView(row(*buttons.toTypedArray()))
    }

    private fun testMessageDialog() {
        val kids = settings.kids
        if (kids.isEmpty()) return
        var kidIndex = 0
        val input = EditText(this).apply {
            hint = "למשל: מחר להביא תחפושת וכובע 🎭"
            minLines = 3
        }
        val kidButton = Button(this).apply { text = "ילדה: ${kids[0]}"; isAllCaps = false }
        kidButton.setOnClickListener {
            kidIndex = (kidIndex + 1) % kids.size
            kidButton.text = "ילדה: ${kids[kidIndex]}"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(kidButton)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("בדיקה: הדבק הודעה מהקבוצה")
            .setView(box)
            .setPositiveButton("נתח") { _, _ ->
                val text = input.text.toString()
                if (text.isBlank()) return@setPositiveButton
                background {
                    Actions.onIncoming(this, kids[kidIndex], "בדיקה", "בדיקה", text, System.currentTimeMillis())
                    if (settings.useAi) Work.scheduleAi(this, 0)
                    runOnUiThread {
                        toast(if (settings.useAi) "נשלח ל-${settings.provider.label}, התוצאה תופיע בעוד רגע" else "נותח")
                        render()
                    }
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    // ------------------------------------------------------------------ עוזר (צ'אט)

    private var chatBusy = false

    private fun renderChat() {
        if (!settings.useAi) {
            content.addView(card(0x33E8618C).apply {
                addView(text("כדי לדבר עם העוזר בשפה חופשית צריך להפעיל בינה מלאכותית (Claude או Gemini) בהגדרות. " +
                    "בינתיים אפשר לכתוב \"הוסף הכל\" או \"הוסף 1,3\".", 14f))
                addView(smallButton("להגדרות") { tab = Tab.SETTINGS; render() })
            })
        }
        content.addView(text(
            "אפשר לכתוב למשל: \"תוסיף את 1 ו-3\", \"בלי הטיול\", \"תזיז את האסיפה ל-19:00\", " +
                "\"מה לני צריכה מחר?\", \"תזכיר לי שרומי צריכה 20 ש״ח ביום ראשון\". לחיצה ארוכה על הודעה – העתקה.",
            13f, dim = true,
        ).apply { setPadding(0, dp(8), 0, dp(8)) })

        val turns = store.chat().takeLast(40)
        if (turns.isEmpty()) content.addView(text("עוד אין שיחה. הודעת הערב תופיע כאן, ואפשר גם לכתוב עכשיו.", 14f, dim = true))
        turns.forEachIndexed { index, t ->
            val failed = t.retryText != null
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(when {
                    t.fromUser -> 0x335B4BC4
                    failed -> 0x33E8618C
                    else -> 0x14808080
                })
                setPadding(dp(12), dp(8), dp(12), dp(8))
                addView(text(t.text, 15f).apply { setTextIsSelectable(true) })
                addView(text(formatTime(t.time), 11f, dim = true))
                // שגיאה אחרונה בשיחה – אפשר לשלוח שוב בלחיצה
                if (failed && index == turns.lastIndex && !chatBusy) {
                    addView(smallButton("🔁 שלח שוב") { sendChat(t.retryText!!) })
                }
            }
            val wrap = LinearLayout(this).apply {
                gravity = if (t.fromUser) Gravity.START else Gravity.END
                setPadding(if (t.fromUser) 0 else dp(32), dp(4), if (t.fromUser) dp(32) else 0, dp(4))
                addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            content.addView(wrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        if (chatBusy) content.addView(text("⏳ חושב… (אם המודל עמוס אנסה שוב ואעבור למודל פנוי יותר)", 14f, dim = true)
            .apply { setPadding(0, dp(6), 0, dp(6)) })

        chatInput.isEnabled = !chatBusy
        sendButton.isEnabled = !chatBusy
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendChat(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || chatBusy) return
        chatBusy = true
        chatInput.setText("")
        store.addChat(true, text) // מוצג מיד, עוד לפני שהעוזר עונה
        render()
        background {
            val result = Actions.chat(this, text, alreadyRecorded = true)
            runOnUiThread {
                chatBusy = false
                // נכשל? מחזירים את הטקסט לשורת הכתיבה כדי שלא צריך להקליד שוב
                if (result.failed && chatInput.text.isBlank()) chatInput.setText(text)
                render()
            }
        }
    }

    // ------------------------------------------------------------------ קבוצות

    private fun renderGroups() {
        content.addView(header("👧 הבנות"))
        val kidsInput = EditText(this).apply { setText(settings.kids.joinToString(", ")) }
        content.addView(kidsInput)
        content.addView(smallButton("שמור שמות") {
            settings.kids = kidsInput.text.toString().split(",", "،").map { it.trim() }.filter { it.isNotEmpty() }
            toast("נשמר"); render()
        })

        content.addView(header("💬 קבוצות וואטסאפ"))
        content.addView(text(
            "קבוצה מופיעה כאן אחרי שמגיעה ממנה הודעה (כשיש לאפליקציה גישה להתראות). " +
                "לחץ על קבוצה כדי לשייך אותה לילדה. רק הודעות מקבוצות משויכות נקראות ונשמרות.",
            13f, dim = true,
        ))
        val assigned = settings.groupKids
        val groups = (assigned.keys + store.knownGroups()).distinct()
        if (groups.isEmpty()) content.addView(text("עוד לא התקבלו הודעות מקבוצות.", 14f, dim = true))
        groups.forEach { g ->
            val kid = assigned[g]
            content.addView(card(if (kid != null) 0x225B4BC4 else 0x10808080).apply {
                addView(text(g, 15f, bold = kid != null))
                addView(text(kid?.let { "✓ עוקב – $it" } ?: "לא עוקב", 13f, dim = kid == null, color = if (kid != null) accent else null))
                isClickable = true
                setOnClickListener { assignDialog(g) }
            })
        }
        content.addView(smallButton("➕ הוסף קבוצה לפי שם") { addGroupDialog() })
    }

    private fun assignDialog(group: String) {
        val kids = settings.kids
        val options = (kids + "לא לעקוב").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(group)
            .setItems(options) { _, which ->
                settings.assignGroup(group, kids.getOrNull(which))
                render()
            }
            .show()
    }

    private fun addGroupDialog() {
        val input = EditText(this).apply { hint = "השם המדויק של הקבוצה בוואטסאפ" }
        AlertDialog.Builder(this)
            .setTitle("הוספת קבוצה")
            .setView(input)
            .setPositiveButton("המשך") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) { store.noteGroup(name); assignDialog(name) }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    // ------------------------------------------------------------------ הגדרות

    private fun renderSettings() {
        content.addView(header("🔐 הרשאות"))
        content.addView(statusRow("קריאת התראות (וואטסאפ)", hasNotificationAccess(), ::openNotificationAccess))
        content.addView(statusRow("גישה ליומן", CalendarHelper.hasPermission(this), ::requestCalendar))
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            content.addView(statusRow("הצגת התראות", granted) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            })
        }

        content.addView(header("📅 יומן"))
        if (CalendarHelper.hasPermission(this)) CalendarHelper.ensureCalendar(this, settings)
        content.addView(text("יומן: " + settings.calendarName.ifEmpty { "לא נבחר" }, 14f))
        content.addView(smallButton("בחר יומן") { chooseCalendar() })
        content.addView(switch("התראה מיידית על כל פריט חדש (אחרת – רק סיכום ערב)", settings.notifyEachItem) {
            settings.notifyEachItem = it
        })
        content.addView(switch("להוסיף ליומן בלי לשאול", settings.autoAddToCalendar) {
            settings.autoAddToCalendar = it
        })
        content.addView(text("סיכום ערב יומי בשעה ${settings.digestHour}:00", 14f).apply { setPadding(0, dp(12), 0, 0) })
        content.addView(smallButton("שנה שעה") {
            val hours = (16..22).toList()
            AlertDialog.Builder(this).setTitle("שעת סיכום ערב")
                .setItems(hours.map { "$it:00" }.toTypedArray()) { _, w ->
                    settings.digestHour = hours[w]; Work.scheduleDigest(this, replace = true); render()
                }.show()
        })

        content.addView(header("🤖 סיכום חכם עם בינה מלאכותית (אופציונלי)"))
        content.addView(text(
            "בלי בינה מלאכותית, האפליקציה מזהה מבחנים, ציוד, אירועים, תשלומים ושינויים לפי מילות מפתח – על המכשיר בלבד. " +
                "עם Claude או Gemini ההבנה מדויקת הרבה יותר ומתקבל גם סיכום כתוב, אבל ההודעות מהקבוצות שבחרת " +
                "נשלחות לספק שבחרת (Anthropic או Google). צריך מפתח API משלך.",
            13f, dim = true,
        ))
        content.addView(switch("להשתמש בבינה מלאכותית", settings.aiEnabled) { settings.aiEnabled = it })

        val p = settings.provider
        content.addView(text("ספק", 12f, dim = true).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(row(*LlmProvider.entries.map { option ->
            Button(this).apply {
                text = (if (option == p) "✓ " else "") + option.label
                isAllCaps = false
                setTextColor(if (option == p) 0xFFFFFFFF.toInt() else accent)
                background = rounded(if (option == p) accent else 0x1A5B4BC4)
                setOnClickListener { settings.provider = option; render() }
            }.also { it.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) } }
        }.toTypedArray()))

        val keyInput = EditText(this).apply {
            hint = "מפתח API של ${p.label} (${p.keyHint})"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(settings.apiKeyFor(p))
        }
        val modelInput = EditText(this).apply {
            hint = p.defaultModel
            setText(settings.modelFor(p))
        }
        content.addView(text("מפתח מתקבל ב-${p.keyUrl}", 12f, dim = true).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(keyInput)
        content.addView(text("מודל (ברירת מחדל: ${p.defaultModel})", 12f, dim = true))
        content.addView(modelInput)
        val suggestions = when (p) {
            LlmProvider.GEMINI -> listOf("gemini-3.1-flash-lite", "gemini-2.5-flash-lite", "gemini-3.6-flash")
            LlmProvider.CLAUDE -> listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5")
        }
        content.addView(text("בחירה מהירה:", 12f, dim = true))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            suggestions.forEach { m -> addView(smallButton(m) { modelInput.setText(m) }) }
        })
        if (p == LlmProvider.GEMINI) content.addView(text(
            "כשהמודל עמוס, האפליקציה מנסה שוב ואז עוברת אוטומטית למודל קל ופנוי יותר.", 12f, dim = true))
        fun save() {
            settings.setApiKeyFor(p, keyInput.text.toString())
            settings.setModelFor(p, modelInput.text.toString())
        }
        content.addView(row(
            smallButton("שמור") { save(); toast("נשמר") },
            smallButton("בדוק חיבור") { save(); testAi() },
        ))
    }

    private fun testAi() {
        if (settings.apiKey.isBlank()) { toast("צריך להזין מפתח"); return }
        val provider = settings.provider
        toast("בודק את ${provider.label}…")
        background {
            val msg = try {
                val now = LocalDateTime.now()
                val r = settings.extractor().extract(
                    listOf(KidMessage(settings.kids.firstOrNull() ?: "מילי",
                        IncomingMessage("בדיקה", "הגננת", "הורים יקרים, מחר יום פיג'מות! להביא פיג'מה ובובה אהובה.", now))),
                    settings.kids, now.toLocalDate(),
                )
                "✓ ${provider.label} עובד!\nזוהה: " + r.items.joinToString { it.calendarTitle() }.ifEmpty { "(כלום)" }
            } catch (e: Exception) {
                "✗ שגיאה: ${e.message?.take(300)}"
            }
            runOnUiThread { AlertDialog.Builder(this).setMessage(msg).setPositiveButton("סגור", null).show() }
        }
    }

    private fun chooseCalendar() {
        if (!CalendarHelper.hasPermission(this)) { requestCalendar(); return }
        val cals = CalendarHelper.listCalendars(this)
        if (cals.isEmpty()) { toast("לא נמצאו יומנים במכשיר"); return }
        AlertDialog.Builder(this).setTitle("לאיזה יומן להוסיף?")
            .setItems(cals.map { "${it.name}\n${it.account}" }.toTypedArray()) { _, w ->
                settings.calendarId = cals[w].id
                settings.calendarName = "${cals[w].name} (${cals[w].account})"
                render()
            }.show()
    }

    // ------------------------------------------------------------------ הרשאות

    private fun hasNotificationAccess(): Boolean {
        val enabled = AndroidSettings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    private fun openNotificationAccess() {
        startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun requestCalendar() {
        requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // ------------------------------------------------------------------ עזרים לבניית המסך

    private val dm = DateTimeFormatter.ofPattern("d/M")
    private val dtm = DateTimeFormatter.ofPattern("d/M HH:mm")

    private fun formatTime(ms: Long) =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(dtm)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }

    private fun text(s: String, size: Float, bold: Boolean = false, dim: Boolean = false, color: Int? = null) =
        TextView(this).apply {
            text = s
            textSize = size
            textDirection = View.TEXT_DIRECTION_RTL
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (dim) alpha = 0.7f
            color?.let { setTextColor(it) }
        }

    private fun header(s: String) = text(s, 18f, bold = true).apply { setPadding(0, dp(20), 0, dp(6)) }

    private fun card(color: Int = 0x145B4BC4) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(4), 0, dp(4)) }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START
        views.forEach { addView(it) }
    }

    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 15f
        isChecked = value
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun statusRow(label: String, ok: Boolean, fix: () -> Unit) = card(if (ok) 0x2238A169 else 0x22E8618C).apply {
        addView(text((if (ok) "✅ " else "❌ ") + label, 15f))
        if (!ok) addView(smallButton("אישור") { fix() })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun background(block: () -> Unit) = Thread(block).start()
}
