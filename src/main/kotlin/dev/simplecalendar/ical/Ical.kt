package dev.simplecalendar.ical

import dev.simplecalendar.model.EventTime
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.parameter.Value
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
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.Temporal
import java.time.temporal.TemporalAmount

/** What an event without a SUMMARY is called on screen. */
internal const val UNTITLED = "(без названия)"

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
    val isRecurring: Boolean get() = master?.repeats() == true
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

/** True when the component defines a recurrence of its own (RRULE or RDATE). */
fun VEvent.repeats(): Boolean =
    getProperties<Property>(Property.RRULE).isNotEmpty() || getProperties<Property>(Property.RDATE).isNotEmpty()

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

internal fun Property.tzidValue(): String? =
    getParameter<Parameter>(Parameter.TZID).orElse(null)?.value

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

internal fun DateProperty<Temporal>.resolvedDate(): Temporal? = date?.reZone(tzidValue())

internal fun DateListProperty<Temporal>.resolvedDates(): List<Temporal> =
    tzidValue().let { tzid -> dates.map { it.reZone(tzid) } }

/** An RDATE of periods rather than dates — a shape we read around and never rewrite. */
internal fun Property.isPeriodList(): Boolean =
    getParameter<Value>(Parameter.VALUE).orElse(null) == Value.PERIOD

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

/** The wall-clock reading of [this] in [zone]; a value without a zone of its own already is one. */
fun Temporal.wallClockIn(zone: ZoneId, household: ZoneId): LocalDateTime = when (this) {
    is LocalDateTime -> this
    is LocalDate -> atStartOfDay()
    else -> toInstantIn(household).atZone(zone).toLocalDateTime()
}

/** The calendar date of [this] — in its own zone if it has one, otherwise in [household]. */
fun Temporal.dateIn(household: ZoneId): LocalDate = when (this) {
    is LocalDate -> this
    is LocalDateTime -> toLocalDate()
    is ZonedDateTime -> toLocalDate()
    else -> toInstantIn(household).atZone(household).toLocalDate()
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

/** The typed time range of an instance starting at [start] and lasting [duration]. */
fun eventTimeOf(start: Temporal, duration: TemporalAmount, zone: ZoneId): EventTime =
    if (start is LocalDate) {
        EventTime.AllDay(start, allDayEnd(start, duration))
    } else {
        val zoned = start.toZonedIn(zone)
        EventTime.Timed(zoned, zoned.plus(duration))
    }

/** All-day events always cover at least one whole day, whatever the source said. */
private fun allDayEnd(start: LocalDate, duration: TemporalAmount): LocalDate {
    val end = when (duration) {
        is Period -> start.plus(duration)
        is Duration -> start.plusDays(duration.toDays())
        else -> start.plusDays(1)
    }
    return if (end.isAfter(start)) end else start.plusDays(1)
}

/** How long the event lasts: whole days for all-day, an exact duration otherwise. */
fun EventTime.length(): TemporalAmount = when (this) {
    is EventTime.AllDay -> Period.ofDays((endExclusive.toEpochDay() - start.toEpochDay()).toInt())
    is EventTime.Timed -> Duration.between(start, end)
}

// --- Instance ids -----------------------------------------------------------------------------

/**
 * How the API names one instance of a series: `2026-09-14` for an all-day series, the UTC instant
 * of its start otherwise.
 *
 * Unambiguous whatever form the file itself uses, and it reduces to the same [recurrenceKey] as
 * the instance it came from — which is all the write path needs to find that instance again.
 */
fun Temporal.toInstanceId(zone: ZoneId): String =
    if (this is LocalDate) toString() else toInstantIn(zone).toString()

fun parseInstanceId(raw: String): Temporal =
    runCatching { LocalDate.parse(raw) }.getOrNull()
        ?: runCatching { Instant.parse(raw) }.getOrNull()
        ?: throw IllegalArgumentException("Неверный идентификатор повторения: «$raw».")

// --- How a component writes its times ---------------------------------------------------------

/**
 * The form a component's date-times are written in, read off its DTSTART.
 *
 * Everything else written into the component — DTEND, EXDATE, RDATE, RECURRENCE-ID, an RRULE's
 * UNTIL — has to follow it. RFC 5545 demands that for several of them, and where it does not,
 * mixing forms is the classic way for an exception to silently miss the instance it was meant for.
 */
sealed interface TimeForm {
    /** `VALUE=DATE`: an all-day event. */
    data object Date : TimeForm

    /** Wall-clock text with a `TZID`. [tzid] is written back verbatim; [zone] does the arithmetic. */
    data class Zoned(val tzid: String, val zone: ZoneId) : TimeForm

    /** `…Z`. */
    data object Utc : TimeForm

    /** No zone at all — "nine in the morning wherever you are", read in the household zone. */
    data object Floating : TimeForm
}

fun VEvent.timeForm(): TimeForm? {
    val property = getProperty<DateProperty<Temporal>>(Property.DTSTART).orElse(null) ?: return null
    val tzid = property.tzidValue()
    return when (val start = property.resolvedDate()) {
        null -> null
        is LocalDate -> TimeForm.Date
        is LocalDateTime -> TimeForm.Floating
        is ZonedDateTime -> if (tzid != null) TimeForm.Zoned(tzid, start.zone) else TimeForm.Utc
        else -> TimeForm.Utc
    }
}

internal val DATE_TEXT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
internal val LOCAL_TEXT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
internal val UTC_TEXT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

/**
 * The parameters and literal text [value] takes when written in this form.
 *
 * Literal text rather than a Temporal handed to ical4j: ical4j formats zoned values through its
 * own tz table, the stale one described above, and would shift them by the difference.
 * [household] places values that carry no zone of their own.
 */
fun TimeForm.render(value: Temporal, household: ZoneId): Pair<ParameterList, String> = when (this) {
    TimeForm.Date -> ParameterList(listOf(Value.DATE)) to value.dateIn(household).format(DATE_TEXT)
    is TimeForm.Zoned -> ParameterList(listOf(TzId(tzid))) to value.wallClockIn(zone, household).format(LOCAL_TEXT)
    TimeForm.Utc -> ParameterList() to UTC_TEXT.format(value.toInstantIn(household))
    TimeForm.Floating -> ParameterList() to value.wallClockIn(household, household).format(LOCAL_TEXT)
}

/**
 * [value] as an RRULE's UNTIL on a series written in this form.
 *
 * UNTIL follows DTSTART's form with one exception: a zoned DTSTART takes it in UTC
 * (RFC 5545 §3.3.10).
 */
fun TimeForm.untilText(value: Temporal, household: ZoneId): String = when (this) {
    TimeForm.Date -> value.dateIn(household).format(DATE_TEXT)
    TimeForm.Floating -> value.wallClockIn(household, household).format(LOCAL_TEXT)
    is TimeForm.Zoned, TimeForm.Utc -> UTC_TEXT.format(value.toInstantIn(household))
}
