package dev.simplecalendar.ical

import dev.simplecalendar.model.EventTime
import dev.simplecalendar.model.Occurrence
import dev.simplecalendar.model.RepeatRule
import dev.simplecalendar.model.StoredEvent
import dev.simplecalendar.model.endInstant
import dev.simplecalendar.model.startInstant
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.Recur
import net.fortuna.ical4j.model.component.VEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import java.time.temporal.TemporalAmount

/** Overall time span of a resource, used to pre-filter candidates in SQL. */
data class EventSpan(
    val firstStartUtc: Long,
    /** `null` means the recurrence never ends, so the event is always a candidate. */
    val lastEndUtc: Long?,
    val recurring: Boolean,
)

/**
 * Turns stored iCalendar resources into concrete calendar entries.
 *
 * ical4j's own `ComponentGroup.calculateRecurrenceSet` is deliberately not used here. It assumes
 * an override's `RECURRENCE-ID` equals the time the instance actually occupies, which breaks as
 * soon as somebody drags a single occurrence of a repeating event to another time — precisely
 * the case a family calendar hits constantly. We keep ical4j for the part it does well, the
 * RRULE arithmetic in [net.fortuna.ical4j.model.Recur], and do the master/override merge here.
 */
class EventExpander(private val zone: ZoneId) {

    /**
     * Expands [parsed] into every occurrence overlapping `[from, to)`.
     *
     * The merge follows RFC 5545 in four steps: expand the master's rules, drop EXDATEs, drop
     * instances that an override replaces, then add the overrides using *their own* DTSTART.
     */
    fun expand(
        parsed: ParsedEvent,
        calendarId: String,
        href: String,
        from: Instant,
        to: Instant,
    ): List<Occurrence> {
        val result = mutableListOf<Occurrence>()
        val overridesByKey = parsed.overrides
            .mapNotNull { ov -> ov.recurrenceIdTemporal()?.let { it.recurrenceKey(zone) to ov } }
            .toMap()

        val master = parsed.master
        val recurring = parsed.isRecurring
        // One rule for the whole series; every instance carries it so the form can show it.
        val repeat = if (recurring && master != null) repeatOf(master) else null

        if (master != null) {
            val seed = master.startTemporal()
            if (seed != null) {
                val duration = master.duration()
                val exceptionKeys = master.exceptionDates().mapTo(mutableSetOf()) { it.recurrenceKey(zone) }

                val starts = if (recurring) recurrenceStarts(master, seed, duration, from, to) else listOf(seed)

                for (start in starts) {
                    val key = start.recurrenceKey(zone)
                    if (key in exceptionKeys) continue
                    // Replaced by an override — emitted below at its own time, not this one.
                    if (key in overridesByKey) continue

                    val time = eventTimeOf(start, duration, zone)
                    if (!overlaps(time, from, to)) continue
                    result += master.toOccurrence(
                        calendarId = calendarId,
                        href = href,
                        uid = parsed.uid,
                        recurrenceId = if (recurring) start.toInstanceId(zone) else null,
                        time = time,
                        recurring = recurring,
                        repeat = repeat,
                    )
                }
            }
        }

        for (override in parsed.overrides) {
            val start = override.startTemporal() ?: continue
            val time = eventTimeOf(start, override.duration(), zone)
            if (!overlaps(time, from, to)) continue
            result += override.toOccurrence(
                calendarId = calendarId,
                href = href,
                uid = parsed.uid,
                recurrenceId = override.recurrenceIdTemporal()?.toInstanceId(zone),
                time = time,
                recurring = true,
                repeat = repeat,
            )
        }

        return result
    }

    /**
     * The master's rule as the form shows it, or `null` when it is richer than a [RepeatRule].
     *
     * The end is reported as the last day the rule reaches, whatever wrote it: a COUNT has no day
     * at all, and an UNTIL can sit anywhere — the old half of a split series ends the second
     * before the cut, on a day the rule no longer reaches. "Until 10 November" on a card whose
     * last Tuesday is the 3rd would be read as the 10th still happening. An exception on that
     * last day does not move it, as in other calendars: the rule runs to it, the day is cancelled.
     * Leaving the control alone keeps the rule's own spelling either way.
     */
    fun repeatOf(master: VEvent): RepeatRule? {
        val rules = master.recurrenceRules()
        if (rules.size != 1 || master.recurrenceDates().isNotEmpty()) return null
        val seed = master.startTemporal() ?: return null
        val raw = master.getProperties<Property>(Property.RRULE).single().value
        val (frequency, interval) = simpleShape(raw, seed.dateIn(zone)) ?: return null

        val recur = rules.single()
        val seriesZone = (seed as? ZonedDateTime)?.zone ?: zone
        val until: Temporal? = recur.until
        val lastDay = when {
            // Past the expansion cap, the UNTIL's own day is the honest answer left.
            until != null -> (lastStart(recur, seed) ?: until).toZonedIn(seriesZone).toLocalDate()
            recur.count > 0 -> (lastStart(recur, seed) ?: return null).toZonedIn(seriesZone).toLocalDate()
            else -> null
        }
        return RepeatRule(frequency, interval, lastDay)
    }

