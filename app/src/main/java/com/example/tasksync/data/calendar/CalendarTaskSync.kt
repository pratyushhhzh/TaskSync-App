package com.example.tasksync.data.calendar

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.provider.CalendarContract
import android.os.Handler
import android.os.Looper
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
data class ExternalCalendarEvent(
    val id: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZone: String?,
)
data class PendingCalendarEventDelete(val taskId: String, val calendarId: Long, val eventId: Long)

/** One-way mirror from dated TaskSync tasks into a Google calendar configured on this Android device. */
class CalendarTaskSync(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val selectedCalendarId: Long?
        get() = preferences.getLong(KEY_CALENDAR_ID, INVALID_CALENDAR_ID).takeIf { it != INVALID_CALENDAR_ID }

    val selectedCalendarLabel: String?
        get() = preferences.getString(KEY_CALENDAR_LABEL, null)

    val lastSuccessfulSyncMillis: Long?
        get() = preferences.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0L }
    val lastSyncErrorCode: String?
        get() = preferences.getString(KEY_LAST_SYNC_ERROR, null)
    val defaultTimedEventDurationMinutes: Int
        get() = preferences.getInt(KEY_EVENT_DURATION, DEFAULT_EVENT_MINUTES.toInt()).coerceIn(15, 180)

    fun setDefaultTimedEventDurationMinutes(minutes: Int) {
        require(minutes in 15..180)
        preferences.edit { putInt(KEY_EVENT_DURATION, minutes) }
    }

    fun hasCalendarPermissions(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    suspend fun eventsForDate(date: LocalDate, taskSyncEventIds: Set<Long> = emptySet()): List<ExternalCalendarEvent> = withContext(Dispatchers.IO) {
        val calendarId = selectedCalendarId ?: return@withContext emptyList()
        check(hasCalendarPermissions()) { "Calendar permission is required to read Google Calendar events." }
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri: Uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toString())
            .appendPath(end.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_TIMEZONE,
            CalendarContract.Instances.CUSTOM_APP_PACKAGE,
        )
        val events = mutableListOf<ExternalCalendarEvent>()
        appContext.contentResolver.query(
            uri,
            projection,
            "${CalendarContract.Instances.CALENDAR_ID} = ?",
            arrayOf(calendarId.toString()),
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val zoneIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_TIMEZONE)
            val ownerIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CUSTOM_APP_PACKAGE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                if (id in taskSyncEventIds || cursor.getString(ownerIndex) == appContext.packageName) continue
                events += ExternalCalendarEvent(
                    id = id,
                    title = cursor.getString(titleIndex).orEmpty(),
                    startMillis = cursor.getLong(beginIndex),
                    endMillis = cursor.getLong(endIndex),
                    allDay = cursor.getInt(allDayIndex) != 0,
                    timeZone = cursor.getString(zoneIndex),
                )
            }
        }
        events
    }

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
            remove(KEY_LAST_SYNC)
            remove(KEY_LAST_SYNC_ERROR)
        }
    }

    fun recordSyncFailure(failure: Throwable) {
        val category = if (failure is SecurityException) "PERMISSION_REQUIRED" else "PROVIDER_FAILURE"
        preferences.edit { putString(KEY_LAST_SYNC_ERROR, category) }
    }

    fun recordSyncSuccess() { preferences.edit { putLong(KEY_LAST_SYNC, System.currentTimeMillis()); remove(KEY_LAST_SYNC_ERROR) } }

    fun recordPendingDelete(taskId: String, calendarId: Long, eventId: Long) {
        val key = "$taskId|$calendarId|$eventId"
        val pending = preferences.getStringSet(KEY_PENDING_DELETES, emptySet()).orEmpty().toMutableSet()
        pending += key
        preferences.edit { putStringSet(KEY_PENDING_DELETES, pending) }
    }

    fun pendingDeletes(): List<PendingCalendarEventDelete> = preferences.getStringSet(KEY_PENDING_DELETES, emptySet()).orEmpty().mapNotNull { value ->
        val parts = value.split('|')
        if (parts.size != 3) null else runCatching { PendingCalendarEventDelete(parts[0], parts[1].toLong(), parts[2].toLong()) }.getOrNull()
    }

    fun clearPendingDelete(taskId: String, calendarId: Long, eventId: Long) {
        val pending = preferences.getStringSet(KEY_PENDING_DELETES, emptySet()).orEmpty().toMutableSet()
        pending.remove("$taskId|$calendarId|$eventId")
        preferences.edit { putStringSet(KEY_PENDING_DELETES, pending) }
    }

    suspend fun deleteOwnedEvent(calendarId: Long, eventId: Long, taskId: String) = withContext(Dispatchers.IO) {
        check(hasCalendarPermissions()) { "Calendar permission is required." }
        appContext.contentResolver.delete(
            CalendarContract.Events.CONTENT_URI,
            "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND ${CalendarContract.Events.CUSTOM_APP_URI} = ?",
            arrayOf(eventId.toString(), calendarId.toString(), appContext.packageName, "tasksync://task/${Uri.encode(taskId)}"),
        )
    }

    suspend fun syncTask(task: TaskEntity): TaskEntity = withContext(Dispatchers.IO) {
        val destinationCalendarId = selectedCalendarId ?: return@withContext task
        check(hasCalendarPermissions()) { "Calendar permission was removed. Reconnect Google Calendar to resume syncing." }

        if (task.dueDate.isNullOrBlank()) {
            deleteEvent(task)
            return@withContext task.copy(googleCalendarId = null, googleCalendarEventId = null)
        }
        if (task.status == COMPLETED_STATUS && task.googleCalendarEventId == null) return@withContext task

        val startDate = LocalDate.parse(task.dueDate)
        val values = eventValues(task, destinationCalendarId, startDate)
        val existingEventId = task.googleCalendarEventId ?: findOwnedEventId(destinationCalendarId, task.id)
        if (existingEventId != null && (task.googleCalendarId == destinationCalendarId || task.googleCalendarId == null)) {
            val updated = appContext.contentResolver.update(
                CalendarContract.Events.CONTENT_URI,
                values,
                "${CalendarContract.Events._ID} = ? AND ${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND ${CalendarContract.Events.CUSTOM_APP_URI} = ?",
                arrayOf(existingEventId.toString(), destinationCalendarId.toString(), appContext.packageName, "tasksync://task/${Uri.encode(task.id)}")
            )
            if (updated > 0) {
                preferences.edit { putLong(KEY_LAST_SYNC, System.currentTimeMillis()) }
                return@withContext task.copy(googleCalendarId = destinationCalendarId)
            }
        }

        if (existingEventId != null && task.googleCalendarId != null) deleteEvent(task)
        val uri = appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Android did not create the Google Calendar event.")
        val newEventId = uri.lastPathSegment?.toLongOrNull()
            ?: error("Android created a calendar event without returning its ID.")
        preferences.edit { putLong(KEY_LAST_SYNC, System.currentTimeMillis()) }
        task.copy(googleCalendarId = destinationCalendarId, googleCalendarEventId = newEventId)
    }

    private fun findOwnedEventId(calendarId: Long, taskId: String): Long? {
        val customUri = "tasksync://task/${Uri.encode(taskId)}"
        return appContext.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.CUSTOM_APP_PACKAGE} = ? AND ${CalendarContract.Events.CUSTOM_APP_URI} = ?",
            arrayOf(calendarId.toString(), appContext.packageName, customUri),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    suspend fun deleteEvent(task: TaskEntity) = withContext(Dispatchers.IO) {
        val eventId = task.googleCalendarEventId ?: return@withContext
        if (!hasCalendarPermissions()) throw SecurityException("Calendar permission is required to remove the linked event.")
        val calendarId = task.googleCalendarId ?: return@withContext
        deleteOwnedEvent(calendarId, eventId, task.id)
        Unit
    }

    private fun eventValues(task: TaskEntity, calendarId: Long, date: LocalDate): ContentValues {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, task.title)
            put(CalendarContract.Events.CUSTOM_APP_PACKAGE, appContext.packageName)
            put(CalendarContract.Events.CUSTOM_APP_URI, "tasksync://task/${Uri.encode(task.id)}")
            val taskDescription = task.description.orEmpty()
            put(
                CalendarContract.Events.DESCRIPTION,
                if (task.status == COMPLETED_STATUS) "$COMPLETED_MARKER\n$taskDescription".trim() else taskDescription.removePrefix("$COMPLETED_MARKER\n"),
            )
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
            values.put(CalendarContract.Events.DTEND, start.plusMinutes(defaultTimedEventDurationMinutes.toLong()).toInstant().toEpochMilli())
        }
        return values
    }

    companion object {
        private const val PREFERENCES = "google_calendar_sync"
        private const val KEY_CALENDAR_ID = "calendar_id"
        private const val KEY_CALENDAR_LABEL = "calendar_label"
        private const val KEY_LAST_SYNC = "last_successful_sync"
        private const val KEY_LAST_SYNC_ERROR = "last_sync_error"
        private const val KEY_EVENT_DURATION = "default_event_duration_minutes"
        private const val KEY_PENDING_DELETES = "pending_event_deletes"
        private const val INVALID_CALENDAR_ID = -1L
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val COMPLETED_STATUS = "completed"
        private const val COMPLETED_MARKER = "[TaskSync: completed]"
        private const val DEFAULT_EVENT_MINUTES = 30L
    }
}
