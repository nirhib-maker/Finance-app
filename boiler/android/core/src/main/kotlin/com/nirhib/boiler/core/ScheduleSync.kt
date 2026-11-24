package com.nirhib.boiler.core

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Decides how to bring the switch's built-in schedules in line with the plan.
 *
 * The switch runs one-time schedules by itself, so the phone only needs to be at home (on the Wi-Fi)
 * now and then: each sync writes every heating window of the next 24 hours.
 *
 * Safety: only schedules this app created are ever deleted. They are remembered as id + start + end,
 * and a schedule is ours only if all three still match and it is one-time (the switch may reuse a
 * slot id for a schedule you created yourself in the Switcher app).
 */
object ScheduleSync {
    const val MAX_SLOTS = 8

    data class Slot(val start: String, val end: String, val date: String, val session: String) {
        fun sameTimes(s: SwitcherProtocol.Schedule) = s.start == start && s.end == end
    }
    data class Owned(val id: String, val start: String, val end: String)
    data class Plan(val delete: List<String>, val create: List<Slot>, val keep: List<Owned>, val skipped: List<Slot>)

    private fun window(day: String, startMin: Int, minutes: Int): Pair<LocalDateTime, LocalDateTime> {
        val start = LocalDate.parse(day).atStartOfDay().plusMinutes(startMin.toLong())
        return start to start.plusMinutes(minutes.toLong())
    }

    /** Heating windows that start within the next 24 hours (not in the next 2 minutes), plus the one running now. */
    fun upcoming(days: List<DayPlan>, now: LocalDateTime): Pair<List<Slot>, List<Slot>> {
        val next = mutableListOf<Slot>(); val running = mutableListOf<Slot>()
        for (d in days) for (s in d.sessions) {
            if (s.minutes <= 0 || s.startMin == null || s.start == null || s.end == null) continue
            val (a, b) = window(d.date, s.startMin, s.minutes)
            val slot = Slot(s.start, s.end, d.date, s.session)
            if (!a.isAfter(now) && b.isAfter(now)) running += slot
            else if (a.isAfter(now.plusMinutes(2)) && !a.isAfter(now.plusHours(24))) next += slot
        }
        return next to running
    }

    fun plan(days: List<DayPlan>, now: LocalDateTime, existing: List<SwitcherProtocol.Schedule>, owned: List<Owned>): Plan {
        val (next, running) = upcoming(days, now)
        val ours = existing.filter { e -> !e.recurring && owned.any { it.id == e.id && it.start == e.start && it.end == e.end } }
        val keep = mutableListOf<Owned>(); val delete = mutableListOf<String>()
        val satisfied = mutableSetOf<Slot>()
        for (e in ours) {
            val match = (next + running).firstOrNull { it.sameTimes(e) && it !in satisfied }
            if (match != null) { satisfied += match; keep += Owned(e.id, e.start, e.end) }
            else delete += e.id
        }
        val wanted = next.filter { it !in satisfied }
        val free = MAX_SLOTS - (existing.size - delete.size)
        val create = wanted.take(maxOf(0, free))
        return Plan(delete, create, keep, wanted.drop(create.size))
    }

    /** A schedule this app wrote into the switch. */
    data class Programmed(val id: String, val start: String, val end: String, val date: String, val session: String)
    data class Outcome(val state: SwitcherProtocol.State, val programmed: List<Programmed>, val skipped: List<Slot>, val verified: Boolean)

    /**
     * The whole sync against a connected switch: read state and schedules, delete our stale ones,
     * create the missing ones, then read back to learn the ids the switch gave them.
     */
    fun apply(client: SwitcherClient, days: List<DayPlan>, now: LocalDateTime, programmed: List<Programmed>): Outcome {
        val state = client.state()
        val existing = client.schedules()
        val plan = plan(days, now, existing, programmed.map { Owned(it.id, it.start, it.end) })
        plan.delete.forEach { client.deleteSchedule(it) }
        val before = existing.filter { it.id !in plan.delete }
        plan.create.forEach { client.createOneTimeSchedule(it.start, it.end) }
        val created = if (plan.create.isEmpty()) emptyList() else identify(before, client.schedules(), plan.create)
        val previous = programmed.associateBy { it.id }
        val left = plan.create.toMutableList()
        val result = plan.keep.mapNotNull { previous[it.id] } + created.map { c ->
            val slot = left.first { it.start == c.start && it.end == c.end }.also { left.remove(it) }
            Programmed(c.id, c.start, c.end, slot.date, slot.session)
        }
        return Outcome(state, result, plan.skipped, created.size == plan.create.size)
    }

    /** After creating: identify the new schedules' ids (new one-time schedules with the created times). */
    fun identify(before: List<SwitcherProtocol.Schedule>, after: List<SwitcherProtocol.Schedule>, created: List<Slot>): List<Owned> {
        val beforeIds = before.map { it.id }.toSet()
        val left = created.toMutableList()
        return after.filter { it.id !in beforeIds && !it.recurring }.mapNotNull { s ->
            left.firstOrNull { it.sameTimes(s) }?.let { left.remove(it); Owned(s.id, s.start, s.end) }
        }
    }
}
