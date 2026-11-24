package com.nirhib.boiler.core

import com.nirhib.boiler.core.ScheduleSync.Programmed
import java.net.ServerSocket
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A fake Switcher on localhost: checks every packet's length header and CRC signature (a real switch
 * ignores badly signed packets), and keeps a schedule table like the device. Exercises the real
 * SwitcherClient socket code and ScheduleSync.apply end to end.
 */
class FakeSwitcher(private val zone: ZoneId) : AutoCloseable {
    data class Row(val id: Int, val recurring: Boolean, val start: Long, val end: Long)
    val schedules = mutableListOf<Row>()
    val log = mutableListOf<String>()
    var badPackets = 0
    private val server = ServerSocket(0)
    val port get() = server.localPort
    private val stateHex = "00".repeat(75) + "01" + "00" + "6a09" + "00".repeat(11) + "08070000" + "00".repeat(4) + "301b0000" + "00".repeat(4)

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    sock.use { s ->
                        val inp = s.getInputStream(); val out = s.getOutputStream(); val buf = ByteArray(2048)
                        while (true) {
                            val n = runCatching { inp.read(buf) }.getOrDefault(-1)
                            if (n <= 0) break
                            out.write(SwitcherProtocol.unhex(handle(SwitcherProtocol.hex(buf.copyOf(n))))); out.flush()
                        }
                    }
                }
            }
        }
    }

    private fun le(v: Long) = SwitcherProtocol.le32(v)

    @Synchronized
    private fun handle(h: String): String {
        val body = h.dropLast(8)
        val lengthOk = (h.substring(4, 6) + "").toInt(16) == h.length / 2
        if (!lengthOk || SwitcherProtocol.sign(body) != h) { badPackets++; return "00" }
        val kind = h.substring(8, 16)
        val tail = body.substring(body.length - 40)
        return when {
            kind == "0232a100" -> { log += "login"; "fef0" + "00".repeat(4) + "cafebabe" + "00".repeat(20) }
            kind == "02320103" -> { log += "state"; stateHex }
            tail.endsWith("00060000") -> { log += "list"; listResponse() }
            body.contains("00030c00ff0100") -> {
                val data = body.substringAfterLast("00030c00ff")
                val start = SwitcherProtocol.readLe32(data.substring(6, 14)); val end = SwitcherProtocol.readLe32(data.substring(14, 22))
                val id = (0 until 8).first { i -> schedules.none { it.id == i } }
                schedules += Row(id, false, start, end); log += "create $id"; "fef0" + "00".repeat(30)
            }
            body.substring(body.length - 10, body.length - 1) == "000801000" -> {
                val id = body.takeLast(1).toInt(); schedules.removeAll { it.id == id }; log += "delete $id"; "fef0" + "00".repeat(30)
            }
            body.contains("000106000") -> { log += "control"; "fef0" + "00".repeat(30) }
            else -> { log += "unknown"; "00" }
        }
    }

    private fun listResponse(): String =
        "00".repeat(45) + schedules.sortedBy { it.id }.joinToString("") { r ->
            "%02x".format(r.id) + "01" + (if (r.recurring) "fe" else "00") + "01" + le(r.start) + le(r.end) + "00".repeat(4)
        } + "00".repeat(4)

    fun addUserSchedule(id: Int, start: String, end: String, day: LocalDate) {
        fun ts(t: String) = day.atTime(java.time.LocalTime.parse(t)).atZone(zone).toEpochSecond()
        schedules += Row(id, true, ts(start), ts(end))
    }

    override fun close() = server.close()
}

class FakeSwitcherTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    private val fake = FakeSwitcher(zone)
    @AfterTest fun stop() = fake.close()

    private fun client() = SwitcherClient("127.0.0.1", "ab1c2d", "18", zone, 2000, fake.port)

    private fun day(d: String, morning: Pair<String, Int>?, evening: Pair<String, Int>?): DayPlan {
        fun sp(session: String, x: Pair<String, Int>?) = if (x == null) SessionPlan(d, session, "", listOf("a"), 0)
            else SessionPlan(d, session, "", listOf("a"), x.second, x.first, fmtTime((toMin(x.first) + x.second).toDouble()), toMin(x.first))
        return DayPlan(d, 15.0, listOf(sp("morning", morning), sp("evening", evening)), 0.0, 0.0)
    }

    @Test fun stateAndManualControl() {
        client().use { c ->
            val st = c.state()
            assertTrue(st.on); assertEquals(2410, st.watts)
            c.turnOn(45); c.turnOff()
        }
        assertEquals(0, fake.badPackets)
        assertEquals(listOf("login", "state", "login", "control", "login", "control"), fake.log)
    }

    @Test fun fullSyncThenReplanKeepsUsersOwnSchedule() {
        val today = LocalDate.now(zone)
        fake.addUserSchedule(0, "07:00", "07:30", today)                 // the user's own, recurring
        val d0 = today.toString(); val d1 = today.plusDays(1).toString()
        val now = today.atTime(21, 0)

        // 1st sync: tomorrow morning + evening are within 24 h
        val first = client().use { ScheduleSync.apply(it, listOf(day(d0, null, "17:20" to 150), day(d1, "05:20" to 60, "17:20" to 150)), now, emptyList()) }
        assertTrue(first.verified)
        assertEquals(listOf("05:20" to "06:20", "17:20" to "19:50"), first.programmed.map { it.start to it.end })
        assertEquals(3, fake.schedules.size)

        // 2nd sync, forecast changed: evening needs less heating → only that one is replaced
        val second = client().use { ScheduleSync.apply(it, listOf(day(d0, null, null), day(d1, "05:20" to 60, "18:00" to 110)), now.plusMinutes(30), first.programmed) }
        assertTrue(second.verified)
        assertEquals(listOf("05:20" to "06:20", "18:00" to "19:50"), second.programmed.map { it.start to it.end }.sortedBy { it.first })
        assertTrue(fake.schedules.any { it.id == 0 && it.recurring }, "the user's schedule must survive")
        assertEquals(3, fake.schedules.size)
        assertEquals(0, fake.badPackets)
        assertEquals(1, fake.log.count { it.startsWith("delete") })
    }

    @Test fun nothingToDoMeansNoWrites() {
        val today = LocalDate.now(zone)
        val out = client().use { ScheduleSync.apply(it, listOf(day(today.toString(), null, null)), LocalDateTime.now(zone), emptyList<Programmed>()) }
        assertTrue(out.verified && out.programmed.isEmpty())
        assertTrue(fake.log.none { it.startsWith("create") || it.startsWith("delete") })
    }
}