    /** The last start of a bounded rule, or `null` if it has none or runs past [COUNT_CAP]. */
    internal fun lastStart(recur: Recur<Temporal>, seed: Temporal): Temporal? {
        val dates = recur.getDates(seed, seed, boundaryLike(seed, FAR_FUTURE), COUNT_CAP)
        return if (dates.size >= COUNT_CAP) null else dates.lastOrNull()
    }

    /** How many starts [recur] produces before [instance] — what a COUNT has used up by then. */
    internal fun generatedBefore(recur: Recur<Temporal>, seed: Temporal, instance: Temporal): Int {
        val cut = instance.toInstantIn(zone)
        return recur.getDates(seed, seed, boundaryLike(seed, cut.plus(Duration.ofDays(1))), COUNT_CAP)
            .count { it.toInstantIn(zone).isBefore(cut) }
    }

    /**
     * Turns a fetched or freshly written resource into a cache row.
     *
     * Returns `null` when the resource holds nothing we can place in time — a VTODO-only file,
     * or an event too malformed to read a start from. Callers treat that as "not cacheable"
     * rather than guessing.
     */
    fun toStoredEvent(calendarId: String, href: String, etag: String?, ics: String): StoredEvent? {
        val parsed = try {
            IcsParser.parse(ics)
        } catch (e: Exception) {
            return null
        }
        val span = spanOf(parsed) ?: return null

        return StoredEvent(
            calendarId = calendarId,
            href = href,
            etag = etag,
            uid = parsed.first().uid,
            ics = ics,
            firstStartUtc = span.firstStartUtc,
            lastEndUtc = span.lastEndUtc,
            recurring = span.recurring,
        )
    }

    /** Combined span across every UID a single resource happens to contain. */
    fun spanOf(events: List<ParsedEvent>): EventSpan? {
        var first: Long? = null
        var last: Long? = null
        var openEnded = false
        var recurring = false

        for (event in events) {
            val span = span(event) ?: continue
            first = first?.let { minOf(it, span.firstStartUtc) } ?: span.firstStartUtc
            if (span.lastEndUtc == null) openEnded = true
            else last = last?.let { maxOf(it, span.lastEndUtc) } ?: span.lastEndUtc
            recurring = recurring || span.recurring
        }

        return EventSpan(
            firstStartUtc = first ?: return null,
            lastEndUtc = if (openEnded) null else last,
            recurring = recurring,
        )
    }

    /**
     * Computes the denormalised span stored alongside the raw `.ics`.
     *
     * Returns `null` for a resource we cannot place in time at all, which the caller treats as
     * "not cacheable" rather than guessing.
     */
    fun span(parsed: ParsedEvent): EventSpan? {
        var first: Instant? = null
        var last: Instant? = null
        var openEnded = false

        fun note(start: Instant, end: Instant) {
            first = first?.let { minOf(it, start) } ?: start
            last = last?.let { maxOf(it, end) } ?: end
        }

        val master = parsed.master
        if (master != null) {
            val seed = master.startTemporal()
            if (seed != null) {
                val duration = master.duration()
                val seedTime = eventTimeOf(seed, duration, zone)
                note(seedTime.startInstant(zone), seedTime.endInstant(zone))

                for (recur in master.recurrenceRules()) {
                    val until = recur.until
                    when {
                        until != null -> {
                            val t = eventTimeOf(until, duration, zone)
                            note(t.startInstant(zone), t.endInstant(zone))
                        }
                        recur.count > 0 -> {
                            // Bounded by COUNT: walk it, but refuse to grind through a pathological
                            // rule. Hitting the cap simply degrades to "treat as open-ended", which
                            // costs a little query-time work and is never wrong.
                            val dates = recur.getDates(seed, seed, boundaryLike(seed, FAR_FUTURE), COUNT_CAP)
                            if (dates.size >= COUNT_CAP) {
                                openEnded = true
                            } else {
                                dates.lastOrNull()?.let {
                                    val t = eventTimeOf(it, duration, zone)
                                    note(t.startInstant(zone), t.endInstant(zone))
                                }
                            }
                        }
                        else -> openEnded = true
                    }
                }

                for (rdate in master.recurrenceDates()) {
                    val t = eventTimeOf(rdate, duration, zone)
                    note(t.startInstant(zone), t.endInstant(zone))
                }
            }
        }

        for (override in parsed.overrides) {
            val start = override.startTemporal() ?: continue
            val t = eventTimeOf(start, override.duration(), zone)
            note(t.startInstant(zone), t.endInstant(zone))
        }

        val firstStart = first ?: return null
        return EventSpan(
            firstStartUtc = firstStart.toEpochMilli(),
            lastEndUtc = if (openEnded) null else last?.toEpochMilli(),
            recurring = parsed.isRecurring,
        )
    }

