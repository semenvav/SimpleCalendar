package dev.simplecalendar.ical

import dev.simplecalendar.model.EventMark
import dev.simplecalendar.model.EventTime
import dev.simplecalendar.model.RepeatRule
import dev.simplecalendar.model.isAllDay
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.Recur
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.property.DateListProperty
import net.fortuna.ical4j.model.property.DtStamp
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.Sequence
import net.fortuna.ical4j.model.property.Uid
import java.io.StringReader
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.Temporal
import java.util.UUID

/** Which part of a repeating series an edit or a delete applies to. */
enum class EditScope { THIS, FOLLOWING, ALL }

/**
 * What an edit turns into on the server.
 *
 * [updated] is the rewritten resource, or `null` when it should be deleted outright. [created] is
 * a second, brand-new resource: the tail of a series split by a "this and following" edit.
 */
data class SeriesChange(val updated: String?, val created: CreatedResource? = null)

data class CreatedResource(val uid: String, val ics: String)

/** The instance an edit names is not part of the series — deleted elsewhere, or never there. */
class NoSuchInstanceException(message: String) : RuntimeException(message)

/**
 * Edits and deletes repeating events: one instance, this one and the ones after it, or all.
 *
 * Works on the parsed resource the way [IcsWriter.applyTo] does — changes what the edit is about
 * and carries everything else through: alarms, attendees, properties we have never heard of.
 *
 * - **This one** becomes an override: a copy of the master whose `RECURRENCE-ID` names the slot,
 *   holding the form's values. Deleting it adds an `EXDATE`.
 * - **All** changes the master, and only in what the form actually changed: an instance whose
 *   title was edited separately keeps it when the whole series moves to another hour. Overrides
 *   and exceptions move along with the series, or they would point at slots that no longer exist
 *   — an override left behind turns into an extra event, an EXDATE left behind stops excluding.
 * - **This and following** splits the series: the original ends the moment before the instance
 *   (UNTIL), and a new series with its own UID starts there and takes the edit as "all".
 *
 * Instances are named by instance ids (`toInstanceId`) and matched by [recurrenceKey], so it does
 * not matter which of the four forms ([TimeForm]) the file writes its times in.
 */
class SeriesEditor(private val zone: ZoneId, private val expander: EventExpander) {

    fun edit(
        ics: String,
        instanceId: String?,
        scope: EditScope,
        draft: EventDraft,
        repeat: RepeatChange = RepeatChange.Keep,
    ): SeriesChange {
        val series = parse(ics)
        val master = series.master
        val instance = instanceId?.let(::parseInstanceId)

        when {
            // Overrides with no master: nothing to spread an edit over, each stands alone.
            master == null -> editOrphan(series, instance, draft)
            !master.repeats() -> editSingle(master, draft, repeat)
            scope == EditScope.THIS && instance != null -> editThis(series, instance, draft, repeat)
            scope == EditScope.FOLLOWING && instance != null && hasInstancesBefore(series, instance) -> {
                requireInstance(series, instance)
                val before = snapshot(series, instance)
                val uid = UUID.randomUUID().toString()
                val tail = tailFrom(series, instance, uid)
                truncate(series, instance)
                editAll(tail, instance, draft, repeat, before)
                return SeriesChange(updated = render(series), created = CreatedResource(uid, render(tail)))
            }
            else -> {
                // "All", or "this and following" from the very first instance, which is the same.
                if (instance != null) requireInstance(series, instance)
                val at = instance ?: master.startTemporal() ?: throw NoSuchInstanceException(GONE)
                editAll(series, at, draft, repeat, snapshot(series, at))
            }
        }
        return SeriesChange(updated = render(series))
    }

    /**
     * Puts a hand-made mark on an occurrence — cancelled, moved — or takes it off again.
     *
     * Nothing about when the event happens changes, which is why "this and following" is not on
     * offer: splitting a series into two resources for the sake of a colour would be a real
     * change to the calendar made for a cosmetic reason. One instance, or the whole series.
     */
    fun mark(ics: String, instanceId: String?, scope: EditScope, mark: EventMark?): SeriesChange {
        require(scope != EditScope.FOLLOWING) {
            "Отметку можно поставить на одно событие или на всю серию."
        }
        val series = parse(ics)
        val master = series.master
        val instance = instanceId?.let(::parseInstanceId)

        when {
            master == null -> {
                val key = instance?.recurrenceKey(zone)
                val target = series.overrides.firstOrNull { key == null || it.slotKey() == key }
                    ?: throw NoSuchInstanceException(GONE)
                applyMark(target, mark)
            }
            !master.repeats() -> applyMark(master, mark)
            scope == EditScope.THIS && instance != null -> {
                requireInstance(series, instance)
                val target = series.overrideFor(instance)
                    ?: newOverride(master, instance).also { series.overrides += it }
                applyMark(target, mark)
            }
            // The whole series: the master, and every instance that already stands apart from it —
            // an override left unmarked would be the one entry on the wall still looking ordinary.
            else -> {
                applyMark(master, mark)
                series.overrides.forEach { applyMark(it, mark) }
            }
        }
        return SeriesChange(updated = render(series))
    }

