package dev.simplecalendar.ical

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.component.Observance
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.TzOffsetFrom
import net.fortuna.ical4j.model.property.TzOffsetTo
import dev.simplecalendar.ical.IcsWriter
import dev.simplecalendar.model.EventTime
import dev.simplecalendar.model.Frequency
import dev.simplecalendar.model.RepeatRule
import java.io.StringReader
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The VTIMEZONE we write must say what the JDK says.
 *
 * Checked by evaluating the definition the way RFC 5545 defines it — the offset at any moment is
 * the TZOFFSETTO of the latest onset at or before it — rather than through ical4j's own reader,
 * which has quirks of its own and is only one of the clients that will read these files.
 */
class VTimeZonesTest {

    @Test
    fun `the household zone matches the JDK for twenty years, through every switch`() {
        assertNull(mismatch(ZoneId.of("Asia/Jerusalem"), years = 20, stepHours = 1))
    }

    @Test
    fun `zones with and without summer time, in both hemispheres, match the JDK`() {
        val zones = listOf(
            "Europe/Berlin", "Europe/Moscow", "Europe/London", "America/New_York", "America/Sao_Paulo",
            "America/Santiago", "Australia/Sydney", "Pacific/Auckland", "Asia/Tokyo", "Asia/Tehran", "UTC",
        )
        val wrong = zones.mapNotNull { id -> mismatch(ZoneId.of(id), years = 20, stepHours = 3)?.let { "$id: $it" } }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `every zone the JDK knows comes out right`() {
        // Only the JDK's own tz database: once ical4j has run, it registers zones of its own in the
        // same ZoneId namespace, and those are not what this generator is for.
        val jdkZones = ZoneId.getAvailableZoneIds().intersect(java.util.TimeZone.getAvailableIDs().toSet())
        val wrong = jdkZones.sorted()
            .mapNotNull { id -> mismatch(ZoneId.of(id), years = 12, stepHours = 12)?.let { "$id: $it" } }
        assertTrue(jdkZones.size > 400, "expected the whole tz database, got ${jdkZones.size} zones")
        assertTrue(wrong.isEmpty(), "${wrong.size} zones disagree:\n" + wrong.take(20).joinToString("\n"))
    }

    @Test
    fun `Jerusalem is written the way other generators write it`() {
        val observances = VTimeZones.of(ZoneId.of("Asia/Jerusalem"), LocalDate.of(2026, 9, 15)).observances
        val rules = observances.mapNotNull { it.getProperty<RRule<*>>(Property.RRULE).orElse(null)?.value }
            .map { ruleParts(it).toMap() }

        // Summer time ends on the last Sunday of October...
        assertTrue(mapOf("FREQ" to "YEARLY", "BYMONTH" to "10", "BYDAY" to "-1SU") in rules, "$rules")
        // ...and starts on the Friday before the last Sunday of March, "Fri>=23" in tz terms.
        assertTrue(
            mapOf("FREQ" to "YEARLY", "BYMONTH" to "3", "BYDAY" to "FR", "BYMONTHDAY" to "23,24,25,26,27,28,29") in rules,
            "$rules",
        )
    }

    @Test
    fun `the definition survives being written out and read back`() {
        val jerusalem = ZoneId.of("Asia/Jerusalem")
        val start = ZonedDateTime.of(2026, 10, 20, 9, 0, 0, 0, jerusalem)
        val ics = IcsWriter.create(
            "tz@test",
            EventDraft("Садик", null, null, EventTime.Timed(start, start.plusHours(1))),
            repeat = RepeatRule(Frequency.WEEKLY),
            zone = jerusalem,
        )
        val parsed = CalendarBuilder().build(StringReader(ics)).getComponents<CalendarComponent>()
            .filterIsInstance<VTimeZone>().single()

        assertNull(mismatch(jerusalem, parsed, from = LocalDate.of(2026, 10, 20), years = 20, stepHours = 6))
    }

    // --- an RFC 5545 reading of a VTIMEZONE ---------------------------------------------------

    private class Onset(val at: Instant, val offset: ZoneOffset)

    /** Every onset of [vtz] up to [until], in order. */
    private fun onsets(vtz: VTimeZone, until: LocalDateTime): List<Onset> =
        vtz.observances.flatMap { observance -> onsetsOf(observance, until) }.sortedBy { it.at }

    @Suppress("UNCHECKED_CAST")
    private fun onsetsOf(observance: Observance, until: LocalDateTime): List<Onset> {
        val from = observance.getRequiredProperty<TzOffsetFrom>(Property.TZOFFSETFROM).offset
        val to = observance.getRequiredProperty<TzOffsetTo>(Property.TZOFFSETTO).offset
        val start = observance.getRequiredProperty<DtStart<LocalDateTime>>(Property.DTSTART).date

        val locals = mutableListOf(start)
        for (rrule in observance.getProperties<RRule<LocalDateTime>>(Property.RRULE)) {
            locals += rrule.recur.getDates(start, start, until)
        }
        for (rdate in observance.getProperties<RDate<LocalDateTime>>(Property.RDATE)) {
            locals += rdate.dates
        }
        // An onset is written as the local time just before it, in the offset it leaves behind.
        return locals.distinct().map { Onset(it.toInstant(from), to) }
    }

    private fun offsetAt(onsets: List<Onset>, instant: Instant): ZoneOffset? =
        onsets.lastOrNull { !it.at.isAfter(instant) }?.offset

    private fun mismatch(zone: ZoneId, years: Int, stepHours: Long): String? {
        val from = LocalDate.of(2026, 1, 1)
        return mismatch(zone, VTimeZones.of(zone, from), from, years, stepHours)
    }

    /** The first moment at which [vtz] and the JDK disagree about [zone], or `null` if they never do. */
    private fun mismatch(zone: ZoneId, vtz: VTimeZone, from: LocalDate, years: Int, stepHours: Long): String? {
        val start = from.atStartOfDay(zone).toInstant()
        val end = from.plusYears(years.toLong()).atStartOfDay(zone).toInstant()
        val onsets = onsets(vtz, LocalDateTime.ofInstant(end, ZoneOffset.UTC).plusDays(2))

        // A regular grid, plus both sides of every real transition, where an error would hide.
        val moments = generateSequence(start) { it.plus(Duration.ofHours(stepHours)) }.takeWhile { it.isBefore(end) } +
            generateSequence(zone.rules.nextTransition(start)) { zone.rules.nextTransition(it.instant) }
                .takeWhile { it.instant.isBefore(end) }
                .flatMap { sequenceOf(it.instant.minusSeconds(1), it.instant) }

        for (moment in moments) {
            val expected = zone.rules.getOffset(moment)
            val actual = offsetAt(onsets, moment)
            if (actual != expected) return "at $moment the JDK says $expected, the VTIMEZONE says $actual"
        }
        return null
    }

    @Test
    fun `a zone that never changes gets a single standing observance`() {
        val observances = VTimeZones.of(ZoneId.of("UTC"), LocalDate.of(2026, 1, 1)).observances
        assertEquals(1, observances.size)
    }
}
