package com.nirhib.boiler.core

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// Local-network protocol of Switcher water-heater switches (V2, V4, Touch, Mini), ported from the
// open-source aioswitcher library (api/packets.py, device/tools.py, api/messages.py, bridge.py).
// SwitcherGoldenTest checks the bytes against packets produced by aioswitcher itself.

object SwitcherProtocol {
    const val TCP_PORT = 9957
    val UDP_PORTS = listOf(20002, 10002)

    private const val PAD_72_ZEROS = "000000000000000000000000000000000000000000000000000000000000000000000000"
    private const val REQUEST_FORMAT = "%s340001000000000000000000%s00000000000000000000f0fe"
    private const val LOGIN = "fef052000232a100" + "00000000" + "340001000000000000000000%s00000000000000000000f0fe" + "%s" + PAD_72_ZEROS + "00"
    private const val GET_STATE = "fef0300002320103" + REQUEST_FORMAT + "%s00"
    private const val CONTROL = "fef05d0002320102" + REQUEST_FORMAT + "%s" + PAD_72_ZEROS + "000106000%s00%s"
    private const val GET_SCHEDULES = "fef0570002320102" + REQUEST_FORMAT + "%s" + PAD_72_ZEROS + "00060000"
    private const val DELETE_SCHEDULE = "fef0580002320102" + REQUEST_FORMAT + "%s" + PAD_72_ZEROS + "000801000%s"
    private const val CREATE_SCHEDULE = "fef0630002320102" + REQUEST_FORMAT + "%s" + PAD_72_ZEROS + "00030c00ff%s"

    val WATER_HEATER_TYPES = mapOf("030f" to "mini", "030b" to "touch", "01a7" to "v2esp", "01a1" to "v2qual", "0317" to "v4")

    // ---- encoding helpers ----
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    /** pack("<I", v) as hex */
    fun le32(v: Long) = "%02x%02x%02x%02x".format(v and 0xff, (v shr 8) and 0xff, (v shr 16) and 0xff, (v shr 24) and 0xff)
    fun readLe32(h: String) = (h.substring(6, 8) + h.substring(4, 6) + h.substring(2, 4) + h.substring(0, 2)).toLong(16)

    /** binascii.crc_hqx(data, init): CRC-16 CCITT, polynomial 0x1021 (init 0 = CRC-16/XMODEM) */
    fun crcHqx(data: ByteArray, init: Int = 0): Int {
        var crc = init
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xff) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xffff
        }
        return crc
    }
    // aioswitcher signs with crc_hqx(packet, 0x1021): 0x1021 is the *starting value* there
    private fun crcLeHex(data: ByteArray): String { val c = crcHqx(data, 0x1021); return "%02x%02x".format(c and 0xff, c shr 8) }

    fun sign(hexPacket: String): String {
        val packetCrc = crcLeHex(unhex(hexPacket))
        val keyCrc = crcLeHex(unhex(packetCrc + "30".repeat(32)))
        return hexPacket + packetCrc + keyCrc
    }

    fun setMessageLength(message: String): String {
        val length = message.length / 2 + 4
        return "fef0" + "%02x%02x".format(length and 0xff, (length shr 8) and 0xff) + message.substring(8)
    }

    fun finalize(packet: String) = sign(setMessageLength(packet))

    fun timestampHex(epochSeconds: Long = Instant.now().epochSecond) = le32(epochSeconds)
    fun minutesHex(minutes: Int) = le32(minutes * 60L)

    /** aioswitcher time_to_hexadecimal_timestamp: today's date at HH:MM in local time. */
    fun timeHex(hhmm: String, date: LocalDate, zone: ZoneId) =
        le32(date.atTime(LocalTime.parse(hhmm)).atZone(zone).toEpochSecond())

    // ---- packets (unsigned; pass through finalize) ----
    fun login(ts: String, deviceKey: String) = LOGIN.format(ts, deviceKey)
    fun getState(session: String, ts: String, deviceId: String) = GET_STATE.format(session, ts, deviceId)
    fun control(session: String, ts: String, deviceId: String, on: Boolean, minutes: Int) =
        CONTROL.format(session, ts, deviceId, if (on) "1" else "0", if (minutes > 0) minutesHex(minutes) else "00000000")
    fun getSchedules(session: String, ts: String, deviceId: String) = GET_SCHEDULES.format(session, ts, deviceId)
    fun deleteSchedule(session: String, ts: String, deviceId: String, scheduleId: String) =
        DELETE_SCHEDULE.format(session, ts, deviceId, scheduleId)
    /** One-time (non recurring) schedule. */
    fun createSchedule(session: String, ts: String, deviceId: String, startHex: String, endHex: String) =
        CREATE_SCHEDULE.format(session, ts, deviceId, "0100" + "01" + startHex + endHex)

    // ---- responses ----
    fun sessionId(response: ByteArray) = hex(response).substring(16, 24)

    data class State(val on: Boolean, val watts: Int, val timeLeftSec: Long, val autoShutdownSec: Long)
    fun parseState(response: ByteArray): State {
        val h = hex(response)
        return State(
            on = h.substring(150, 152) == "01",
            watts = (h.substring(156, 158) + h.substring(154, 156)).toInt(16),
            timeLeftSec = readLe32(h.substring(178, 186)),
            autoShutdownSec = readLe32(h.substring(194, 202)),
        )
    }

    data class Schedule(val id: String, val enabled: Boolean, val recurring: Boolean, val start: String, val end: String)
    fun parseSchedules(response: ByteArray, zone: ZoneId): List<Schedule> {
        val h = hex(response)
        if (h.length <= 98) return emptyList()
        val data = h.substring(90, h.length - 8)
        return data.chunked(32).filter { it.length == 32 }.map { s ->
            Schedule(
                id = s.substring(0, 2).toInt(16).toString(),
                enabled = s.substring(2, 4).toInt(16) == 1,
                recurring = s.substring(4, 6) != "00",
                start = localHhmm(readLe32(s.substring(8, 16)), zone),
                end = localHhmm(readLe32(s.substring(16, 24)), zone),
            )
        }
    }
    private fun localHhmm(epoch: Long, zone: ZoneId) =
        Instant.ofEpochSecond(epoch).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }

    data class Discovered(val type: String, val id: String, val key: String, val ip: String, val name: String, val on: Boolean, val watts: Int)
    /** Parse a UDP broadcast datagram. Returns null for anything that is not a Switcher water heater. */
    fun parseDiscovery(msg: ByteArray, ip: String): Discovered? {
        if (msg.size != 165) return null
        val h = hex(msg)
        if (!h.startsWith("fef0")) return null
        val type = WATER_HEATER_TYPES[hex(msg.copyOfRange(74, 76))] ?: return null
        val on = h.substring(266, 268) == "01"
        val p = h.substring(270, 278)
        return Discovered(
            type = type, id = h.substring(36, 42), key = h.substring(80, 82), ip = ip,
            name = String(msg.copyOfRange(42, 74), Charsets.UTF_8).trimEnd('\u0000'),
            on = on, watts = if (on) (p.substring(2, 4) + p.substring(0, 2)).toInt(16) else 0,
        )
    }
}