    fun delete(ics: String, instanceId: String?, scope: EditScope): SeriesChange {
        val instance = instanceId?.let(::parseInstanceId)
        if (instance == null || scope == EditScope.ALL) return SeriesChange(updated = null)

        val series = parse(ics)
        val master = series.master
        val key = instance.recurrenceKey(zone)

        if (master == null) {
            val cut = instance.toInstantIn(zone)
            if (scope == EditScope.THIS) series.overrides.removeAll { it.slotKey() == key }
            else series.overrides.removeAll { it.slotInstant()?.isBefore(cut) == false }
            return SeriesChange(updated = if (series.overrides.isEmpty()) null else render(series))
        }
        if (!master.repeats()) return SeriesChange(updated = null)

        requireInstance(series, instance)
        if (scope == EditScope.FOLLOWING) {
            if (!hasInstancesBefore(series, instance)) return SeriesChange(updated = null)
            truncate(series, instance)
        } else {
            series.overrides.removeAll { it.slotKey() == key }
            val form = master.timeForm() ?: TimeForm.Utc
            master.propertyList = master.propertyList
                .add(IcsWriter.dateListProperty(Property.EXDATE, listOf(instance), form, zone))
            IcsWriter.bumpRevision(master)
        }
        return SeriesChange(updated = render(series))
    }

    // --- edits --------------------------------------------------------------------------------

    private fun applyMark(event: VEvent, mark: EventMark?) {
        IcsWriter.setMark(event, mark)
        IcsWriter.bumpRevision(event)
    }

    private fun editOrphan(series: Series, instance: Temporal?, draft: EventDraft) {
        val key = instance?.recurrenceKey(zone)
        val target = series.overrides.firstOrNull { key == null || it.slotKey() == key }
            ?: throw NoSuchInstanceException(GONE)
        IcsWriter.applyDraft(target, draft, IcsWriter.timedFormOf(target), zone)
        IcsWriter.bumpRevision(target)
    }

    /** A single event; given a rule it becomes a series, which needs a real zone to keep its hour. */
    private fun editSingle(master: VEvent, draft: EventDraft, repeat: RepeatChange) {
        val rule = (repeat as? RepeatChange.To)?.rule
        val timedForm = when (val form = master.timeForm()) {
            is TimeForm.Zoned -> form
            TimeForm.Floating -> form
            else -> if (rule == null) IcsWriter.timedFormOf(master) else IcsWriter.seriesForm(zone)
        }
        IcsWriter.applyDraft(master, draft, timedForm, zone)
        if (rule != null) {
            val form = if (draft.time is EventTime.AllDay) TimeForm.Date else timedForm
            master.setProperty(RRule<Temporal>(ParameterList(), rule.toRRuleValue(form, zone)))
        }
        IcsWriter.bumpRevision(master)
    }

    private fun editThis(series: Series, instance: Temporal, draft: EventDraft, repeat: RepeatChange) {
        val master = checkNotNull(series.master)
        require(!changesRule(master, repeat)) {
            "Правило повторения меняется для всей серии или начиная с этого события — не для одного."
        }
        requireInstance(series, instance)

        val target = series.overrideFor(instance) ?: newOverride(master, instance).also { series.overrides += it }
        IcsWriter.applyDraft(target, draft, IcsWriter.timedFormOf(target), zone)
        IcsWriter.bumpRevision(target)
    }

