package com.nirhib.boiler.core

import com.fasterxml.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun resource(name: String): JsonNode =
    Settings.mapper.readTree(PlannerGoldenTest::class.java.getResourceAsStream("/$name")!!.readAllBytes())

/** The planner port must give exactly what boiler/js/model.js gives (tools/planner_vectors.mjs). */
class PlannerGoldenTest {
    @Test fun matchesModelJs() {
        val cases = resource("planner_vectors.json")
        assertTrue(cases.size() >= 6)
        for (c in cases) {
            val name = c["name"].asText()
            val s = Settings.mapper.treeToValue(c["settings"], Settings::class.java)
            val plan = planDays(parseOpenMeteo(c["weather"]), s, c["today"].asText())
            val expected = c["plan"]
            assertEquals(expected.size(), plan.size, name)
            for ((i, e) in expected.withIndex()) {
                val d = plan[i]
                assertEquals(e["date"].asText(), d.date, name)
                assertEquals(e["coldTemp"].asDouble(), d.coldTemp, name)
                assertEquals(e["kwh"].asDouble(), d.kwh, "$name ${d.date} kwh")
                assertEquals(e["cost"].asDouble(), d.cost, "$name ${d.date} cost")
                for ((j, es) in e["sessions"].withIndex()) {
                    val p = d.sessions[j]
                    val where = "$name ${d.date} ${p.session}"
                    assertEquals(es["minutes"].asInt(), p.minutes, "$where minutes")
                    assertEquals(es["users"].map { it.asText() }, p.users, where)
                    if (es.has("targetTemp")) {
                        assertEquals(es["targetTemp"].asLong(), p.targetTemp, "$where target")
                        assertEquals(es["sunTemp"].asLong(), p.sunTemp, "$where sunTemp")
                        assertEquals(es["sunKwh"].asDouble(), p.sunKwh, "$where sunKwh")
                        assertEquals(es["kwh"].asDouble(), p.kwh, "$where kwh")
                        assertEquals(es["cost"].asDouble(), p.cost, "$where cost")
                        assertEquals(es["warnings"].map { it.asText() }, p.warnings, "$where warnings")
                    }
                    if (es["start"]?.isTextual == true) {
                        assertEquals(es["start"].asText(), p.start, "$where start")
                        assertEquals(es["end"].asText(), p.end, "$where end")
                        assertEquals(es["startMin"].asInt(), p.startMin, "$where startMin")
                    } else assertNull(p.start, where)
                }
            }
        }
    }

    @Test fun settingsFromAppJsonWithUnknownFieldsAndSwitcher() {
        val s = Settings.fromJson("""{"tank":{"liters":120,"heaterKw":3,"maxTemp":60,"lossWPerDeg":2},"future":1,
            "switcher":{"type":"touch","ip":"10.0.0.7","id":"ab12cd","key":"1a","baseUrl":"x"}}""")
        assertEquals(120.0, s.tank.liters)
        assertEquals(3, s.members.size)
        assertTrue(s.switcher.configured)
    }
}

/** The protocol port must produce aioswitcher's exact bytes (tools/switcher_vectors.py). */
class SwitcherGoldenTest {
    private val v = resource("switcher_vectors.json")
    private val ts = "a1b2c3d4"; private val session = "0badf00d"; private val id = "ab1c2d"
    private fun t(key: String) = v[key].asText()

    @Test fun crcMatchesBinasciiCrcHqx() {
        assertEquals(0x31c3, SwitcherProtocol.crcHqx("123456789".toByteArray())) // CRC-16/XMODEM check value
    }

    @Test fun packets() {
        val p = SwitcherProtocol
        assertEquals(t("minutes90"), p.minutesHex(90))
        assertEquals(t("login"), p.finalize(p.login(ts, "18")))
        assertEquals(t("getState"), p.finalize(p.getState(session, ts, id)))
        assertEquals(t("on90"), p.finalize(p.control(session, ts, id, true, 90)))
        assertEquals(t("off"), p.finalize(p.control(session, ts, id, false, 0)))
        assertEquals(t("getSchedules"), p.finalize(p.getSchedules(session, ts, id)))
        assertEquals(t("delete3"), p.finalize(p.deleteSchedule(session, ts, id, "3")))
        assertEquals(t("create"), p.finalize(p.createSchedule(session, ts, id, t("startHex"), t("endHex"))))
    }

    @Test fun scheduleTimesEncodeLikePython() {
        val zone = ZoneId.of("Asia/Jerusalem"); val date = LocalDate.parse(t("date"))
        assertEquals(t("startHex"), SwitcherProtocol.timeHex("17:20", date, zone))
        assertEquals(t("endHex"), SwitcherProtocol.timeHex("19:50", date, zone))
    }

    @Test fun stateResponse() {
        val st = SwitcherProtocol.parseState(SwitcherProtocol.unhex(t("stateResponse")))
        val e = v["state"]
        assertEquals(e["on"].asBoolean(), st.on)
        assertEquals(e["watts"].asInt(), st.watts)
        assertEquals(1800, st.timeLeftSec)
        assertEquals(6960, st.autoShutdownSec)
    }

    @Test fun schedulesResponse() {
        val got = SwitcherProtocol.parseSchedules(SwitcherProtocol.unhex(t("schedulesResponse")), ZoneId.of("Asia/Jerusalem")).sortedBy { it.id }
        val e = v["schedules"]
        assertEquals(e.size(), got.size)
        for ((i, x) in e.withIndex()) {
            assertEquals(x["id"].asText(), got[i].id)
            assertEquals(x["recurring"].asBoolean(), got[i].recurring)
            assertEquals(x["start"].asText(), got[i].start)
            assertEquals(x["end"].asText(), got[i].end)
        }
    }

    @Test fun discovery() {
        val d = SwitcherProtocol.parseDiscovery(SwitcherProtocol.unhex(t("discovery")), "192.168.1.50")!!
        val e = v["discovered"]
        assertEquals("v4", d.type)
        assertEquals(e["id"].asText(), d.id)
        assertEquals(e["key"].asText(), d.key)
        assertEquals(e["name"].asText(), d.name)
        assertEquals(e["on"].asBoolean(), d.on)
        assertEquals(e["watts"].asInt(), d.watts)
        assertEquals("192.168.1.50", d.ip)
        assertNull(SwitcherProtocol.parseDiscovery(ByteArray(100), "x"))
    }
}
