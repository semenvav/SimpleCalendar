package dev.simplecalendar.service

import dev.simplecalendar.caldav.CalDavClient
import dev.simplecalendar.caldav.CalDavException
import dev.simplecalendar.caldav.hrefPath
import dev.simplecalendar.ical.EventDraft
import dev.simplecalendar.ical.EventExpander
import dev.simplecalendar.ical.IcsParser
import dev.simplecalendar.ical.IcsWriter
import dev.simplecalendar.model.CalendarCollection
import dev.simplecalendar.model.Occurrence
import dev.simplecalendar.model.StoredEvent
import dev.simplecalendar.model.startInstant
import dev.simplecalendar.plugins.ConflictException
import dev.simplecalendar.plugins.ForbiddenException
import dev.simplecalendar.plugins.NotFoundException
import dev.simplecalendar.plugins.NotSupportedException
import dev.simplecalendar.store.CalendarRepository
import dev.simplecalendar.store.EventRepository
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Creates, edits and deletes events on the CalDAV server.
 *
 * Writes go to Baikal first and only then to our cache — the server stays the source of truth,
 * so a write that Baikal rejects never lingers locally looking successful.
 *
 * Every write carries `If-Match` with the ETag we believe the resource has. That is what turns a
 * simultaneous edit from somebody's phone into a visible conflict instead of a silent overwrite.
 */
class EventWriteService(
    private val calendars: CalendarRepository,
    private val events: EventRepository,
    private val client: CalDavClient,
    private val expander: EventExpander,
    private val zone: ZoneId,
    private val onChange: () -> Unit = {},
) {

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun create(calendarId: String, draft: EventDraft): Occurrence {
        val calendar = writableCalendar(calendarId)

        val uid = UUID.randomUUID().toString()
        val href = "${hrefPath(calendar.url).trimEnd('/')}/$uid.ics"
        val ics = IcsWriter.create(uid, draft)

        // No If-Match, but If-None-Match: * — refuse to silently clobber if the name is taken.
        val etag = client.put(calendar.url, href, ics, ifMatch = null)
        log.info("Created event '{}' in '{}'", draft.title, calendar.name)

        return storeAndReturn(calendar, href, ics, etag)
    }

    suspend fun update(calendarId: String, href: String, draft: EventDraft): Occurrence {
        val calendar = writableCalendar(calendarId)
        val stored = storedEvent(calendarId, href)

        if (stored.recurring) {
            throw NotSupportedException(
                "Это повторяющееся событие. Правка отдельных повторений появится на следующем этапе — " +
                    "пока его можно только удалить целиком.",
            )
        }

        val ics = IcsWriter.applyTo(stored.ics, draft)
            ?: throw NotSupportedException("В этом файле нет события, которое можно изменить.")

        val etag = withConflictHandling(calendar, href) {
            client.put(calendar.url, href, ics, ifMatch = stored.etag)
        }
        log.info("Updated event '{}' in '{}'", draft.title, calendar.name)

        return storeAndReturn(calendar, href, ics, etag)
    }

    /**
     * Deletes the whole resource.
     *
     * For a repeating event that means the entire series. Removing a single occurrence needs the
     * EXDATE machinery and belongs with the rest of the recurrence editing; the UI says so
     * explicitly rather than letting somebody delete more than they meant to.
     */
    suspend fun delete(calendarId: String, href: String) {
        val calendar = writableCalendar(calendarId)
        val stored = storedEvent(calendarId, href)

        withConflictHandling(calendar, href) {
            client.delete(calendar.url, href, stored.etag)
        }

        events.deleteHrefs(calendarId, listOf(href))
        onChange()
        log.info("Deleted {} from '{}'", href, calendar.name)
    }

    // --- internals ----------------------------------------------------------------------------

    private fun writableCalendar(calendarId: String): CalendarCollection {
        val calendar = calendars.byId(calendarId)
            ?: throw NotFoundException("Календарь не найден.")
        if (calendar.readOnly) {
            throw ForbiddenException("Календарь «${calendar.name}» доступен только для чтения.")
        }
        return calendar
    }

    private fun storedEvent(calendarId: String, href: String): StoredEvent =
        events.byHref(calendarId, href)
            ?: throw NotFoundException("Событие не найдено — возможно, его уже удалили.")

    /**
     * Turns a 412 from the server into a conflict the UI can explain.
     *
     * The resource is re-read first, so by the time the user sees the message the calendar
     * already shows the other person's version rather than the stale one they were editing.
     */
    private suspend fun <T> withConflictHandling(
        calendar: CalendarCollection,
        href: String,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (e: CalDavException) {
        if (e.status != 412) throw e
        refreshFromServer(calendar, href)
        throw ConflictException(
            "Событие успели изменить в другом месте. Календарь обновлён — посмотрите и повторите правку.",
        )
    }

    /** Re-reads one resource from the server, dropping it locally if it is gone. */
    private suspend fun refreshFromServer(calendar: CalendarCollection, href: String) {
        val fetched = client.multiget(calendar.url, listOf(href)).firstOrNull()
        val ics = fetched?.ics

        if (ics == null) {
            events.deleteHrefs(calendar.id, listOf(href))
        } else {
            expander.toStoredEvent(calendar.id, href, fetched.etag, ics)
                ?.let { events.upsertAll(listOf(it)) }
        }
        onChange()
    }

    /**
     * Caches what we just wrote and returns the resulting occurrence.
     *
     * When the server does not hand back an ETag we ask for the resource instead of inventing
     * one: a row with the wrong ETag would make the very next sync re-download it, and worse,
     * would make the next edit's `If-Match` fail for no reason.
     */
    private suspend fun storeAndReturn(
        calendar: CalendarCollection,
        href: String,
        writtenIcs: String,
        etag: String?,
    ): Occurrence {
        val (ics, resolvedEtag) = if (etag != null) {
            writtenIcs to etag
        } else {
            val fetched = client.multiget(calendar.url, listOf(href)).firstOrNull()
            (fetched?.ics ?: writtenIcs) to fetched?.etag
        }

        val stored = expander.toStoredEvent(calendar.id, href, resolvedEtag, ics)
            ?: throw NotSupportedException("Не удалось прочитать сохранённое событие.")

        events.upsertAll(listOf(stored))
        onChange()

        return firstOccurrence(stored)
            ?: throw NotSupportedException("Событие сохранено, но не удалось его отобразить.")
    }

    /** Expands a freshly stored resource just widely enough to find its earliest occurrence. */
    private fun firstOccurrence(stored: StoredEvent): Occurrence? {
        val from = Instant.ofEpochMilli(stored.firstStartUtc).minus(PADDING)
        val to = Instant.ofEpochMilli(stored.lastEndUtc ?: stored.firstStartUtc).plus(PADDING)

        return IcsParser.parse(stored.ics)
            .flatMap { expander.expand(it, stored.calendarId, stored.href, from, to) }
            .minByOrNull { it.time.startInstant(zone) }
    }

    private companion object {
        val PADDING: Duration = Duration.ofDays(2)
    }
}