    /**
     * Applies an edit of [instance] to the whole of [series].
     *
     * [before] is how the edited instance looked when the form was filled in — the edit is what
     * differs from it, and only that is spread over the series.
     */
    private fun editAll(series: Series, instance: Temporal, draft: EventDraft, repeat: RepeatChange, before: Snapshot) {
        val master = checkNotNull(series.master)
        val edited = series.overrideFor(instance)

        if (repeat is RepeatChange.To && repeat.rule == null) {
            // Not repeating any more: what is left is exactly the event the form shows.
            for (name in SERIES_ONLY) master.clearProperty(name)
            series.overrides.clear()
            IcsWriter.applyDraft(master, draft, IcsWriter.timedFormOf(master), zone)
            IcsWriter.bumpRevision(master)
            return
        }

        val oldRule = expander.repeatOf(master)
        val oldText = Texts(master.summaryValue(), master.descriptionValue(), master.locationValue())

        if (!sameTime(draft.time, before.time)) moveSeries(series, before, draft)
        spreadText(series, draft, before, oldText)

        val rule = (repeat as? RepeatChange.To)?.rule
        if (rule != null && rule != oldRule) replaceRule(series, rule, oldRule)

        // The edited instance ends up exactly as the form says, even when it was an override with
        // changes of its own — unless the new rule above has just discarded it.
        if (edited != null && series.overrides.any { it === edited }) {
            IcsWriter.applyDraft(edited, draft, IcsWriter.timedFormOf(edited), zone)
        }
        IcsWriter.bumpRevision(master)
    }

    /**
     * Moves every instance of the series the way the edited one moved.
     *
     * The three possible changes are taken apart: a new date shifts the series by the same number
     * of days, a new time of day sets it for all, a new length applies to all. That is what lets an
     * instance that had been moved to another day on its own keep that day when only the hour of
     * the series changes.
     */
    private fun moveSeries(series: Series, before: Snapshot, draft: EventDraft) {
        val master = checkNotNull(series.master)
        val oldForm = master.timeForm() ?: return
        val oldStart = master.startTemporal() ?: return
        val oldLength = master.duration()
        val seriesZone = (oldForm as? TimeForm.Zoned)?.zone ?: zone

        val allDayChanged = before.time.isAllDay != draft.time.isAllDay
        val newForm = when {
            draft.time is EventTime.AllDay -> TimeForm.Date
            oldForm == TimeForm.Date -> IcsWriter.seriesForm(zone)
            else -> oldForm
        }
        val was = before.time.startIn(seriesZone)
        val now = draft.time.startIn(seriesZone)
        val fromTime = if (oldForm == TimeForm.Date) null else oldStart.wallClockIn(seriesZone, zone).toLocalTime()
        val toTime = when {
            draft.time is EventTime.AllDay -> null
            allDayChanged || was.toLocalTime() != now.toLocalTime() -> now.toLocalTime()
            else -> fromTime
        }
        val shift = Shift(ChronoUnit.DAYS.between(was.toLocalDate(), now.toLocalDate()), fromTime, toTime, seriesZone, newForm)
        val newLength = if (allDayChanged || before.time.length() != draft.time.length()) draft.time.length() else oldLength

        IcsWriter.writeTimes(master, eventTimeOf(shift.apply(oldStart), newLength, zone), newForm, zone)
        rewriteDates(master, Property.EXDATE, newForm, shift::apply)
        rewriteDates(master, Property.RDATE, newForm, shift::apply)
        rewriteRules(master) { parts, recur ->
            val until = recur.until ?: return@rewriteRules parts
            parts.map { if (it.first == "UNTIL") "UNTIL" to movedUntil(until, shift, seriesZone, newForm) else it }
        }

        for (override in series.overrides) {
            val slot = override.recurrenceIdTemporal() ?: continue
            val start = override.startTemporal()
            // An override that only changed, say, the title sits in its own slot and moves with the
            // series; one that was rescheduled keeps the time somebody chose for it.
            val rescheduled = start == null || start.recurrenceKey(zone) != slot.recurrenceKey(zone)
            val newSlot = shift.apply(slot)
            override.setProperty(IcsWriter.dateProperty(Property.RECURRENCE_ID, newSlot, newForm, zone))
            if (!rescheduled) {
                val ownLength = override.duration()
                val length = if (!allDayChanged && ownLength != oldLength) ownLength else newLength
                IcsWriter.writeTimes(override, eventTimeOf(newSlot, length, zone), newForm, zone)
            }
            IcsWriter.bumpRevision(override)
        }
    }

