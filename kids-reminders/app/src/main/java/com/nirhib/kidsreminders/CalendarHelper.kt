package com.nirhib.kidsreminders

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.nirhib.kidsreminders.core.ExtractedItem
import com.nirhib.kidsreminders.core.ItemType
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

data class CalendarInfo(val id: Long, val name: String, val account: String, val isPrimary: Boolean)

/**
 * כתיבה ליומן של המכשיר. כשבוחרים יומן של חשבון Google, האירועים מסתנכרנים אוטומטית
 * ל-Google Calendar – בלי צורך בהתחברות או מפתחות נוספים.
 */
object CalendarHelper {

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun listCalendars(context: Context): List<CalendarInfo> {
        if (!hasPermission(context)) return emptyList()
        val result = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(4) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                result.add(CalendarInfo(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3) == 1))
            }
        }
        return result.sortedWith(compareByDescending<CalendarInfo> { it.isPrimary }.thenBy { it.account })
    }

    /** בוחר יומן ברירת מחדל: היומן הראשי של חשבון Google. */
    fun ensureCalendar(context: Context, settings: Settings): Long {
        if (settings.calendarId >= 0) return settings.calendarId
        val cal = listCalendars(context).firstOrNull { it.isPrimary && it.account.contains("@") }
            ?: listCalendars(context).firstOrNull()
            ?: return -1
        settings.calendarId = cal.id
        settings.calendarName = "${cal.name} (${cal.account})"
        return cal.id
    }

    /** יוצר אירוע ומחזיר את ה-id שלו, או -1 אם נכשל. */
    fun insert(context: Context, settings: Settings, item: ExtractedItem): Long {
        if (!hasPermission(context)) return -1
        val date = item.date ?: return -1
        val calId = ensureCalendar(context, settings)
        if (calId < 0) return -1

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, item.calendarTitle())
            put(CalendarContract.Events.DESCRIPTION, item.details + "\n\n(נוסף אוטומטית מקבוצת הוואטסאפ)")
            put(CalendarContract.Events.HAS_ALARM, 1)
        }

        val reminders: List<Int>
        if (item.time != null) {
            val zone = ZoneId.systemDefault()
            val start = date.atTime(item.time).atZone(zone)
            values.put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
            values.put(CalendarContract.Events.DTEND, start.plusHours(1).toInstant().toEpochMilli())
            values.put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            // תזכורת בערב הקודם ב-20:00 + שעה לפני
            val eveningBefore = date.minusDays(1).atTime(20, 0).atZone(zone)
            val minutesFromEvening = Duration.between(eveningBefore, start).toMinutes().toInt()
            reminders = listOf(60, minutesFromEvening)
        } else {
            // אירוע של יום שלם: לפי החוזה של Android הזמנים ב-UTC
            val startUtc = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            values.put(CalendarContract.Events.DTSTART, startUtc)
            values.put(CalendarContract.Events.DTEND, startUtc + 86_400_000L)
            values.put(CalendarContract.Events.ALL_DAY, 1)
            values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getTimeZone("UTC").id)
            // דקות לפני חצות של אותו יום: 240 = 20:00 בערב הקודם; 420 = 07:00 בבוקר שלפני
            reminders = when (item.type) {
                ItemType.EXAM -> listOf(240, 3 * 1440 - minutesOf(LocalTime.of(18, 0)))
                else -> listOf(240)
            }
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return -1
        val eventId = ContentUris.parseId(uri)
        for (m in reminders.filter { it > 0 }.distinct()) {
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, m)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
        return eventId
    }

    fun delete(context: Context, eventId: Long) {
        if (eventId < 0 || !hasPermission(context)) return
        context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null,
        )
    }

    private fun minutesOf(t: LocalTime) = t.hour * 60 + t.minute
}
