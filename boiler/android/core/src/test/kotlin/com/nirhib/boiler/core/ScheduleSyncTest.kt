package com.nirhib.boiler.core

import com.nirhib.boiler.core.ScheduleSync.Owned
import com.nirhib.boiler.core.SwitcherProtocol.Schedule
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleSyncTest {
    private fun s(session: String, start: String?, end: String?, startMin: Int?, minutes: Int) =
        SessionPlan("", session, "", listOf("x"), minutes, start, end, startMin)

    // Two days: morning 05:20–06:20, evening 17:20–19:50
    private val days = listOf("2026-12-01", "2026-12-02").map { d ->
        DayPlan(d, 15.0, listOf(
            s("morning", "05:20", "06:20", 320, 60).copy(date = d),
            s("evening", "17:20", "19:50", 1040, 150).copy(date = d),
        ), 0.0, 0.0)
    }

    @Test fun programsTheNext24Hours() {
        val p = ScheduleSync.plan(days, LocalDateTime.parse("2026-12-01T21:00"), emptyList(), emptyList())
        assertEquals(listOf("2026-12-02 morning", "2026-12-02 evening"), p.create.map { "${it.date} ${it.session}" })
        assertTrue(p.delete.isEmpty())
    }

    @Test fun keepsOwnSchedulesThatStillMatchAndReplacesChangedOnes() {
        val existing = listOf(
            Schedule("1", true, false, "05:20", "06:20"),   // ours, still right
            Schedule("2", true, false, "17:00", "19:50"),   // ours, plan changed
            Schedule("4", true, true, "07:00", "08:00"),    // the user's own, recurring
        )
        val owned = listOf(Owned("1", "05:20", "06:20"), Owned("2", "17:00", "19:50"))
        val p = ScheduleSync.plan(days, LocalDateTime.parse("2026-12-01T21:00"), existing, owned)
        assertEquals(listOf("2"), p.delete)
        assertEquals(listOf("1"), p.keep.map { it.id })
        assertEquals(listOf("17:20"), p.create.map { it.start })
    }

    @Test fun neverDeletesTheUsersScheduleEvenIfItReusedOurSlotId() {
        val existing = listOf(Schedule("2", true, false, "12:00", "13:00"))  // slot 2 now belongs to the user
        val p = ScheduleSync.plan(days, LocalDateTime.parse("2026-12-01T21:00"), existing, listOf(Owned("2", "17:00", "19:50")))
        assertTrue(p.delete.isEmpty())
    }

    @Test fun doesNotTouchTheRunningWindow() {
        val existing = listOf(Schedule("3", true, false, "17:20", "19:50"))
        val p = ScheduleSync.plan(days, LocalDateTime.parse("2026-12-01T18:00"), existing, listOf(Owned("3", "17:20", "19:50")))
        assertTrue(p.delete.isEmpty())
        assertEquals(listOf("3"), p.keep.map { it.id })
        assertEquals(listOf("2026-12-02 morning"), p.create.map { "${it.date} ${it.session}" }) // tomorrow evening is > 24h away
    }

    @Test fun respectsTheEightSlotLimit() {
        val full = (0 until 7).map { Schedule("$it", true, true, "0$it:00", "0$it:30") }
        val p = ScheduleSync.plan(days, LocalDateTime.parse("2026-12-01T21:00"), full, emptyList())
        assertEquals(1, p.create.size)
        assertEquals(1, p.skipped.size)
    }

    @Test fun identifiesCreatedIds() {
        val before = listOf(Schedule("4", true, true, "07:00", "08:00"))
        val after = before + Schedule("0", true, false, "05:20", "06:20") + Schedule("1", true, false, "17:20", "19:50")
        val created = listOf(ScheduleSync.Slot("05:20", "06:20", "d", "morning"), ScheduleSync.Slot("17:20", "19:50", "d", "evening"))
        assertEquals(listOf(Owned("0", "05:20", "06:20"), Owned("1", "17:20", "19:50")), ScheduleSync.identify(before, after, created))
    }
}