    /**
     * Where an UNTIL goes when its series moves.
     *
     * An UNTIL at the very end of a day means "through that day" — ours are written that way — and
     * stays so on the shifted day: moved an hour later like an instance, it would cross midnight
     * and the series would read as ending a day later. Any other UNTIL sits at an exact moment,
     * usually an instance or the second before one, and moves with the instances so that it keeps
     * exactly the ones it kept before — a series cut short by a split stays cut.
     */
    private fun movedUntil(until: Temporal, shift: Shift, seriesZone: ZoneId, form: TimeForm): String {
        val wall = until.wallClockIn(seriesZone, zone)
        return if (until !is LocalDate && !wall.toLocalTime().isBefore(LAST_MINUTE)) {
            untilThrough(wall.toLocalDate().plusDays(shift.days), form, zone)
        } else {
            form.untilText(shift.apply(until), zone)
        }
    }

    /**
     * Applies the text fields the form changed to the master, and to every override that still
     * had the master's old value. An override with a title of its own keeps it.
     */
    private fun spreadText(series: Series, draft: EventDraft, before: Snapshot, old: Texts) {
        val master = checkNotNull(series.master)

        fun spread(name: String, value: String?, oldValue: String?, read: (VEvent) -> String?) {
            master.setText(name, value)
            series.overrides
                .filter { read(it).blankToNull() == oldValue.blankToNull() }
                .forEach { it.setText(name, value) }
        }

        if (draft.title != before.title) spread(Property.SUMMARY, draft.title, old.title) { it.summaryValue() }
        if (draft.description != before.description) {
            spread(Property.DESCRIPTION, draft.description, old.description) { it.descriptionValue() }
        }
        if (draft.location != before.location) spread(Property.LOCATION, draft.location, old.location) { it.locationValue() }
    }

    private fun replaceRule(series: Series, rule: RepeatRule, oldRule: RepeatRule?) {
        val master = checkNotNull(series.master)
        val form = master.timeForm() ?: return

        if (oldRule != null && oldRule.frequency == rule.frequency && oldRule.interval == rule.interval) {
            // Only the end moved. The rule keeps its own spelling, and the overrides and exceptions
            // that still fall inside it stay.
            rewriteRules(master) { parts, _ ->
                val kept = parts.without("COUNT", "UNTIL")
                if (rule.until == null) kept else kept + ("UNTIL" to untilThrough(rule.until, form, zone))
            }
            rule.until?.let { last ->
                val seriesZone = (form as? TimeForm.Zoned)?.zone ?: zone
                dropFrom(series, last.plusDays(1).atStartOfDay(seriesZone).toInstant())
            }
        } else {
            // A different rule makes different slots; the old overrides and exceptions name slots that
            // are gone, and left in place would show up as extra events.
            for (name in SERIES_ONLY) master.clearProperty(name)
            master.setProperty(RRule<Temporal>(ParameterList(), rule.toRRuleValue(form, zone)))
            series.overrides.clear()
        }
    }

    // --- splitting ----------------------------------------------------------------------------

    /** Ends the series the moment before [instance], leaving nothing of it after the cut. */
    private fun truncate(series: Series, instance: Temporal) {
        val master = checkNotNull(series.master)
        val form = master.timeForm() ?: return
        val seed = master.startTemporal() ?: return
        val cut = instance.toInstantIn(zone)

        rewriteRules(master) { parts, recur ->
            val ends = recur.until?.toInstantIn(zone)
                ?: if (recur.count > 0) expander.lastStart(recur, seed)?.toInstantIn(zone) else null
            // A rule already over by then stays as it is; UNTIL would only lengthen a short COUNT.
            if (ends != null && ends.isBefore(cut)) parts
            else parts.without("COUNT", "UNTIL") + ("UNTIL" to untilBefore(instance, form, zone))
        }
        dropFrom(series, cut)
        IcsWriter.bumpRevision(master)
    }

    /** The part of [series] from [instance] on, as a new series with its own [uid]. */
    private fun tailFrom(series: Series, instance: Temporal, uid: String): Series {
        val master = checkNotNull(series.master)
        val form = master.timeForm() ?: TimeForm.Utc
        val seed = checkNotNull(master.startTemporal())
        val cut = instance.toInstantIn(zone)

        val head = master.copy()
        head.setProperty(Uid(uid))
        head.setProperty(Sequence(0))
        head.setProperty(DtStamp(Instant.now()))
        head.clearProperty(Property.LAST_MODIFIED)
        IcsWriter.writeTimes(head, eventTimeOf(instance, master.duration(), zone), form, zone)

        // Whatever a COUNT spent before the cut does not carry over.
        rewriteRules(head) { parts, recur ->
            if (recur.count <= 0) parts
            else parts.map {
                if (it.first == "COUNT") "COUNT" to "${recur.count - expander.generatedBefore(recur, seed, instance)}" else it
            }
        }
        val fromCut = { value: Temporal -> value.takeIf { !it.toInstantIn(zone).isBefore(cut) } }
        rewriteDates(head, Property.EXDATE, form, fromCut)
        rewriteDates(head, Property.RDATE, form, fromCut)

        val overrides = series.overrides
            .filter { it.slotInstant()?.isBefore(cut) == false }
            .map { it.copy().apply { setProperty(Uid(uid)) } }
        // The new file needs the zone definitions its times refer to.
        val zones = series.others.filterIsInstance<VTimeZone>().map { it.copy() }
        return Series(zones, head, overrides.toMutableList())
    }

