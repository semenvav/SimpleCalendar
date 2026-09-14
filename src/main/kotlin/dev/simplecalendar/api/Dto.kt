package dev.simplecalendar.api

import dev.simplecalendar.ical.EventDraft
import dev.simplecalendar.model.CalendarCollection
import dev.simplecalendar.model.EventTime
import dev.simplecalendar.model.Occurrence
import dev.simplecalendar.sync.SyncStatus
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Serializable
data class CalendarDto(
    val id: String,
    val name: String,
    val color: String,
    val readOnly: Boolean,
    val visible: Boolean,
    val sortOrder: Int,
)

/**
 * One occurrence as the UI consumes it.
 *
 * [start] and [end] follow the convention both calendar libraries expect: all-day entries are
 * plain `YYYY-MM-DD` with an *exclusive* end, timed entries are ISO-8601 with an explicit offset
 * so the browser never has to guess a zone.
 */
@Serializable
data class EventDto(
    val id: String,
    val calendarId: String,
    val uid: String,
    val recurrenceId: String? = null,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val allDay: Boolean,
    val start: String,
    val end: String,
    val recurring: Boolean,
    val readOnly: Boolean,
)

@Serializable
data class SyncStatusDto(
    val configured: Boolean,
    val lastSuccessAt: String? = null,
    val lastError: String? = null,
    val calendarCount: Int,
    val eventCount: Int,
    val revision: Long,
)

@Serializable
data class HealthDto(
    val status: String,
    val version: String,
    val timeZone: String,
    val sync: SyncStatusDto,
)

@Serializable
data class CalendarPatch(
    val name: String? = null,
    val color: String? = null,
    val visible: Boolean? = null,
    val sortOrder: Int? = null,
)

/**
 * What the UI sends to create or change an event.
 *
 * [end] is **exclusive**, matching what the read API returns — an all-day event on the 10th has
 * `start=2026-09-10, end=2026-09-11`. Symmetry with the read side is worth more here than the
 * slightly friendlier inclusive form; the UI adds and subtracts the day in one place.
 */
@Serializable
data class EventWriteRequest(
    /** Required when creating; ignored when editing, since the event already has a calendar. */
    val calendarId: String? = null,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val allDay: Boolean,
    val start: String,
    val end: String,
)

/**
 * Validates a write request and converts it to a draft.
 *
 * Times arrive as the user typed them — `2026-09-15T19:00`, with no zone — and are read in the
 * household zone, which is what a wall calendar's clock shows.
 */
fun EventWriteRequest.toDraft(zone: ZoneId): EventDraft {
    val cleanTitle = title.trim()
    require(cleanTitle.isNotEmpty()) { "У события должно быть название." }
    require(cleanTitle.length <= 500) { "Название слишком длинное." }

    val time = if (allDay) {
        val from = parseDate(start)
        val to = parseDate(end)
        require(to.isAfter(from)) { "Событие должно заканчиваться позже, чем начинается." }
        EventTime.AllDay(from, to)
    } else {
        val from = parseDateTime(start, zone)
        val to = parseDateTime(end, zone)
        require(!to.isBefore(from)) { "Событие должно заканчиваться позже, чем начинается." }
        EventTime.Timed(from, to)
    }

    return EventDraft(
        title = cleanTitle,
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        location = location?.trim()?.takeIf { it.isNotEmpty() },
        time = time,
    )
}

private fun parseDate(raw: String): LocalDate =
    runCatching { LocalDate.parse(raw) }
        .getOrElse { throw IllegalArgumentException("Не удалось прочитать дату «$raw».") }

private fun parseDateTime(raw: String, zone: ZoneId): ZonedDateTime =
    runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(zone) }
        .recoverCatching { LocalDateTime.parse(raw).atZone(zone) }
        .recoverCatching { Instant.parse(raw).atZone(zone) }
        .getOrElse { throw IllegalArgumentException("Не удалось прочитать дату и время «$raw».") }

fun CalendarCollection.toDto() = CalendarDto(
    id = id,
    name = name,
    color = color,
    readOnly = readOnly,
    visible = visible,
    sortOrder = sortOrder,
)

fun Occurrence.toDto(readOnly: Boolean): EventDto {
    val (start, end) = when (val t = time) {
        is EventTime.AllDay -> t.start.toString() to t.endExclusive.toString()
        is EventTime.Timed -> t.start.format(ISO_OFFSET) to t.end.format(ISO_OFFSET)
    }
    return EventDto(
        // Stable per occurrence, so the UI can track an entry across refreshes.
        id = "$calendarId|$href|${recurrenceId ?: "-"}",
        calendarId = calendarId,
        uid = uid,
        recurrenceId = recurrenceId,
        title = title,
        description = description,
        location = location,
        allDay = time is EventTime.AllDay,
        start = start,
        end = end,
        recurring = recurring,
        readOnly = readOnly,
    )
}

fun SyncStatus.toDto() = SyncStatusDto(
    configured = configured,
    lastSuccessAt = lastSuccessAt?.toString(),
    lastError = lastError,
    calendarCount = calendarCount,
    eventCount = eventCount,
    revision = revision,
)

private val ISO_OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
