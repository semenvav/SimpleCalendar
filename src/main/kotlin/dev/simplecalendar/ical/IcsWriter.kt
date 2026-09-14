package dev.simplecalendar.ical

import dev.simplecalendar.model.EventTime
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.ComponentList
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.PropertyList
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStamp
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.LastModified
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.Sequence
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion
import java.io.StringReader
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.Temporal

/** The fields a person can actually set from the UI. */
data class EventDraft(
    val title: String,
    val description: String?,
    val location: String?,
    val time: EventTime,
)

/**
 * Builds and edits calendar resources.
 *
 * The important rule lives in [applyTo]: editing parses what the server already has and changes
 * only the fields the user touched. Regenerating the file from our own model would silently drop
 * everything we do not model — alarms, attendees, categories, colours, any `X-` property another
 * client put there — and the loss would only surface on somebody else's phone.
 */
object IcsWriter {

    private const val PRODID = "-//SimpleCalendar//RU"

    /** iCalendar's local date-time form: `20260915T200000`. */
    private val LOCAL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private const val MAX_LINE_OCTETS = 75

    /** A fresh single-event resource. */
    fun create(uid: String, draft: EventDraft): String {
        val event = VEvent(false)
        event.propertyList = PropertyList(
            listOf(
                Uid(uid),
                DtStamp(Instant.now()),
                Sequence(0),
            ),
        )
        applyDraft(event, draft, existingStart = null)
        return serialise(listOf(event), extraComponents = emptyList())
    }

    /**
     * Rewrites an existing resource with [draft], keeping every other component and property.
     *
     * Returns `null` when the resource holds no editable event — nothing to change.
     */
    fun applyTo(existingIcs: String, draft: EventDraft): String? {
        val calendar = CalendarBuilder().build(StringReader(existingIcs))
        val components = calendar.getComponents<CalendarComponent>()

        val events = components.filterIsInstance<VEvent>()
        val master = events.firstOrNull { it.recurrenceIdTemporal() == null } ?: return null

        applyDraft(master, draft, existingStart = master.startTemporal())
        bumpRevision(master)

        // Anything that is not our master event — VTIMEZONE, other VEVENTs, VALARM siblings —
        // travels through untouched.
        val others = components.filterNot { it === master }
        return serialise(listOf(master), others)
    }

    // --- internals ----------------------------------------------------------------------------

    private fun applyDraft(event: VEvent, draft: EventDraft, existingStart: Temporal?) {
        event.setProperty(Summary(draft.title))

        val description = draft.description?.takeIf { it.isNotBlank() }
        if (description != null) event.setProperty(Description(description))
        else event.clearProperty(Property.DESCRIPTION)

        val location = draft.location?.takeIf { it.isNotBlank() }
        if (location != null) event.setProperty(Location(location))
        else event.clearProperty(Property.LOCATION)

        when (val time = draft.time) {
            is EventTime.AllDay -> {
                event.setProperty(DtStart(time.start))
                event.setProperty(DtEnd(time.endExclusive))
            }
            is EventTime.Timed -> {
                val zone = (existingStart as? ZonedDateTime)?.zone
                event.setProperty(timedProperty(Property.DTSTART, time.start, zone))
                event.setProperty(timedProperty(Property.DTEND, time.end, zone))
            }
        }

        // We always write an explicit DTEND, so a leftover DURATION would contradict it.
        event.clearProperty(Property.DURATION)
    }

    /**
     * Builds DTSTART or DTEND for a timed value.
     *
     * A brand-new event ([zone] null) is written in UTC: unambiguous, needs no `VTIMEZONE`, read
     * correctly by every client. We deliberately never emit a `VTIMEZONE` of our own — ical4j's
     * bundled zone data is out of date (see the Europe/Moscow case in `Ical.kt`), and writing
     * stale rules into the family's server would spread that bug to every other client.
     *
     * An event that already carries a `TZID` keeps it, so editing somebody else's event does not
     * quietly change how it is stored. The value is then written as literal wall-clock text with
     * the `TZID` parameter attached, rather than handing ical4j a `ZonedDateTime` to format —
     * because formatting goes through the same stale zone table and would shift the time by the
     * difference between the real rules and ical4j's. Writing the wall clock verbatim keeps the
     * reader, the writer and every other client in agreement.
     */
    private fun timedProperty(name: String, value: ZonedDateTime, zone: ZoneId?): Property {
        if (zone == null) {
            val utc = value.toInstant()
            return if (name == Property.DTSTART) DtStart(utc) else DtEnd(utc)
        }

        val local = value.withZoneSameInstant(zone).toLocalDateTime().format(LOCAL_FORMAT)
        val parameters = ParameterList(listOf(TzId(zone.id)))
        return if (name == Property.DTSTART) {
            DtStart<ZonedDateTime>(parameters, local)
        } else {
            DtEnd<ZonedDateTime>(parameters, local)
        }
    }

    private fun bumpRevision(event: VEvent) {
        val current = event.getProperty<Sequence>(Property.SEQUENCE).orElse(null)?.sequenceNo ?: 0
        event.setProperty(Sequence(current + 1))
        event.setProperty(DtStamp(Instant.now()))
        event.setProperty(LastModified(Instant.now()))
    }

    private fun serialise(events: List<VEvent>, extraComponents: List<CalendarComponent>): String {
        val calendar = Calendar()
        calendar.propertyList = PropertyList(listOf(ImmutableVersion.VERSION_2_0, ProdId(PRODID)))
        // VTIMEZONE must precede the components that reference it.
        val ordered = extraComponents.sortedBy { if (it.name == "VTIMEZONE") 0 else 1 } + events
        calendar.componentList = ComponentList(ordered)

        val raw = StringWriter().use { writer ->
            // Validation off: ical4j rejects shapes real servers accept, and our own tests are the
            // check that matters.
            CalendarOutputter(false).output(calendar, writer)
            writer.toString()
        }
        return refold(raw)
    }

    /**
     * Re-folds the output so no line exceeds 75 **octets**.
     *
     * ical4j folds by characters. RFC 5545 counts octets, and in UTF-8 Cyrillic takes two per
     * character — so a line ical4j considers 73 long is 136 octets on the wire. For a calendar
     * written in Russian that is nearly every description, and strict parsers are within their
     * rights to complain. Cheaper to be correct here than to debug "some events look odd on my
     * phone" later.
     */
    private fun refold(ics: String): String {
        val unfolded = ics.replace("\r\n ", "").replace("\r\n\t", "")
        return unfolded.split("\r\n").joinToString("\r\n") { foldLine(it) }
    }

    private fun foldLine(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= MAX_LINE_OCTETS) return line

        val folded = StringBuilder(line.length + 16)
        var used = 0
        var index = 0
        while (index < line.length) {
            // Step by code point so a surrogate pair — an emoji in a title — is never split.
            val codePoint = line.codePointAt(index)
            val width = Character.charCount(codePoint)
            val piece = line.substring(index, index + width)
            val octets = piece.toByteArray(Charsets.UTF_8).size

            if (used + octets > MAX_LINE_OCTETS) {
                folded.append("\r\n ")
                used = 1 // the continuation line's leading space counts toward the limit
            }
            folded.append(piece)
            used += octets
            index += width
        }
        return folded.toString()
    }

    private fun VEvent.setProperty(property: Property) {
        propertyList = propertyList.replace(property)
    }

    private fun VEvent.clearProperty(name: String) {
        propertyList = propertyList.removeAll(name)
    }
}
