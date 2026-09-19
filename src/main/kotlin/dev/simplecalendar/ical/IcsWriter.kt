package dev.simplecalendar.ical

import dev.simplecalendar.model.EventMark
import dev.simplecalendar.model.EventTime
import dev.simplecalendar.model.RepeatRule
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.ComponentList
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.PropertyList
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStamp
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.LastModified
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.RecurrenceId
import net.fortuna.ical4j.model.property.Sequence
import net.fortuna.ical4j.model.property.Status
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.XProperty
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion
import java.io.StringReader
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
 *
 * Repeating events are edited by [SeriesEditor], which is built from the same pieces.
 */
object IcsWriter {

    private const val PRODID = "-//SimpleCalendar//RU"

    private const val MAX_LINE_OCTETS = 75

    /**
     * A fresh resource.
     *
     * A single event goes out in UTC: unambiguous, needs no `VTIMEZONE`, read correctly by every
     * client. A repeating one cannot — in UTC a weekly 09:00 would slide by an hour at the next
     * DST change — so it is written with the household [zone]'s TZID and a VTIMEZONE built from
     * the JDK's database ([VTimeZones]).
     */
    fun create(uid: String, draft: EventDraft, repeat: RepeatRule? = null, zone: ZoneId? = null): String {
        val event = VEvent(false)
        event.propertyList = PropertyList(
            listOf(
                Uid(uid),
                DtStamp(Instant.now()),
                Sequence(0),
            ),
        )
        val household = zone ?: draftZone(draft)
        val timedForm = if (repeat == null) TimeForm.Utc else seriesForm(household)
        applyDraft(event, draft, timedForm, household)

        if (repeat != null) {
            val form = if (draft.time is EventTime.AllDay) TimeForm.Date else timedForm
            event.setProperty(RRule<Temporal>(ParameterList(), repeat.toRRuleValue(form, household)))
        }
        return serialise(withTimeZones(listOf(event), household))
    }

    /**
     * Rewrites an existing single event with [draft], keeping every other component and property.
     *
     * Returns `null` when the resource holds no editable event — nothing to change.
     */
    fun applyTo(existingIcs: String, draft: EventDraft): String? {
        val components = CalendarBuilder().build(StringReader(existingIcs)).getComponents<CalendarComponent>()
        val master = components.filterIsInstance<VEvent>().firstOrNull { it.recurrenceIdTemporal() == null }
            ?: return null

        val household = draftZone(draft)
        applyDraft(master, draft, timedFormOf(master), household)
        bumpRevision(master)

        // Anything that is not our master event — VTIMEZONE, other VEVENTs, VALARM siblings —
        // travels through untouched.
        return serialise(withTimeZones(components, household))
    }

    // --- building blocks, shared with SeriesEditor --------------------------------------------

    /**
     * The form a single event's timed values go out in: the one it already uses, so that editing
     * somebody else's event does not quietly change how it is stored — or UTC, for an event that
     * was all-day until now.
     */
    internal fun timedFormOf(event: VEvent): TimeForm = when (val form = event.timeForm()) {
        TimeForm.Date, null -> TimeForm.Utc
        else -> form
    }

    /** What a repeating event is written in: a real zone, unless the household zone is a bare offset. */
    internal fun seriesForm(zone: ZoneId): TimeForm =
        if (zone is ZoneOffset) TimeForm.Utc else TimeForm.Zoned(zone.id, zone)

    /** Writes [draft] into [event]; a timed draft goes out in [timedForm], an all-day one as dates. */
    internal fun applyDraft(event: VEvent, draft: EventDraft, timedForm: TimeForm, household: ZoneId) {
        event.setProperty(Summary(draft.title))
        event.setText(Property.DESCRIPTION, draft.description)
        event.setText(Property.LOCATION, draft.location)
        writeTimes(event, draft.time, timedForm, household)
    }

    /** Sets DTSTART and DTEND; [timedForm] applies to a timed [time] and must not be [TimeForm.Date]. */
    internal fun writeTimes(event: VEvent, time: EventTime, timedForm: TimeForm, household: ZoneId) {
        when (time) {
            is EventTime.AllDay -> {
                event.setProperty(DtStart(time.start))
                event.setProperty(DtEnd(time.endExclusive))
            }
            is EventTime.Timed -> {
                val form = if (timedForm == TimeForm.Date) TimeForm.Utc else timedForm
                event.setProperty(dateProperty(Property.DTSTART, time.start, form, household))
                event.setProperty(dateProperty(Property.DTEND, time.end, form, household))
            }
        }
        // We always write an explicit DTEND, so a leftover DURATION would contradict it.
        event.clearProperty(Property.DURATION)
    }

    /**
     * DTSTART, DTEND or RECURRENCE-ID holding [value] in [form].
     *
     * Built from literal text (see [TimeForm.render]): a zoned value keeps its `TZID` and goes out
     * as the wall-clock reading from the JDK's rules, which keeps the reader, the writer and every
     * other client in agreement. Handing ical4j a `ZonedDateTime` to format would route it through
     * ical4j's stale zone table and shift it — caught by `editing keeps the zone the event already used`.
     */
    internal fun dateProperty(name: String, value: Temporal, form: TimeForm, household: ZoneId): Property {
        val (parameters, text) = form.render(value, household)
        return when (name) {
            Property.DTSTART -> DtStart<Temporal>(parameters, text)
            Property.DTEND -> DtEnd<Temporal>(parameters, text)
            Property.RECURRENCE_ID -> RecurrenceId<Temporal>(parameters, text)
            else -> throw IllegalArgumentException("$name is not a single-date property")
        }
    }

