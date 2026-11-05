package com.nirhib.kidsreminders.core

/**
 * חילוץ פריטים חשובים מהודעות לפי מילות מפתח בעברית – עובד על המכשיר, בלי אינטרנט.
 * פחות מדויק מ-Claude, אבל חינמי ופרטי לגמרי.
 */
object RuleExtractor {

    /** אותיות שימוש שיכולות להופיע לפני מילה: ו, ה, ב, ל, מ, ש, כ */
    private const val PREFIX = "(?<![\\p{L}])[והבלמשכ]{0,2}"

    private fun words(vararg w: String) = Regex(PREFIX + "(?:" + w.joinToString("|") + ")")

    /** סדר הבדיקה = עדיפות. משפט מקבל סוג אחד בלבד. */
    private val rules: List<Pair<ItemType, Regex>> = listOf(
        ItemType.CHANGE to words(
            "אין לימודים", "לא יתקיימו לימודים", "יום חופש", "חופשת", "שביתה", "שובתים",
            "יום קצר", "סיום מוקדם", "מסתיימים ב", "איסוף מוקדם", "הגן סגור", "הגן יהיה סגור",
            "הגן ייסגר", "בית הספר סגור", "מבוטל", "מבוטלת", "בוטל", "בוטלה", "ביטול",
            "שינוי במערכת", "שינוי בשעות", "לימודים מרחוק", "למידה מרחוק", "בזום",
        ),
        ItemType.EXAM to words(
            "מבחן", "מבחנים", "בוחן", "בחנים", "בחינה", "בחינות", "מבדק", "מבדקים", "בגרות",
            "הכתבה", "הגשת עבודה", "הגשת העבודה", "עבודה להגשה", "להגיש את העבודה", "להגיש עבודה",
        ),
        ItemType.BRING to words(
            "להביא", "תביאו", "יביאו", "להצטייד", "הצטיידו", "לצייד", "לשלוח עם", "לשלוח לגן",
            "לשלוח לבית הספר", "להגיע עם", "להגיע לבושים", "לבוש לבן", "חולצה לבנה", "תלבושת",
            "תחפושת", "בגדים להחלפה", "בגדי החלפה", "כובע", "בקבוק מים", "ציוד",
        ),
        ItemType.PAYMENT to words(
            "תשלום", "לשלם", "להעביר כסף", "להעביר תשלום", "ביט(?![\\p{L}])", "bit(?![a-z])", "פייבוקס", "paybox",
            "ש\"ח", "ש״ח", "שקלים", "₪",
        ),
        ItemType.FORM to words(
            "טופס", "טפסים", "אישור הורים", "לחתום", "חתימה", "חתימת", "להחזיר את האישור",
            "למלא את", "למלא טופס", "מילוי טופס",
        ),
        ItemType.EVENT to words(
            "טיול", "מסיבה", "מסיבת", "אסיפה", "אסיפת", "ערב הורים", "הצגה", "טקס", "יום הולדת",
            "חגיגה", "חגיגת", "יום כיף", "יום ספורט", "יום פתוח", "סיור", "מופע", "פעילות",
            "שבת גן", "קבלת שבת", "מפגש הורים", "שיחות הורים", "פיקניק", "הופעה",
        ),
    )

    private val sentenceSplit = Regex("\\n+|(?<=[.!])\\s+")

    fun extract(message: IncomingMessage, kid: String): List<ExtractedItem> {
        val text = message.text.trim()
        if (text.length < 4) return emptyList()

        val messageWhen = HebrewDateParser.parse(text, message.receivedAt)
        val result = LinkedHashMap<String, ExtractedItem>()

        for (raw in text.split(sentenceSplit)) {
            val sentence = raw.trim()
            if (sentence.length < 4) continue
            // שאלות של הורים אחרים ("מה צריך להביא מחר?") – לא מידע מהגננת/המורה
            if (sentence.endsWith("?")) continue

            val (type, match) = classify(sentence) ?: continue
            val sentenceWhen = HebrewDateParser.parse(sentence, message.receivedAt)
            val date = sentenceWhen.date ?: messageWhen.date
            val time = sentenceWhen.time ?: if (sentenceWhen.date == null) messageWhen.time else null

            val item = ExtractedItem(
                kid = kid,
                type = type,
                title = makeTitle(sentence, match, type),
                date = date,
                time = time,
                details = text,
            )
            result.putIfAbsent(item.dedupKey(), item)
        }
        return result.values.toList()
    }

    fun classify(sentence: String): Pair<ItemType, MatchResult>? {
        for ((type, regex) in rules) {
            val m = regex.find(sentence) ?: continue
            return type to m
        }
        return null
    }

    private fun makeTitle(sentence: String, match: MatchResult, type: ItemType): String {
        val cleaned = sentence.replace(Regex("\\s+"), " ").trim()
        // ל"להביא" ו"מבחן" – מתחילים מהמילה עצמה, כדי שהכותרת תהיה ממוקדת
        val start = if (type == ItemType.BRING || type == ItemType.EXAM) {
            val idx = match.range.first.coerceIn(0, cleaned.length)
            if (idx > 25) idx else 0
        } else 0
        return shorten(cleaned.substring(start).trimStart(' ', '-', ':', ','), 70)
    }

    fun shorten(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.lastIndexOf(' ', max)
        return (if (cut > max / 2) s.substring(0, cut) else s.substring(0, max)) + "…"
    }
}
