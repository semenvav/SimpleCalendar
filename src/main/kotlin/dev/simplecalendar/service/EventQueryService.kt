package dev.simplecalendar.service

import dev.simplecalendar.ical.EventExpander
import dev.simplecalendar.ical.IcsParser
import dev.simplecalendar.model.CalendarCollection
import dev.simplecalendar.model.Occurrence
import dev.simplecalendar.model.startInstant
import dev.simplecalendar.store.CalendarRepository
import dev.simplecalendar.store.EventRepository
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId

/**
 * Answers "what is on the calendar between these two moments".
 *
 * Recurrences are expanded on demand rather than materialised into a table. At household scale —
 * a couple of thousand events, a few dozen of them repeating — expansion costs single-digit
 * milliseconds, and skipping the materialised copy removes an entire class of cache-invalidation
 * bugs. The [candidatesInRange][EventRepository.candidatesInRange] pre-filter is what keeps it
 * cheap: SQL discards everything whose overall span cannot touch the window before we parse
 * a single line of iCalendar.
 */
class EventQueryService(
    private val calendars: CalendarRepository,
    private val events: EventRepository,
    private val expander: EventExpander,
    private val zone: ZoneId,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Visible calendars, optionally narrowed to [calendarIds]. */
    fun visibleCalendars(calendarIds: Collection<String>? = null): List<CalendarCollection> {
        val visible = calendars.all().filter { it.visible }
        if (calendarIds.isNullOrEmpty()) return visible
        val wanted = calendarIds.toSet()
        return visible.filter { it.id in wanted }
    }

    fun occurrences(from: Instant, to: Instant, calendarIds: Collection<String>? = null): List<Occurrence> {
        require(from.isBefore(to)) { "'from' must be before 'to'" }

        val started = System.nanoTime()
        val selected = visibleCalendars(calendarIds)
        if (selected.isEmpty()) return emptyList()

        val candidates = events.candidatesInRange(selected.map { it.id }, from.toEpochMilli(), to.toEpochMilli())

        val result = candidates.flatMap { stored ->
            try {
                IcsParser.parse(stored.ics).flatMap { parsed ->
                    expander.expand(parsed, stored.calendarId, stored.href, from, to)
                }
            } catch (e: Exception) {
                // A single malformed resource must not blank the whole display.
                log.warn("Skipping {} while expanding: {}", stored.href, e.message)
                emptyList()
            }
        }.sortedWith(compareBy({ it.time.startInstant(zone) }, { it.title }))

        log.debug(
            "Expanded {} candidates into {} occurrences in {} ms",
            candidates.size, result.size, (System.nanoTime() - started) / 1_000_000,
        )
        return result
    }
}