    /** EXDATE or RDATE holding [values], all in [form]. */
    internal fun dateListProperty(name: String, values: List<Temporal>, form: TimeForm, household: ZoneId): Property {
        val rendered = values.map { form.render(it, household) }
        val parameters = rendered.first().first
        val text = rendered.joinToString(",") { it.second }
        return when (name) {
            Property.EXDATE -> ExDate<Temporal>(parameters, text)
            Property.RDATE -> RDate<Temporal>(parameters, text)
            else -> throw IllegalArgumentException("$name is not a date-list property")
        }
    }

    /**
     * Writes the hand-made mark, or takes it off when [mark] is null.
     *
     * A cancellation also goes out as `STATUS:CANCELLED`, which every calendar understands; a
     * postponement has no such equivalent and lives in our property alone. Taking a mark off
     * clears both, including a `STATUS:CANCELLED` that came from somewhere else — otherwise the
     * event would read as cancelled again on the next sync.
     */
    internal fun setMark(event: VEvent, mark: EventMark?) {
        event.clearProperty(MARK_PROPERTY)
        when (mark) {
            null -> if (event.statusValue()?.equals("CANCELLED", ignoreCase = true) == true) {
                event.clearProperty(Property.STATUS)
            }
            EventMark.CANCELLED -> {
                event.setProperty(XProperty(MARK_PROPERTY, mark.name))
                event.setProperty(Status(ParameterList(), Status.VALUE_CANCELLED))
            }
            EventMark.MOVED -> {
                event.setProperty(XProperty(MARK_PROPERTY, mark.name))
                if (event.statusValue()?.equals("CANCELLED", ignoreCase = true) == true) {
                    event.clearProperty(Property.STATUS)
                }
            }
        }
    }

    internal fun bumpRevision(event: VEvent) {
        val current = event.getProperty<Sequence>(Property.SEQUENCE).orElse(null)?.sequenceNo ?: 0
        event.setProperty(Sequence(current + 1))
        event.setProperty(DtStamp(Instant.now()))
        event.setProperty(LastModified(Instant.now()))
    }

    /**
     * Adds a VTIMEZONE for every TZID the events use but the file does not define, built from the
     * JDK's database. A TZID the JDK does not know is left as it is — only whoever wrote it can
     * define it.
     */
    internal fun withTimeZones(components: List<CalendarComponent>, household: ZoneId): List<CalendarComponent> {
        val defined = components.filterIsInstance<VTimeZone>()
            .mapNotNull { it.getProperty<Property>(Property.TZID).orElse(null)?.value }
            .toSet()
        val events = components.filterIsInstance<VEvent>()
        val missing = events.flatMap { event -> event.propertyList.all.mapNotNull { it.tzidValue() } }
            .distinct()
            .filter { it !in defined }
        if (missing.isEmpty()) return components

        val from = events.mapNotNull { it.startTemporal() }.minOfOrNull { it.toInstantIn(household) }
            ?.atZone(household)?.toLocalDate()
            ?: return components
        val added = missing.mapNotNull { tzid ->
            runCatching { ZoneId.of(tzid) }.getOrNull()?.let { VTimeZones.of(it, from) }
        }
        return added + components
    }

    internal fun serialise(components: List<CalendarComponent>): String {
        val calendar = Calendar()
        calendar.propertyList = PropertyList(listOf(ImmutableVersion.VERSION_2_0, ProdId(PRODID)))
        // VTIMEZONE must precede the components that reference it.
        calendar.componentList = ComponentList(components.sortedBy { if (it is VTimeZone) 0 else 1 })

        val raw = StringWriter().use { writer ->
            // Validation off: ical4j rejects shapes real servers accept, and our own tests are the
            // check that matters.
            CalendarOutputter(false).output(calendar, writer)
            writer.toString()
        }
        return refold(raw)
    }

    /** A timed draft's own zone — the household's, since the API reads times there. */
    private fun draftZone(draft: EventDraft): ZoneId =
        (draft.time as? EventTime.Timed)?.start?.zone ?: ZoneOffset.UTC

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
}

internal fun VEvent.setProperty(property: Property) {
    propertyList = propertyList.replace(property)
}

internal fun VEvent.clearProperty(name: String) {
    propertyList = propertyList.removeAll(name)
}

/** Sets SUMMARY, DESCRIPTION or LOCATION; an empty value removes the property rather than blanking it. */
internal fun VEvent.setText(name: String, value: String?) {
    val text = value?.takeIf { it.isNotBlank() }
    if (text == null) {
        clearProperty(name)
        return
    }
    setProperty(
        when (name) {
            Property.SUMMARY -> Summary(text)
            Property.DESCRIPTION -> Description(text)
            Property.LOCATION -> Location(text)
            else -> throw IllegalArgumentException("$name is not a text property")
        },
    )
}