    /** Removes overrides, exceptions and extra dates from [cut] on. */
    private fun dropFrom(series: Series, cut: Instant) {
        val master = checkNotNull(series.master)
        val form = master.timeForm() ?: return
        val beforeCut = { value: Temporal -> value.takeIf { it.toInstantIn(zone).isBefore(cut) } }
        rewriteDates(master, Property.EXDATE, form, beforeCut)
        rewriteDates(master, Property.RDATE, form, beforeCut)
        series.overrides.removeAll { it.slotInstant()?.isBefore(cut) == false }
    }

    /** Whether anything of the series comes before [instance]; if not, "this and following" is all of it. */
    private fun hasInstancesBefore(series: Series, instance: Temporal): Boolean {
        val seed = series.master?.startTemporal() ?: return false
        val cut = instance.toInstantIn(zone)
        val from = seed.toInstantIn(zone).minus(Duration.ofDays(1))
        if (!from.isBefore(cut)) return false
        return expander.expand(series.parsed(), "", "", from, cut).any { occurrence ->
            occurrence.recurrenceId?.let(::parseInstanceId)?.toInstantIn(zone)?.isBefore(cut) == true
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun requireInstance(series: Series, instance: Temporal) {
        if (series.overrideFor(instance) != null) return
        val key = instance.recurrenceKey(zone)
        val at = instance.toInstantIn(zone)
        val exists = expander.expand(series.parsed(), "", "", at, at.plusMillis(1))
            .any { it.recurrenceId?.let(::parseInstanceId)?.recurrenceKey(zone) == key }
        if (!exists) throw NoSuchInstanceException(GONE)
    }

    private fun changesRule(master: VEvent, change: RepeatChange): Boolean =
        change is RepeatChange.To && (change.rule == null || change.rule != expander.repeatOf(master))

    /**
     * A copy of the master standing in for the one instance in [instance]'s slot.
     *
     * It starts out saying exactly what the series says for that slot — the master's own DTSTART
     * would put it on the day the series began. An edit overwrites the times straight afterwards;
     * a mark leaves them, and has to find them right.
     */
    private fun newOverride(master: VEvent, instance: Temporal): VEvent {
        val override = master.copy()
        for (name in SERIES_ONLY) override.clearProperty(name)
        val form = master.timeForm() ?: TimeForm.Utc
        override.setProperty(IcsWriter.dateProperty(Property.RECURRENCE_ID, instance, form, zone))
        IcsWriter.writeTimes(override, eventTimeOf(instance, master.duration(), zone), form, zone)
        return override
    }

    private fun snapshot(series: Series, instance: Temporal): Snapshot {
        val master = checkNotNull(series.master)
        val override = series.overrideFor(instance)
        val source = override ?: master
        return Snapshot(
            title = source.summaryValue().blankToNull() ?: UNTITLED,
            description = source.descriptionValue().blankToNull(),
            location = source.locationValue().blankToNull(),
            time = eventTimeOf(override?.startTemporal() ?: instance, source.duration(), zone),
        )
    }

    private fun sameTime(a: EventTime, b: EventTime): Boolean = when {
        a is EventTime.AllDay && b is EventTime.AllDay -> a == b
        a is EventTime.Timed && b is EventTime.Timed ->
            a.start.toInstant() == b.start.toInstant() && a.end.toInstant() == b.end.toInstant()
        else -> false
    }

    private fun EventTime.startIn(zone: ZoneId): LocalDateTime = when (this) {
        is EventTime.AllDay -> start.atStartOfDay()
        is EventTime.Timed -> start.withZoneSameInstant(zone).toLocalDateTime()
    }

    /** Rewrites every RRULE through [change]; the parsed rule comes along for its typed UNTIL and COUNT. */
    private fun rewriteRules(event: VEvent, change: (RuleParts, Recur<Temporal>) -> RuleParts) {
        val properties = event.getProperties<Property>(Property.RRULE)
        if (properties.isEmpty()) return
        var list = event.propertyList.removeAll(Property.RRULE)
        for ((property, recur) in properties.zip(event.recurrenceRules())) {
            list = list.add(RRule<Temporal>(ParameterList(), change(ruleParts(property.value), recur).joinRule()))
        }
        event.propertyList = list
    }

    /** Rewrites every value of EXDATE or RDATE through [change], in [form]; `null` drops the value. */
    private fun rewriteDates(event: VEvent, name: String, form: TimeForm, change: (Temporal) -> Temporal?) {
        val properties = event.getProperties<DateListProperty<Temporal>>(name)
        if (properties.isEmpty()) return
        var list = event.propertyList.removeAll(name)
        for (property in properties) {
            if (property.isPeriodList()) {
                list = list.add(property)
                continue
            }
            val values = property.resolvedDates().mapNotNull(change)
            if (values.isNotEmpty()) list = list.add(IcsWriter.dateListProperty(name, values, form, zone))
        }
        event.propertyList = list
    }

    private fun VEvent.slotKey(): String? = recurrenceIdTemporal()?.recurrenceKey(zone)

    private fun VEvent.slotInstant(): Instant? = recurrenceIdTemporal()?.toInstantIn(zone)

    private fun Series.overrideFor(instance: Temporal): VEvent? {
        val key = instance.recurrenceKey(zone)
        return overrides.firstOrNull { it.slotKey() == key }
    }

    private fun render(series: Series): String =
        IcsWriter.serialise(IcsWriter.withTimeZones(series.components(), zone))

    private fun parse(ics: String): Series {
        val components = CalendarBuilder().build(StringReader(ics)).getComponents<CalendarComponent>()
        val events = components.filterIsInstance<VEvent>()
        val master = events.firstOrNull { it.recurrenceIdTemporal() == null }
        val uid = (master ?: events.firstOrNull())?.uidValue()
        val overrides = events.filter { it.recurrenceIdTemporal() != null && it.uidValue() == uid }
        val others = components.filter { component -> component !== master && overrides.none { it === component } }
        return Series(others, master, overrides.toMutableList())
    }

    /**
     * Where the instances of a series go when it moves: [days] later or earlier, and from
     * [fromTime] of day to [toTime] — `null` on either side meaning all-day.
     */
    private inner class Shift(
        val days: Long,
        private val fromTime: LocalTime?,
        private val toTime: LocalTime?,
        private val seriesZone: ZoneId,
        private val form: TimeForm,
    ) {
        /** The new place of a start that sat at [value]; one off the series' hour keeps its distance from it. */
        fun apply(value: Temporal): Temporal {
            val wall = value.wallClockIn(seriesZone, zone)
            val date = wall.toLocalDate().plusDays(days)
            if (toTime == null) return date
            val moved = if (fromTime == null) {
                date.atTime(toTime)
            } else {
                LocalDateTime.of(date, wall.toLocalTime()).plus(Duration.between(fromTime, toTime))
            }
            return when (form) {
                is TimeForm.Zoned -> moved.atZone(form.zone)
                TimeForm.Floating -> moved
                else -> moved.atZone(seriesZone)
            }
        }
    }

    /** A resource taken apart: one series, and everything else the file holds. */
    private class Series(
        val others: List<CalendarComponent>,
        val master: VEvent?,
        val overrides: MutableList<VEvent>,
    ) {
        fun components(): List<CalendarComponent> = others + listOfNotNull(master) + overrides

        fun parsed() = ParsedEvent((master ?: overrides.firstOrNull())?.uidValue().orEmpty(), master, overrides.toList())
    }

    /** How the edited instance looked when the form was filled in. */
    private class Snapshot(val title: String, val description: String?, val location: String?, val time: EventTime)

    private class Texts(val title: String?, val description: String?, val location: String?)

    private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

    private companion object {
        /** What makes a component a series rather than one event. */
        val SERIES_ONLY = listOf(Property.RRULE, Property.RDATE, Property.EXDATE, Property.EXRULE)

        const val GONE = "Этого повторения уже нет — возможно, его изменили или удалили в другом месте."

        /** An UNTIL from here to midnight means "through the whole day"; clients write 23:59 or 23:59:59. */
        val LAST_MINUTE: LocalTime = LocalTime.of(23, 59)
    }
}
