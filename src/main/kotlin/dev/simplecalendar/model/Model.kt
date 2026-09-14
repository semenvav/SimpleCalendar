package dev.simplecalendar.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When an event happens.
 *
 * All-day and timed events are deliberately different types. In iCalendar an all-day event is a
 * pair of floating dates with no zone at all, and converting one to an instant "just to store it"
 * is the single most common source of off-by-one-day bugs in calendar software. Keeping them
 * apart means the compiler refuses to let that happen by accident.
 */
sealed interface EventTime {
    /** [endExclusive] follows the iCalendar DTEND convention: a one-day event ends the next day. */
    data class AllDay(val start: LocalDate, val endExclusive: LocalDate) : EventTime

    data class Timed(val start: ZonedDateTime, val end: ZonedDateTime) : EventTime
}

/** Start of the event as an instant, resolving floating all-day dates against [zone]. */
fun EventTime.startInstant(zone: ZoneId): Instant = when (this) {
    is EventTime.AllDay -> start.atStartOfDay(zone).toInstant()
    is EventTime.Timed -> start.toInstant()
}

/** Exclusive end of the event as an instant, resolving floating all-day dates against [zone]. */
fun EventTime.endInstant(zone: ZoneId): Instant = when (this) {
    is EventTime.AllDay -> endExclusive.atStartOfDay(zone).toInstant()
    is EventTime.Timed -> end.toInstant()
}

val EventTime.isAllDay: Boolean get() = this is EventTime.AllDay

/**
 * A calendar collection on the CalDAV server together with our local presentation settings.
 *
 * Server-provided values and local overrides are kept in separate fields so that a sync can
 * refresh the former without ever clobbering the latter.
 */
data class CalendarCollection(
    /** Stable local id derived from the collection URL; survives restarts and renames. */
    val id: String,
    val url: String,
    val serverName: String,
    val serverColor: String?,
    val customName: String?,
    val customColor: String?,
    val readOnly: Boolean,
    val visible: Boolean,
    val sortOrder: Int,
    val syncToken: String?,
    val ctag: String?,
    val lastSyncAt: Instant?,
) {
    val name: String get() = customName ?: serverName
    val color: String get() = customColor ?: serverColor ?: fallbackColor(id)

    companion object {
        private val PALETTE = listOf(
            "#3b82f6", "#ef4444", "#22c55e", "#a855f7",
            "#f59e0b", "#06b6d4", "#ec4899", "#84cc16",
        )

        /** Deterministic colour for calendars the server has no colour for. */
        fun fallbackColor(id: String): String =
            PALETTE[Math.floorMod(id.hashCode(), PALETTE.size)]
    }
}

/**
 * One `.ics` resource exactly as it lives on the server, plus a few denormalised fields.
 *
 * [ics] is the cached source of truth. [firstStartUtc]/[lastEndUtc] exist purely so SQL can
 * narrow a date-range query down to candidate rows before we pay for recurrence expansion.
 */
data class StoredEvent(
    val calendarId: String,
    val href: String,
    val etag: String?,
    val uid: String,
    val ics: String,
    val firstStartUtc: Long,
    /** Epoch millis of the last possible end, or `null` for an open-ended recurrence. */
    val lastEndUtc: Long?,
    val recurring: Boolean,
)

/** A single concrete appearance of an event on the calendar, after recurrence expansion. */
data class Occurrence(
    val calendarId: String,
    val href: String,
    val uid: String,
    /**
     * `RECURRENCE-ID` of this instance in its original iCalendar form, or `null` for
     * non-recurring events. Identifies which instance to edit or delete.
     */
    val recurrenceId: String?,
    val time: EventTime,
    val title: String,
    val description: String?,
    val location: String?,
    val status: String?,
    val recurring: Boolean,
)
