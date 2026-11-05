package com.nirhib.kidsreminders.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// יום חמישי, 5/11/2026, 14:00
private val NOW: LocalDateTime = LocalDateTime.of(2026, 11, 5, 14, 0)
private val TODAY: LocalDate = NOW.toLocalDate()

private fun msg(text: String, group: String = "גן חבצלת") = IncomingMessage(group, "הגננת", text, NOW)

class HebrewDateParserTest {
    private fun date(t: String) = HebrewDateParser.parseDate(t, TODAY)

    @Test fun relative() {
        assertEquals(TODAY.plusDays(1), date("מחר להביא תחפושת"))
        assertEquals(TODAY.plusDays(2), date("מחרתיים יש טיול"))
        assertEquals(TODAY, date("היום יש אסיפה"))
        assertEquals(TODAY.plusDays(1), date("תזכורת למחר"))
    }

    @Test fun weekdays() {
        assertEquals(LocalDate.of(2026, 11, 10), date("ביום שלישי יש מבחן"))
        assertEquals(LocalDate.of(2026, 11, 10), date("ביום ג' יש מבחן"))
        assertEquals(LocalDate.of(2026, 11, 8), date("ביום א׳ מתחילים"))
        // יום חמישי כשהיום חמישי -> בשבוע הבא
        assertEquals(LocalDate.of(2026, 11, 12), date("ביום חמישי טקס"))
        assertEquals(LocalDate.of(2026, 11, 6), date("ביום ו' שבת גן"))
        // "יום הולדת" הוא לא יום ה'
        assertNull(date("יום הולדת שמח!"))
    }

    @Test fun nextWeek() {
        // ראשון 8/11 כבר בשבוע הבא, לכן לא מוסיפים שבוע
        assertEquals(LocalDate.of(2026, 11, 8), date("בשבוע הבא ביום ראשון"))
    }

    @Test fun explicitDates() {
        assertEquals(LocalDate.of(2026, 11, 12), date("המבחן יתקיים ב-12/11"))
        assertEquals(LocalDate.of(2026, 11, 12), date("בתאריך 12.11.26"))
        assertEquals(LocalDate.of(2026, 11, 20), date("ה-20 בנובמבר טיול"))
        assertEquals(LocalDate.of(2027, 1, 5), date("חזרה ללימודים 5/1"))
        // "בשעה 8.30" זו לא תאריך
        assertNull(date("נפגשים בשעה 8.30"))
        // תאריך מפורש גובר על "מחר"
        assertEquals(LocalDate.of(2026, 11, 12), date("מחר אזכיר, המבחן ב-12/11"))
    }

    @Test fun times() {
        assertEquals(LocalTime.of(8, 30), HebrewDateParser.parseTime("להגיע בשעה 8:30"))
        assertEquals(LocalTime.of(17, 0), HebrewDateParser.parseTime("אסיפה ב-17:00"))
        assertEquals(LocalTime.of(16, 0), HebrewDateParser.parseTime("מסיבה בשעה 4"))
        assertNull(HebrewDateParser.parseTime("מחר להביא כובע"))
    }
}

class RuleExtractorTest {
    @Test fun bringTomorrow() {
        val items = RuleExtractor.extract(msg("הורים יקרים, מחר יום כיף! נא להביא בגד ים ומגבת 🌞"), "מילי")
        val bring = items.single { it.type == ItemType.BRING }
        assertEquals(TODAY.plusDays(1), bring.date)
        assertEquals("מילי", bring.kid)
        assertTrue(bring.title.contains("להביא"))
    }

    @Test fun exam() {
        val items = RuleExtractor.extract(
            msg("שלום לכולם. ביום שלישי יתקיים מבחן במתמטיקה על שברים.", "ה'2 הורים"), "רומי",
        )
        val exam = items.single()
        assertEquals(ItemType.EXAM, exam.type)
        assertEquals(LocalDate.of(2026, 11, 10), exam.date)
        assertEquals("📝 רומי: ${exam.title}", exam.calendarTitle())
    }

    @Test fun changeAndPayment() {
        val items = RuleExtractor.extract(
            msg("ביום ראשון אין לימודים בגלל השביתה.\nכמו כן יש להעביר תשלום של 50 ש\"ח לוועד עד 15/11."),
            "לני",
        )
        assertEquals(listOf(ItemType.CHANGE, ItemType.PAYMENT), items.map { it.type })
        assertEquals(LocalDate.of(2026, 11, 8), items[0].date)
        assertEquals(LocalDate.of(2026, 11, 15), items[1].date)
    }

    @Test fun eventWithTime() {
        val item = RuleExtractor.extract(msg("אסיפת הורים תתקיים ב-12/11 בשעה 18:30"), "רומי").single()
        assertEquals(ItemType.EVENT, item.type)
        assertEquals(LocalDate.of(2026, 11, 12), item.date)
        assertEquals(LocalTime.of(18, 30), item.time)
    }

    @Test fun ignoresChatter() {
        assertTrue(RuleExtractor.extract(msg("תודה רבה!! 🙏"), "מילי").isEmpty())
        assertTrue(RuleExtractor.extract(msg("מישהו יודע מה צריך להביא מחר?"), "מילי").isEmpty())
        assertTrue(RuleExtractor.extract(msg("מזל טוב ליום הולדת"), "מילי").all { it.type == ItemType.EVENT })
        // "ביטוח" זה לא "ביט"
        assertTrue(RuleExtractor.extract(msg("קיבלנו את חוברת הביטוח"), "מילי").isEmpty())
    }
}