/** One TCP session with the switch: login once per command, exactly like aioswitcher does. */
class SwitcherClient(
    private val ip: String, private val deviceId: String, private val deviceKey: String,
    private val zone: ZoneId = ZoneId.systemDefault(), timeoutMs: Int = 5000, port: Int = SwitcherProtocol.TCP_PORT,
) : Closeable {
    private val socket = Socket().apply { connect(InetSocketAddress(ip, port), timeoutMs); soTimeout = timeoutMs }
    private val out = socket.getOutputStream()
    private val inp = socket.getInputStream()

    private fun send(hexPacket: String): ByteArray {
        out.write(SwitcherProtocol.unhex(SwitcherProtocol.finalize(hexPacket))); out.flush()
        val buf = ByteArray(1024)
        val n = inp.read(buf)
        if (n <= 0) throw IllegalStateException("no response from switcher")
        return buf.copyOf(n)
    }

    private fun login(): Pair<String, String> {
        val ts = SwitcherProtocol.timestampHex()
        val resp = send(SwitcherProtocol.login(ts, deviceKey))
        if (resp.size < 12) throw IllegalStateException("login failed")
        return ts to SwitcherProtocol.sessionId(resp)
    }

    fun state(): SwitcherProtocol.State {
        val (ts, s) = login()
        return SwitcherProtocol.parseState(send(SwitcherProtocol.getState(s, ts, deviceId)))
    }

    fun turnOn(minutes: Int) { val (ts, s) = login(); send(SwitcherProtocol.control(s, ts, deviceId, true, minutes)) }
    fun turnOff() { val (ts, s) = login(); send(SwitcherProtocol.control(s, ts, deviceId, false, 0)) }

    fun schedules(): List<SwitcherProtocol.Schedule> {
        val (ts, s) = login()
        return SwitcherProtocol.parseSchedules(send(SwitcherProtocol.getSchedules(s, ts, deviceId)), zone)
    }

    fun deleteSchedule(id: String) {
        val (ts, s) = login()
        send(SwitcherProtocol.deleteSchedule(s, ts, deviceId, id))
    }

    fun createOneTimeSchedule(start: String, end: String, today: LocalDate = LocalDate.now(zone)) {
        val (ts, s) = login()
        send(SwitcherProtocol.createSchedule(s, ts, deviceId,
            SwitcherProtocol.timeHex(start, today, zone), SwitcherProtocol.timeHex(end, today, zone)))
    }

    override fun close() = socket.close()
}
