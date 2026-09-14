package dev.simplecalendar.ical

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.DateListProperty
import net.fortuna.ical4j.model.property.DateProperty
import net.fortuna.ical4j.model.property.Duration as DurationProperty
import java.io.StringReader
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

/**
 * A single `.ics` resource split the way RFC 5545 actually structures a recurring event:
 * one master component carrying the recurrence rule, plus zero or more override components
 * that each replace one instance and are identified by `RECURRENCE-ID`.
 *
 * [master] is null only in the unusual case of a resource that contains overrides alone.
 */
data class ParsedEvent(
    val uid: String,
    val master: VEvent?,
    val overrides: List<VEvent>,
) {
    val components: List<VEvent> get() = listOfNotNull(master) + overrides

    /** True when the master defines an actual recurrence (RRULE or RDATE). */
    val isRecurring: Boolean
        get() = master != null &&
            (master.getProperties<Property>(Property.RRULE).isNotEmpty() ||
                master.getProperties<Property>(Property.RDATE).isNotEmpty())
}

object IcsParser {

    /**
     * Parses a calendar resource and returns one [ParsedEvent] per UID.
     *
     * A well-behaved CalDAV resource holds exactly one UID, but the format permits more and
     * some clients emit it, so grouping by UID is the honest reading rather than assuming one.
     */
    fun parse(ics: String): List<ParsedEvent> {
        val calendar = CalendarBuilder().build(StringReader(ics))
        val events = calendar.getComponents<CalendarComponent>().filterIsInstance<VEvent>()

        return events
            .groupBy { it.uidValue() }
            .mapNotNull { (uid, components) ->
                if (uid == null) return@mapNotNull null
                ParsedEvent(
                    uid = uid,
                    master = components.firstOrNull { it.recurrenceIdTemporal() == null },
                    overrides = components.filter { it.recurrenceIdTemporal() != null },
                )
            }
    }
}

// --- VEvent accessors -------------------------------------------------------------------------
//
// ical4j 4.x exposes properties through a mix of typed accessors and a generic
// getProperty(name) lookup. These wrappers keep that inconsistency out of the calling code.

fun VEvent.uidValue(): String? = getProperty<Property>(Property.UID).orElse(null)?.value

fun VEvent.summaryValue(): String? = getProperty<Property>(Property.SUMMARY).orElse(null)?.value

fun VEvent.descriptionValue(): String? = getProperty<Property>(Property.DESCRIPTION).orElse(null)?.value

fun VEvent.locationValue(): String? = getProperty<Property>(Property.LOCATION).orElse(null)?.value

fun VEvent.statusValue(): String? = getProperty<Property>(Property.STATUS).orElse(null)?.value

@Suppress("UNCHECKED_CAST")
fun VEvent.startTemporal(): Temporal? =
    getProperty<DateProperty<Temporal>>(Property.DTSTART).orElse(null)?.resolvedDate()

@Suppress("UNCHECKED_CAST")
fun VEvent.endTemporal(): Temporal? =
    getProperty<DateProperty<Temporal>>(Property.DTEND).orElse(null)?.resolvedDate()

@Suppress("UNCHECKED_CAST")
fun VEvent.recurrenceIdTemporal(): Temporal? =
    getProperty<DateProperty<Temporal>>(Property.RECURRENCE_ID).orElse(null)?.resolvedDate()

fun VEvent.durationAmount(): TemporalAmount? =
    getProperty<DurationProperty>(Property.DURATION).orElse(null)?.duration

@Suppress("UNCHECKED_CAST")
fun VEvent.recurrenceRules(): List<net.fortuna.ical4j.model.Recur<Temporal>> =
    getProperties<net.fortuna.ical4j.model.property.RRule<Temporal>>(Property.RRULE).map { it.recur }

@Suppress("UNCHECKED_CAST")
fun VEvent.recurrenceDates(): List<Temporal> =
    getProperties<DateListProperty<Temporal>>(Property.RDATE).flatMap { it.resolvedDates() }

@Suppress("UNCHECKED_CAST")
fun VEvent.exceptionDates(): List<Temporal> =
    getProperties<DateListProperty<Temporal>>(Property.EXDATE).flatMap { it.resolvedDates() }

// --- Time zone resolution ---------------------------------------------------------------------
//
// ical4j resolves TZID against a copy of the tz database bundled inside its own jar, and that
// copy can lag reality: its Europe/Moscow still carries the UTC+4 rule that was abolished in
// 2014, which silently shifts every Moscow event by an hour. The JDK's database ships with the
// runtime and gets updated with it, so we re-resolve TZID ourselves and treat ical4j's answer
// only as a fallback for names the JDK does not recognise (Microsoft-style "Russian Standard
// Time", or a custom VTIMEZONE with no IANA equivalent).

private val zoneCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<ZoneId>>()

