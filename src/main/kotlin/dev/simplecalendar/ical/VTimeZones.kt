package dev.simplecalendar.ical

import net.fortuna.ical4j.model.ComponentList
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.PropertyList
import net.fortuna.ical4j.model.component.Daylight
import net.fortuna.ical4j.model.component.Observance
import net.fortuna.ical4j.model.component.Standard
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.TzId
import net.fortuna.ical4j.model.property.TzName
import net.fortuna.ical4j.model.property.TzOffsetFrom
import net.fortuna.ical4j.model.property.TzOffsetTo
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneOffsetTransitionRule
import java.util.Locale

/**
 * VTIMEZONE components built from the JDK's time zone database.
 *
 * A repeating event has to be written with a real TZID — stored in UTC, a weekly 09:00 would
 * drift to 08:00 or 10:00 at the next DST change — and RFC 5545 wants every TZID a file uses to
 * be defined in that file. ical4j can produce the definition, but from its bundled tz copy, the
 * one that still has Moscow on UTC+4 (see `Ical.kt`). The JDK's copy is updated with the runtime.
 *
 * The definition covers [of]'s `from` onwards: the observance in force at that moment, the
 * transitions the database lists one by one after it, then the zone's ongoing rules as yearly
 * RRULEs — the shape other generators (vzic, tzurl.org) produce and every client understands.
 */
object VTimeZones {

    /** How far ahead onsets are listed for a rule that RRULE cannot express. */
    private const val LISTED_YEARS = 60

    private val EPOCH_ONSET: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0)
    private val SHORT_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH)

    fun of(zone: ZoneId, from: LocalDate): VTimeZone {
        val rules = zone.rules
        val start = from.atStartOfDay(zone).toInstant()
        val observances = mutableListOf<Observance>()

        // Whatever is in force when the first event starts. A zone that never changed offset
        // gets one STANDARD observance that has always applied.
        val inForce = rules.previousTransition(start)
        observances += if (inForce == null) {
            val offset = rules.getOffset(start)
            observance(daylight = false, onset = EPOCH_ONSET, from = offset, to = offset, name = nameAt(zone, start))
        } else {
            single(zone, inForce)
        }

        // Transitions the database lists one by one, up to where its ongoing yearly rules take over.
        val lastListed = rules.transitions.lastOrNull()
        var next = rules.nextTransition(start)
        while (next != null && lastListed != null && !next.instant.isAfter(lastListed.instant)) {
            observances += single(zone, next)
            next = rules.nextTransition(next.instant)
        }

        val rulesFrom = maxOf(start, lastListed?.instant ?: start)
        for (rule in rules.transitionRules) observances += yearly(zone, rule, rulesFrom)

        return VTimeZone(PropertyList(listOf(TzId(zone.id))), ComponentList(observances))
    }

    private fun single(zone: ZoneId, transition: ZoneOffsetTransition) = observance(
        daylight = zone.rules.isDaylightSavings(transition.instant),
        onset = transition.dateTimeBefore,
        from = transition.offsetBefore,
        to = transition.offsetAfter,
        name = nameAt(zone, transition.instant),
    )

    /** One ongoing rule as a yearly observance, starting with the first onset after [after]. */
    private fun yearly(zone: ZoneId, rule: ZoneOffsetTransitionRule, after: Instant): Observance {
        var year = after.atOffset(ZoneOffset.UTC).year - 1
        var first = rule.createTransition(year)
        while (!first.instant.isAfter(after)) first = rule.createTransition(++year)

        val daylight = rule.offsetAfter.totalSeconds > rule.standardOffset.totalSeconds
        val name = nameAt(zone, first.instant)
        val rrule = yearlyRule(rule, first)

        return if (rrule != null) {
            observance(daylight, first.dateTimeBefore, rule.offsetBefore, rule.offsetAfter, name, rrule = rrule)
        } else {
            // RRULE cannot say it, so list the onsets — far enough ahead to outlive any event written today.
            val later = (1..LISTED_YEARS).map { rule.createTransition(year + it).dateTimeBefore }
            observance(daylight, first.dateTimeBefore, rule.offsetBefore, rule.offsetAfter, name, rdates = later)
        }
    }

    /**
     * The RRULE repeating [rule] every year, or `null` where RRULE cannot express it: a "weekday on
     * or after the Nth" that can spill into the neighbouring month, or an onset whose wall-clock date
     * differs from the rule's nominal one (a 24:00 or UTC-defined time that crosses midnight).
     */
    private fun yearlyRule(rule: ZoneOffsetTransitionRule, first: ZoneOffsetTransition): String? {
        val month = rule.month
        val indicator = rule.dayOfMonthIndicator
        val weekday = rule.dayOfWeek?.let(WEEKDAY_CODES::getValue)

        val day = when {
            weekday == null -> "BYMONTHDAY=$indicator"
            indicator == -1 -> "BYDAY=-1$weekday"
            // The JDK keeps "last Sunday of October" as "Sunday on or after the 25th"; in a month of
            // fixed length the two are the same, and the first is the spelling every client knows.
            indicator > 0 && month.minLength() == month.maxLength() && indicator + 6 == month.maxLength() ->
                "BYDAY=-1$weekday"
            indicator > 0 && indicator + 6 > month.minLength() -> return null
            indicator < 0 && -indicator + 6 > month.minLength() -> return null
            // "Sunday on or after the 8th" is simply the second Sunday.
            indicator > 0 && (indicator - 1) % 7 == 0 -> "BYDAY=${(indicator - 1) / 7 + 1}$weekday"
            indicator > 0 -> "BYDAY=$weekday;BYMONTHDAY=${(indicator..indicator + 6).joinToString(",")}"
            else -> "BYDAY=$weekday;BYMONTHDAY=${(-indicator..-indicator + 6).joinToString(",") { "-$it" }}"
        }
        if (nominalDate(rule, first.dateTimeBefore.year) != first.dateTimeBefore.toLocalDate()) return null
        return "FREQ=YEARLY;BYMONTH=${month.value};$day"
    }

    /** The date [rule] names for [year] before its time of day is applied — what the RRULE produces. */
    private fun nominalDate(rule: ZoneOffsetTransitionRule, year: Int): LocalDate {
        val month = rule.month
        val indicator = rule.dayOfMonthIndicator
        val weekday = rule.dayOfWeek
        return if (indicator < 0) {
            val date = LocalDate.of(year, month, month.length(Year.isLeap(year.toLong())) + 1 + indicator)
            if (weekday == null) date else date.with(TemporalAdjusters.previousOrSame(weekday))
        } else {
            val date = LocalDate.of(year, month, indicator)
            if (weekday == null) date else date.with(TemporalAdjusters.nextOrSame(weekday))
        }
    }

    private fun observance(
        daylight: Boolean,
        onset: LocalDateTime,
        from: ZoneOffset,
        to: ZoneOffset,
        name: String,
        rrule: String? = null,
        rdates: List<LocalDateTime> = emptyList(),
    ): Observance {
        val properties = mutableListOf<Property>(
            TzName(name),
            TzOffsetFrom(from),
            TzOffsetTo(to),
            DtStart<LocalDateTime>(onset),
        )
        rrule?.let { properties += RRule<LocalDateTime>(ParameterList(), it) }
        if (rdates.isNotEmpty()) {
            properties += RDate<LocalDateTime>(ParameterList(), rdates.joinToString(",") { it.format(LOCAL_TEXT) })
        }
        val list = PropertyList(properties)
        return if (daylight) Daylight(list) else Standard(list)
    }

    private fun nameAt(zone: ZoneId, instant: Instant): String = SHORT_NAME.format(instant.atZone(zone))
}
