package com.example.tasksync.data.calendar

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.tasksync.data.local.entity.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

data class GoogleDeviceCalendar(val id: Long, val displayName: String, val accountName: String)

/** One-way mirror from dated TaskSync tasks into a Google calendar configured on this Android device. */
class CalendarTaskSync(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val selectedCalendarId: Long?
        get() = preferences.getLong(KEY_CALENDAR_ID, INVALID_CALENDAR_ID).takeIf { it != INVALID_CALENDAR_ID }

    val selectedCalendarLabel: String?
        get() = preferences.getString(KEY_CALENDAR_LABEL, null)

    fun hasCalendarPermissions(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    suspend fun availableGoogleCalendars(): List<GoogleDeviceCalendar> = withContext(Dispatchers.IO) {
        check(hasCalendarPermissions()) { "Calendar permission is required to choose a Google calendar." }
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME
        )
        val selection = "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(GOOGLE_ACCOUNT_TYPE, CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val result = mutableListOf<GoogleDeviceCalendar>()
        appContext.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accountIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            while (cursor.moveToNext()) {
                result += GoogleDeviceCalendar(
                    id = cursor.getLong(idIndex),
                    displayName = cursor.getString(nameIndex).orEmpty(),
                    accountName = cursor.getString(accountIndex).orEmpty()
                )
            }
        }
        result
    }

    fun connect(calendar: GoogleDeviceCalendar) {
        preferences.edit {
            putLong(KEY_CALENDAR_ID, calendar.id)
            putString(KEY_CALENDAR_LABEL, "${calendar.displayName} · ${calendar.accountName}")
        }
    }

    fun disconnect() {
        preferences.edit {
            remove(KEY_CALENDAR_ID)
            remove(KEY_CALENDAR_LABEL)
        }
    }

    suspend fun syncTask(task: TaskEntity): TaskEntity = withContext(Dispatchers.IO) {
        val destinationCalendarId = selectedCalendarId ?: return@withContext task
        check(hasCalendarPermissions()) { "Calendar permission was removed. Reconnect Google Calendar to resume syncing." }

        if (task.status == COMPLETED_STATUS || task.dueDate.isNullOrBlank()) {
            deleteEvent(task)
            return@withContext task.copy(googleCalendarId = null, googleCalendarEventId = null)
        }

        val startDate = LocalDate.parse(task.dueDate)
        val values = eventValues(task, destinationCalendarId, startDate)
        val existingEventId = task.googleCalendarEventId
        if (existingEventId != null && task.googleCalendarId == destinationCalendarId) {
            val updated = appContext.contentResolver.update(
                CalendarContract.Events.CONTENT_URI,
                values,
                "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ?",
                arrayOf(existingEventId.toString(), destinationCalendarId.toString())
            )
            if (updated > 0) return@withContext task.copy(googleCalendarId = destinationCalendarId)
        }

        if (existingEventId != null) deleteEvent(task)
        val uri = appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Android did not create the Google Calendar event.")
        val newEventId = uri.lastPathSegment?.toLongOrNull()
            ?: error("Android created a calendar event without returning its ID.")
        task.copy(googleCalendarId = destinationCalendarId, googleCalendarEventId = newEventId)
    }

    suspend fun deleteEvent(task: TaskEntity) = withContext(Dispatchers.IO) {
        val eventId = task.googleCalendarEventId ?: return@withContext
        if (!hasCalendarPermissions()) throw SecurityException("Calendar permission is required to remove the linked event.")
        val calendarId = task.googleCalendarId
        val selection = if (calendarId == null) "${CalendarContract.Events._ID} = ?" else
            "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ?"
        val args = if (calendarId == null) arrayOf(eventId.toString()) else arrayOf(eventId.toString(), calendarId.toString())
        appContext.contentResolver.delete(CalendarContract.Events.CONTENT_URI, selection, args)
        Unit
    }

    private fun eventValues(task: TaskEntity, calendarId: Long, date: LocalDate): ContentValues {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, task.title)
            put(CalendarContract.Events.DESCRIPTION, task.description.orEmpty())
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
            put(CalendarContract.Events.HAS_ALARM, 0)
        }
        val time = task.dueTime?.takeIf(String::isNotBlank)?.let { LocalTime.parse(it) }
        if (time == null) {
            values.put(CalendarContract.Events.ALL_DAY, 1)
            values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            values.put(CalendarContract.Events.DTSTART, date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            values.put(CalendarContract.Events.DTEND, date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        } else {
            val zone = ZoneId.systemDefault()
            val start = date.atTime(time).atZone(zone)
            values.put(CalendarContract.Events.ALL_DAY, 0)
            values.put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            values.put(CalendarContract.Events.EVENT_END_TIMEZONE, zone.id)
            values.put(CalendarContract.Events.DTSTART, start.toInstant().toEpochMilli())
            values.put(CalendarContract.Events.DTEND, start.plusMinutes(DEFAULT_EVENT_MINUTES).toInstant().toEpochMilli())
        }
        return values
    }

    companion object {
        private const val PREFERENCES = "google_calendar_sync"
        private const val KEY_CALENDAR_ID = "calendar_id"
        private const val KEY_CALENDAR_LABEL = "calendar_label"
        private const val INVALID_CALENDAR_ID = -1L
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val COMPLETED_STATUS = "completed"
        private const val DEFAULT_EVENT_MINUTES = 30L
    }
}