private fun resolveZone(tzid: String): ZoneId? =
    zoneCache.computeIfAbsent(tzid) { java.util.Optional.ofNullable(lookupZone(it)) }.orElse(null)

private fun lookupZone(tzid: String): ZoneId? {
    runCatching { return ZoneId.of(tzid) }
    // Some clients emit globally unique ids such as
    // "/freeassociation.sourceforge.net/Europe/Moscow"; peel segments until one resolves.
    val parts = tzid.trim('/').split('/')
    for (i in parts.indices) {
        runCatching { return ZoneId.of(parts.drop(i).joinToString("/")) }
    }
    return null
}

private fun Property.tzidValue(): String? =
    getParameter<net.fortuna.ical4j.model.Parameter>(net.fortuna.ical4j.model.Parameter.TZID)
        .orElse(null)?.value

/** Rebinds a floating or zoned value to the zone named by its own TZID parameter. */
private fun Temporal.reZone(tzid: String?): Temporal {
    val zone = tzid?.let(::resolveZone) ?: return this
    return when (this) {
        // Same wall-clock reading, correct rules. Date-only and UTC values carry no ambiguity.
        is ZonedDateTime -> toLocalDateTime().atZone(zone)
        is LocalDateTime -> atZone(zone)
        else -> this
    }
}

private fun DateProperty<Temporal>.resolvedDate(): Temporal? = date?.reZone(tzidValue())

private fun DateListProperty<Temporal>.resolvedDates(): List<Temporal> =
    tzidValue().let { tzid -> dates.map { it.reZone(tzid) } }

/**
 * How long this component lasts, following RFC 5545 defaults.
 *
 * An event with a date-only DTSTART and no DTEND lasts one day; a timed event with neither
 * DTEND nor DURATION is instantaneous.
 */
fun VEvent.duration(): TemporalAmount {
    val start = startTemporal() ?: return Duration.ZERO
    endTemporal()?.let { end ->
        return if (start is LocalDate && end is LocalDate) {
            Period.ofDays((end.toEpochDay() - start.toEpochDay()).toInt())
        } else {
            Duration.between(start.toInstantIn(UTC_FALLBACK), end.toInstantIn(UTC_FALLBACK))
        }
    }
    durationAmount()?.let { return it }
    return if (start is LocalDate) Period.ofDays(1) else Duration.ZERO
}

// --- Temporal helpers -------------------------------------------------------------------------

private val UTC_FALLBACK: ZoneId = ZoneId.of("UTC")

/** True for a date-only value, i.e. an all-day event's DTSTART. */
fun Temporal.isDateOnly(): Boolean = this is LocalDate

/**
 * Resolves any of the Temporal shapes ical4j produces to an instant.
 *
 * A bare [LocalDateTime] is a *floating* iCalendar time — "9am wherever you are" — so it is
 * resolved against the household [zone] rather than assumed to be UTC.
 */
fun Temporal.toInstantIn(zone: ZoneId): Instant = when (this) {
    is Instant -> this
    is ZonedDateTime -> toInstant()
    is OffsetDateTime -> toInstant()
    is LocalDateTime -> atZone(zone).toInstant()
    is LocalDate -> atStartOfDay(zone).toInstant()
    else -> throw IllegalArgumentException("Unsupported temporal type: ${this::class.java.name}")
}

/**
 * Presents a value in the zone the household actually reads the calendar in.
 *
 * A named zone from `TZID` is kept: it carries intent — "nine in the morning in Berlin" — and
 * survives a change of DST rules. A bare offset does not, so `20260916T160000Z` is shown as
 * 19:00+03:00 rather than 16:00Z. Both denote the same instant, but the UTC spelling can carry a
 * *different calendar date* than the one on the wall, which is a trap for anything that reads the
 * date out of the string.
 */
fun Temporal.toZonedIn(zone: ZoneId): ZonedDateTime = when (this) {
    is ZonedDateTime -> this
    is OffsetDateTime -> atZoneSameInstant(zone)
    is Instant -> atZone(zone)
    is LocalDateTime -> atZone(zone)
    is LocalDate -> atStartOfDay(zone)
    else -> throw IllegalArgumentException("Unsupported temporal type: ${this::class.java.name}")
}

/**
 * Canonical key used to match a `RECURRENCE-ID` or `EXDATE` against an expanded instance start.
 *
 * The two sides routinely arrive in different representations — the rule may expand to
 * `ZonedDateTime` while `RECURRENCE-ID` was written as UTC — so comparing the raw Temporals
 * misses matches. Reducing both to an epoch value makes the comparison representation-agnostic,
 * while date-only values stay in their own namespace so an all-day event can never collide with
 * a timed one.
 */
fun Temporal.recurrenceKey(zone: ZoneId): String =
    if (this is LocalDate) "D:$this" else "T:${toInstantIn(zone).toEpochMilli()}"