    // --- internals ----------------------------------------------------------------------------

    /**
     * Instance start times produced by the master's RRULEs and RDATEs within the window.
     *
     * The query window is widened backwards by the event's own length so that an instance which
     * started before the window but is still running inside it does not disappear — the usual
     * symptom being a multi-day event missing from the first day of a month view.
     */
    private fun recurrenceStarts(
        master: VEvent,
        seed: Temporal,
        duration: TemporalAmount,
        from: Instant,
        to: Instant,
    ): List<Temporal> {
        val widenedFrom = from.minus(approximate(duration))
        val queryStart = boundaryLike(seed, widenedFrom)
        val queryEnd = boundaryLike(seed, to)

        val starts = LinkedHashSet<Temporal>()
        for (recur in master.recurrenceRules()) {
            starts += recur.getDates(seed, queryStart, queryEnd)
        }
        for (rdate in master.recurrenceDates()) {
            val instant = rdate.toInstantIn(zone)
            if (!instant.isBefore(widenedFrom) && instant.isBefore(to)) starts += rdate
        }
        return starts.toList()
    }

    private fun overlaps(time: EventTime, from: Instant, to: Instant): Boolean {
        val start = time.startInstant(zone)
        val end = time.endInstant(zone)
        // A zero-length event covers no interval, so treat it as the single point it sits on.
        return if (end == start) !start.isBefore(from) && start.isBefore(to)
        else start.isBefore(to) && end.isAfter(from)
    }

    /**
     * Converts an instant into the same Temporal shape as [seed].
     *
     * ical4j compares the window bounds against generated values directly, so handing it a
     * `ZonedDateTime` bound for a `LocalDate` seed produces nonsense.
     */
    private fun boundaryLike(seed: Temporal, instant: Instant): Temporal = when (seed) {
        is LocalDate -> instant.atZone(zone).toLocalDate()
        is ZonedDateTime -> instant.atZone(seed.zone)
        is OffsetDateTime -> instant.atOffset(seed.offset)
        is LocalDateTime -> instant.atZone(zone).toLocalDateTime()
        else -> instant
    }

    /** Rough length, used only to widen a query window — never to compute an event's real end. */
    private fun approximate(amount: TemporalAmount): Duration = when (amount) {
        is Duration -> amount
        is Period -> Duration.ofDays(amount.toTotalMonths() * 31 + amount.days)
        else -> Duration.ofDays(1)
    }

    private fun VEvent.toOccurrence(
        calendarId: String,
        href: String,
        uid: String,
        recurrenceId: String?,
        time: EventTime,
        recurring: Boolean,
        repeat: RepeatRule?,
    ) = Occurrence(
        calendarId = calendarId,
        href = href,
        uid = uid,
        recurrenceId = recurrenceId,
        time = time,
        title = summaryValue()?.takeIf { it.isNotBlank() } ?: UNTITLED,
        description = descriptionValue()?.takeIf { it.isNotBlank() },
        location = locationValue()?.takeIf { it.isNotBlank() },
        status = statusValue(),
        mark = markValue(),
        recurring = recurring,
        repeat = repeat,
    )

    private companion object {
        val FAR_FUTURE: Instant = Instant.parse("2200-01-01T00:00:00Z")
        const val COUNT_CAP = 2_000
    }
}