class DigestTest {
    @Test fun buildsTomorrowAndWeek() {
        val items = listOf(
            ExtractedItem("מילי", ItemType.BRING, "להביא תחפושת", TODAY.plusDays(1), null, ""),
            ExtractedItem("רומי", ItemType.EXAM, "מבחן בחשבון", LocalDate.of(2026, 11, 10), null, ""),
            ExtractedItem("לני", ItemType.EVENT, "טיול", LocalDate.of(2026, 12, 1), null, ""),
        )
        val text = assertNotNull(DigestBuilder.build(items, TODAY))
        assertTrue(text.startsWith("מחר (יום שישי):"))
        assertTrue(text.contains("🎒 מילי: להביא תחפושת"))
        assertTrue(text.contains("יום שלישי 10/11 – 📝 רומי: מבחן בחשבון"))
        assertTrue(!text.contains("טיול"))
        assertNull(DigestBuilder.build(emptyList(), TODAY))
    }
}

class WhatsAppTitleTest {
    @Test fun normalize() {
        assertEquals("גן חבצלת", WhatsAppTitle.normalizeGroup("גן חבצלת (3 הודעות)"))
        assertEquals("Class 2B", WhatsAppTitle.normalizeGroup("Class 2B (12 messages)"))
        assertEquals("גן חבצלת", WhatsAppTitle.normalizeGroup("דנה @ גן חבצלת"))
        assertEquals("גן חבצלת" to "דנה", WhatsAppTitle.splitGroupSender("גן חבצלת: דנה", listOf("גן חבצלת")))
        assertTrue(WhatsAppTitle.isNoise("הודעה זו נמחקה"))
    }
}

class ClaudeExtractorParsingTest {
    @Test fun parsesStructuredResponse() {
        val json = """
            {"summary":"רומי: מבחן ביום ג'","items":[
              {"kid":"רומי","type":"EXAM","title":"מבחן בחשבון","date":"2026-11-10","time":"","details":"שברים"},
              {"kid":"מילי","type":"BRING","title":"להביא תחפושת","date":"","time":"08:00","details":""},
              {"kid":"לני","type":"EXAM","title":"","date":"","time":"","details":""}
            ]}
        """.trimIndent()
        val r = ExtractionPrompt.parseResponse(json, listOf("מילי", "רומי", "לני"))
        assertEquals(2, r.items.size)
        assertEquals(LocalDate.of(2026, 11, 10), r.items[0].date)
        assertNull(r.items[1].date)
        assertEquals(LocalTime.of(8, 0), r.items[1].time)
        assertEquals("רומי: מבחן ביום ג'", r.summary)
    }

    @Test fun promptContainsContext() {
        val p = ExtractionPrompt.buildUserPrompt(listOf(KidMessage("מילי", msg("מחר להביא כובע"))), listOf("מילי"), TODAY)
        assertTrue(p.contains("Today is 2026-11-05 (Thursday)"))
        assertTrue(p.contains("[child: מילי] [group: גן חבצלת] [2026-11-05 14:00 Thu]"))
    }
}

class GeminiExtractorTest {
    private val kids = listOf("מילי", "רומי", "לני")
    private val input = listOf(KidMessage("מילי", msg("מחר להביא תחפושת")))

    @Test fun sendsRequestAndParsesAnswer() {
        val server = MockWebServer()
        val answer = """{"summary":"מילי: תחפושת מחר","items":[{"kid":"מילי","type":"BRING","title":"להביא תחפושת","date":"2026-11-06","time":"","details":""}]}"""
        val payload = mapOf(
            "candidates" to listOf(mapOf(
                "content" to mapOf("parts" to listOf(
                    mapOf("text" to "thinking...", "thought" to true),
                    mapOf("text" to answer),
                )),
                "finishReason" to "STOP",
            ))
        )
        server.enqueue(MockResponse().setBody(com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload)))
        server.start()
        try {
            val r = GeminiExtractor("test-key", "gemini-x", baseUrl = server.url("/v1beta").toString().trimEnd('/'))
                .extract(input, kids, TODAY)
            assertEquals(ItemType.BRING, r.items.single().type)
            assertEquals(LocalDate.of(2026, 11, 6), r.items.single().date)
            assertEquals("מילי: תחפושת מחר", r.summary)

            val req = server.takeRequest()
            assertEquals("/v1beta/models/gemini-x:generateContent", req.path)
            assertEquals("test-key", req.getHeader("x-goog-api-key"))
            val body = req.body.readUtf8()
            assertTrue(body.contains("\"responseMimeType\":\"application/json\""))
            assertTrue(body.contains("\"responseSchema\""))
            assertTrue(body.contains("systemInstruction"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun reportsApiErrors() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"API key not valid"}}"""))
        server.start()
        try {
            val e = assertFailsWith<java.io.IOException> {
                GeminiExtractor("bad", baseUrl = server.url("/v1beta").toString().trimEnd('/')).extract(input, kids, TODAY)
            }
            assertTrue(e.message!!.contains("API key not valid"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun blockedPrompt() {
        assertFailsWith<IllegalStateException> {
            GeminiExtractor.extractText("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        }
    }
}
