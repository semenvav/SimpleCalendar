package dev.simplecalendar.ical

import dev.simplecalendar.model.EventTime
import dev.simplecalendar.model.Frequency
import dev.simplecalendar.model.Occurrence
import dev.simplecalendar.model.RepeatRule
import dev.simplecalendar.model.startInstant
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Editing repeating events: one instance, this one and the following, all of them.
 *
 * Each test reads its result back through the expander rather than trusting the text alone — what
 * counts is what the calendar shows afterwards. Raw text is checked only where the *form* of the
 * file is the point: a TZID kept, an EXDATE in the master's own shape, a rule left as it was.
 */
class SeriesEditorTest {

    private val jerusalem: ZoneId = ZoneId.of("Asia/Jerusalem")
    private val moscow: ZoneId = ZoneId.of("Europe/Moscow")
    private val expander = EventExpander(jerusalem)
    private val editor = SeriesEditor(jerusalem, expander)

    // --- one instance -------------------------------------------------------------------------

    @Test
    fun `deleting one instance adds an EXDATE in the master's own form`() {
        val ics = assertNotNull(
            editor.delete(fixture("weekly-jerusalem.ics"), instance("2026-10-12T09:00", jerusalem), EditScope.THIS).updated,
        )

        assertContains(ics, "EXDATE;TZID=Asia/Jerusalem:20261012T090000")
        assertEquals(
            listOf(
                "2026-10-05 09:00+03:00 Кружок",
                "2026-10-19 09:00+03:00 Кружок",
                "2026-10-26 09:00+02:00 Кружок",
            ),
            show(ics, to = "2026-10-31T00:00:00Z"),
        )
    }

    @Test
    fun `deleting a moved instance removes its override and frees nothing in its old slot`() {
        val ics = assertNotNull(
            editor.delete(fixture("weekly-moved-override.ics"), instance("2026-09-14T10:00", moscow), EditScope.THIS).updated,
        )

        assertFalse(ics.contains("RECURRENCE-ID"), "the override must go with the instance\n$ics")
        assertEquals(
            listOf(
                "2026-09-07 10:00+03:00 Тренировка",
                "2026-09-21 10:00+03:00 Тренировка",
                "2026-09-28 10:00+03:00 Тренировка",
            ),
            show(ics),
        )
    }

    @Test
    fun `editing one instance creates an override that keeps what the master carries`() {
        val ics = edit(
            "weekly-jerusalem.ics",
            instance("2026-10-12T09:00", jerusalem),
            EditScope.THIS,
            draft("Кружок (в другом зале)", "2026-10-12T09:00", "2026-10-12T10:00", jerusalem, location = "Зал 2"),
        ).updated

        assertContains(ics, "RECURRENCE-ID;TZID=Asia/Jerusalem:20261012T090000")
        assertEquals(2, count(ics, "BEGIN:VALARM"), "the override has to keep the master's alarm\n$ics")
        assertEquals(1, count(ics, "RRULE:FREQ=WEEKLY"), "an override must not repeat on its own")
        assertEquals(
            listOf(
                "2026-10-05 09:00+03:00 Кружок",
                "2026-10-12 09:00+03:00 Кружок (в другом зале)",
                "2026-10-19 09:00+03:00 Кружок",
            ),
            show(ics, to = "2026-10-20T00:00:00Z"),
        )
    }

    @Test
    fun `editing an instance that was already moved changes its override instead of adding another`() {
        val ics = edit(
            "weekly-moved-override.ics",
            instance("2026-09-14T10:00", moscow),
            EditScope.THIS,
            draft("Тренировка (перенесена на вечер)", "2026-09-14T19:00", "2026-09-14T20:00", moscow),
        ).updated

        assertEquals(1, count(ics, "RECURRENCE-ID"))
        assertEquals(
            listOf(
                "2026-09-07 10:00+03:00 Тренировка",
                "2026-09-14 19:00+03:00 Тренировка (перенесена на вечер)",
                "2026-09-21 10:00+03:00 Тренировка",
                "2026-09-28 10:00+03:00 Тренировка",
            ),
            show(ics),
        )
    }

    @Test
    fun `a rule cannot be changed for a single instance`() {
        assertFailsWith<IllegalArgumentException> {
            editor.edit(
                fixture("weekly-jerusalem.ics"),
                instance("2026-10-12T09:00", jerusalem),
                EditScope.THIS,
                draft("Кружок", "2026-10-12T09:00", "2026-10-12T10:00", jerusalem),
                RepeatChange.To(RepeatRule(Frequency.DAILY)),
            )
        }
    }

    @Test
    fun `an instance that is not part of the series is reported, including one already deleted`() {
        val source = fixture("weekly-jerusalem.ics")
        val tuesday = instance("2026-10-13T09:00", jerusalem)
        assertFailsWith<NoSuchInstanceException> {
            editor.edit(source, tuesday, EditScope.THIS, draft("Кружок", "2026-10-13T09:00", "2026-10-13T10:00", jerusalem))
        }

        val monday = instance("2026-10-12T09:00", jerusalem)
        val once = assertNotNull(editor.delete(source, monday, EditScope.THIS).updated)
        assertFailsWith<NoSuchInstanceException> { editor.delete(once, monday, EditScope.THIS) }
    }

    // --- all ----------------------------------------------------------------------------------

    @Test
    fun `editing all moves every instance to the new hour and leaves a rescheduled one where it is`() {
        val ics = edit(
            "weekly-moved-override.ics",
            instance("2026-09-07T10:00", moscow),
            EditScope.ALL,
            draft("Тренировка", "2026-09-07T11:00", "2026-09-07T12:00", moscow),
        ).updated

        // The override's slot moves with the series, or it would stop replacing anything.
        assertContains(ics, "RECURRENCE-ID;TZID=Europe/Moscow:20260914T110000")
        assertEquals(
            listOf(
                "2026-09-07 11:00+03:00 Тренировка",
                "2026-09-14 18:00+03:00 Тренировка (перенесена на вечер)",
                "2026-09-21 11:00+03:00 Тренировка",
                "2026-09-28 11:00+03:00 Тренировка",
            ),
            show(ics),
        )
    }

    @Test
    fun `editing all keeps the wall-clock hour on both sides of the DST change`() {
        val ics = edit(
            "weekly-jerusalem.ics",
            instance("2026-10-26T09:00", jerusalem),
            EditScope.ALL,
            draft("Кружок", "2026-10-26T10:00", "2026-10-26T11:00", jerusalem),
        ).updated

        assertEquals(
            listOf(
                "2026-10-05 10:00+03:00 Кружок",
                "2026-10-12 10:00+03:00 Кружок",
                "2026-10-19 10:00+03:00 Кружок",
                "2026-10-26 10:00+02:00 Кружок",
                "2026-11-02 10:00+02:00 Кружок",
                "2026-11-09 10:00+02:00 Кружок",
            ),
            show(ics),
        )
    }

    @Test
    fun `editing all renames the instances that shared the old title and no others`() {
        val ics = edit(
            "weekly-overrides.ics",
            instance("2026-09-01T17:00", jerusalem),
            EditScope.ALL,
            draft("Секция", "2026-09-01T17:00", "2026-09-01T18:00", jerusalem, location = "Стадион"),
        ).updated

        assertEquals(
            listOf(
                "2026-09-01 17:00+03:00 Секция",
                "2026-09-08 17:00+03:00 Секция",
                "2026-09-17 19:00+03:00 Тренировка (перенесена)",
                "2026-09-29 17:00+03:00 Секция",
                "2026-10-06 17:00+03:00 Секция",
            ),
            show(ics),
        )
        assertEquals("Взять форму", occurrenceOn(ics, "2026-09-08").description, "its own description stays")
    }

    @Test
    fun `editing all from a moved instance spreads only what was changed`() {
        // The instance was moved from Tuesday 15th to Thursday 17th, 19:00. Changing its hour to
        // 20:00 for all must move the series to 20:00 — and keep it on Tuesdays.
        val ics = edit(
            "weekly-overrides.ics",
            instance("2026-09-15T17:00", jerusalem),
            EditScope.ALL,
            draft("Тренировка (перенесена)", "2026-09-17T20:00", "2026-09-17T21:00", jerusalem, location = "Стадион"),
        ).updated

        assertEquals(
            listOf(
                "2026-09-01 20:00+03:00 Тренировка",
                "2026-09-08 20:00+03:00 Тренировка",
                "2026-09-17 20:00+03:00 Тренировка (перенесена)",
                "2026-09-29 20:00+03:00 Тренировка",
                "2026-10-06 20:00+03:00 Тренировка",
            ),
            show(ics),
        )
        assertContains(ics, "EXDATE;TZID=Asia/Jerusalem:20260922T200000")
    }

    @Test
    fun `stopping the repetition leaves the one event the form shows`() {
        val ics = edit(
            "weekly-overrides.ics",
            instance("2026-09-08T17:00", jerusalem),
            EditScope.ALL,
            draft("Тренировка", "2026-09-08T17:00", "2026-09-08T18:00", jerusalem, "Взять форму", "Стадион"),
            RepeatChange.To(null),
        ).updated

        assertFalse(ics.contains("RRULE:FREQ=WEEKLY"), ics)
        assertFalse(ics.contains("RECURRENCE-ID"), ics)
        assertFalse(ics.contains("EXDATE"), ics)
        assertEquals(listOf("2026-09-08 17:00+03:00 Тренировка"), show(ics))
    }

    @Test
    fun `a different frequency drops the overrides that fitted the old one`() {
        val ics = edit(
            "weekly-overrides.ics",
            instance("2026-09-01T17:00", jerusalem),
            EditScope.ALL,
            draft("Тренировка", "2026-09-01T17:00", "2026-09-01T18:00", jerusalem, location = "Стадион"),
            RepeatChange.To(RepeatRule(Frequency.DAILY, until = LocalDate.of(2026, 9, 3))),
        ).updated

        assertFalse(ics.contains("RECURRENCE-ID"), ics)
        assertEquals(
            listOf(
                "2026-09-01 17:00+03:00 Тренировка",
                "2026-09-02 17:00+03:00 Тренировка",
                "2026-09-03 17:00+03:00 Тренировка",
            ),
            show(ics),
        )
    }

    @Test
    fun `moving only the end keeps the rule's own spelling and trims what falls outside`() {
        val ics = edit(
            "weekly-moved-override.ics",
            instance("2026-09-07T10:00", moscow),
            EditScope.ALL,
            draft("Тренировка", "2026-09-07T10:00", "2026-09-07T11:00", moscow),
            RepeatChange.To(RepeatRule(Frequency.WEEKLY, until = LocalDate.of(2026, 9, 15))),
        ).updated

        assertEquals(
            mapOf("FREQ" to "WEEKLY", "BYDAY" to "MO", "UNTIL" to "20260915T205959Z"),
            rule(ics, "WEEKLY"),
            "through the whole of 15 Sep in Moscow, COUNT gone",
        )
        assertEquals(
            listOf(
                "2026-09-07 10:00+03:00 Тренировка",
                "2026-09-14 18:00+03:00 Тренировка (перенесена на вечер)",
            ),
            show(ics),
        )
    }

    @Test
    fun `the rule the form sends back unchanged is not a change`() {
        // COUNT=6 reads as "until 6 October"; sending that back must not rewrite the rule or drop
        // the overrides that a real rule change would.
        val ics = edit(
            "weekly-overrides.ics",
            instance("2026-09-01T17:00", jerusalem),
            EditScope.ALL,
            draft("Секция", "2026-09-01T17:00", "2026-09-01T18:00", jerusalem, location = "Стадион"),
            RepeatChange.To(RepeatRule(Frequency.WEEKLY, until = LocalDate.of(2026, 10, 6))),
        ).updated

        assertEquals("6", rule(ics, "WEEKLY")["COUNT"])
        assertContains(show(ics), "2026-09-17 19:00+03:00 Тренировка (перенесена)")
    }

    @Test
    fun `a rule richer than the form is kept exactly when the control is left alone`() {
        val ics = edit(
            "monthly-second-tuesday.ics",
            instance("2026-09-08T19:00", jerusalem),
            EditScope.ALL,
            draft("Собрание в школе", "2026-09-08T19:00", "2026-09-08T20:00", jerusalem),
        ).updated

        assertEquals(mapOf("FREQ" to "MONTHLY", "BYDAY" to "2TU"), rule(ics, "MONTHLY"))
        assertEquals(
            listOf(
                "2026-09-08 19:00+03:00 Собрание в школе",
                "2026-10-13 19:00+03:00 Собрание в школе",
                "2026-11-10 19:00+02:00 Собрание в школе",
            ),
            show(ics, to = "2026-11-30T00:00:00Z"),
        )
    }

    // --- this and following -------------------------------------------------------------------

    @Test
    fun `this and following splits the series at the instance`() {
        val change = edit(
            "weekly-jerusalem.ics",
            instance("2026-10-19T09:00", jerusalem),
            EditScope.FOLLOWING,
            draft("Кружок", "2026-10-19T18:00", "2026-10-19T19:00", jerusalem),
        )
        val tail = assertNotNull(change.created)

        // 19 Oct 09:00 in Israel is 06:00 UTC; the old series ends the second before.
        assertEquals("20261019T055959Z", rule(change.updated, "WEEKLY")["UNTIL"])
        assertEquals(listOf("2026-10-05 09:00+03:00 Кружок", "2026-10-12 09:00+03:00 Кружок"), show(change.updated))
        // Its card must say it ends on the 12th — its last Monday — not on the 19th, the UNTIL's day.
        assertEquals(
            RepeatRule(Frequency.WEEKLY, until = LocalDate.of(2026, 10, 12)),
            expander.repeatOf(checkNotNull(IcsParser.parse(change.updated).single().master)),
        )

        assertContains(tail.ics, "UID:${tail.uid}")
        assertContains(tail.ics, "DTSTART;TZID=Asia/Jerusalem:20261019T180000")
        assertContains(tail.ics, "BEGIN:VALARM")
        assertContains(tail.ics, "BEGIN:VTIMEZONE")
        assertEquals(
            listOf(
                "2026-10-19 18:00+03:00 Кружок",
                "2026-10-26 18:00+02:00 Кружок",
                "2026-11-02 18:00+02:00 Кружок",
                "2026-11-09 18:00+02:00 Кружок",
            ),
            show(tail.ics),
        )
    }

    @Test
    fun `this and following hands what is left of a COUNT to the new series`() {
        val change = edit(
            "weekly-exdate.ics",
            instance("2026-09-14T10:00", moscow),
            EditScope.FOLLOWING,
            draft("Секция по плаванию", "2026-09-14T11:00", "2026-09-14T12:00", moscow),
        )
        val tail = assertNotNull(change.created)

        assertEquals(listOf("2026-09-07 10:00+03:00 Секция по плаванию"), show(change.updated))
        assertEquals("3", rule(tail.ics, "WEEKLY")["COUNT"], "four in all, one already used by the old series")
        // The exception on the 21st goes with the tail — and moves to the new hour with it.
        assertEquals(
            listOf("2026-09-14 11:00+03:00 Секция по плаванию", "2026-09-28 11:00+03:00 Секция по плаванию"),
            show(tail.ics),
        )
    }

    @Test
    fun `deleting this and following ends the series the moment before`() {
        val ics = assertNotNull(
            editor.delete(fixture("weekly-jerusalem.ics"), instance("2026-10-19T09:00", jerusalem), EditScope.FOLLOWING).updated,
        )

        assertEquals("20261019T055959Z", rule(ics, "WEEKLY")["UNTIL"])
        assertEquals(listOf("2026-10-05 09:00+03:00 Кружок", "2026-10-12 09:00+03:00 Кружок"), show(ics))
    }

    @Test
    fun `this and following from the first instance is the whole series`() {
        val first = instance("2026-10-05T09:00", jerusalem)
        val change = edit(
            "weekly-jerusalem.ics",
            first,
            EditScope.FOLLOWING,
            draft("Кружок", "2026-10-05T18:00", "2026-10-05T19:00", jerusalem),
        )

        assertNull(change.created, "nothing is left before it, so there is nothing to split off")
        assertEquals(
            listOf("2026-10-05 18:00+03:00 Кружок", "2026-10-12 18:00+03:00 Кружок"),
            show(change.updated, to = "2026-10-13T00:00:00Z"),
        )
        assertNull(editor.delete(fixture("weekly-jerusalem.ics"), first, EditScope.FOLLOWING).updated)
    }

    @Test
    fun `moving a series to a later hour keeps the day it repeats until`() {
        // Found in the real UI: our UNTIL means "through the whole of 20 Oct", and shifting it an
        // hour like an instance carried it past midnight — the form then said "until 21 Oct".
        val source = IcsWriter.create(
            "until@test",
            draft("Кружок", "2026-09-22T17:00", "2026-09-22T18:00", jerusalem),
            RepeatRule(Frequency.WEEKLY, until = LocalDate.of(2026, 10, 20)),
            jerusalem,
        )
        val tail = assertNotNull(
            editor.edit(
                source,
                instance("2026-10-13T17:00", jerusalem),
                EditScope.FOLLOWING,
                draft("Кружок", "2026-10-13T18:00", "2026-10-13T19:00", jerusalem),
            ).created,
        ).ics

        assertEquals(
            RepeatRule(Frequency.WEEKLY, until = LocalDate.of(2026, 10, 20)),
            expander.repeatOf(checkNotNull(IcsParser.parse(tail).single().master)),
        )
        assertEquals(listOf("2026-10-13 18:00+03:00 Кружок", "2026-10-20 18:00+03:00 Кружок"), show(tail))
    }

    @Test
    fun `a series cut short by a split stays cut when it is moved`() {
        // The old half ends the second before the cut. Moving it to a later hour must not let the
        // instance on the cut day back in, where it would duplicate the new half.
        val head = assertNotNull(
            editor.edit(
                fixture("weekly-jerusalem.ics"),
                instance("2026-10-19T09:00", jerusalem),
                EditScope.FOLLOWING,
                draft("Кружок", "2026-10-19T18:00", "2026-10-19T19:00", jerusalem),
            ).updated,
        )
        val moved = assertNotNull(
            editor.edit(
                head,
                instance("2026-10-05T09:00", jerusalem),
                EditScope.ALL,
                draft("Кружок", "2026-10-05T20:00", "2026-10-05T21:00", jerusalem),
            ).updated,
        )

        assertEquals(listOf("2026-10-05 20:00+03:00 Кружок", "2026-10-12 20:00+03:00 Кружок"), show(moved))
    }

    // --- forms --------------------------------------------------------------------------------

    @Test
    fun `a single event given a rule becomes a zoned series that keeps its hour across DST`() {
        val single = IcsWriter.create("single@test", draft("Садик", "2026-10-20T09:00", "2026-10-20T10:00", jerusalem))
        val ics = assertNotNull(
            editor.edit(
                single,
                null,
                EditScope.ALL,
                draft("Садик", "2026-10-20T09:00", "2026-10-20T10:00", jerusalem),
                RepeatChange.To(RepeatRule(Frequency.WEEKLY)),
            ).updated,
        )

        assertContains(ics, "DTSTART;TZID=Asia/Jerusalem:20261020T090000")
        assertContains(ics, "BEGIN:VTIMEZONE")
        assertEquals(
            listOf(
                "2026-10-20 09:00+03:00 Садик",
                "2026-10-27 09:00+02:00 Садик",
                "2026-11-03 09:00+02:00 Садик",
            ),
            show(ics, to = "2026-11-05T00:00:00Z"),
        )
    }

    @Test
    fun `an all-day series takes exceptions and splits as plain dates`() {
        val source = fixture("all-day-daily.ics")

        val deleted = assertNotNull(editor.delete(source, "2026-09-16", EditScope.THIS).updated)
        assertContains(deleted, "EXDATE;VALUE=DATE:20260916")
        assertEquals(
            listOf("2026-09-14", "2026-09-15", "2026-09-17", "2026-09-18", "2026-09-19", "2026-09-20").map { "$it Лагерь" },
            show(deleted),
        )

        val split = editor.edit(
            source,
            "2026-09-18",
            EditScope.FOLLOWING,
            EventDraft(
                "Лагерь, вторая смена", null, null,
                EventTime.AllDay(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 19)),
            ),
        )
        val head = assertNotNull(split.updated)
        assertEquals("20260917", rule(head, "DAILY")["UNTIL"])
        assertEquals(listOf("2026-09-14", "2026-09-15", "2026-09-16", "2026-09-17").map { "$it Лагерь" }, show(head))
        assertEquals(
            listOf("2026-09-18", "2026-09-19", "2026-09-20").map { "$it Лагерь, вторая смена" },
            show(assertNotNull(split.created).ics),
        )
    }

    @Test
    fun `a floating series stays floating`() {
        val ics = edit(
            "weekly-floating.ics",
            instance("2026-09-07T08:00", jerusalem),
            EditScope.ALL,
            draft("Зарядка", "2026-09-07T07:30", "2026-09-07T08:00", jerusalem),
        ).updated

        assertContains(ics, "DTSTART:20260907T073000\r\n")
        assertFalse(ics.contains("DTSTART;TZID"), ics)
        assertEquals(
            listOf("2026-09-07", "2026-09-14", "2026-09-21").map { "$it 07:30+03:00 Зарядка" },
            show(ics),
        )
    }

    @Test
    fun `a series written in UTC stays in UTC`() {
        val ics = edit(
            "weekly-utc-count.ics",
            instance("2026-09-07T09:00", jerusalem),
            EditScope.ALL,
            draft("Бассейн", "2026-09-07T10:00", "2026-09-07T11:00", jerusalem),
        ).updated

        assertContains(ics, "DTSTART:20260907T070000Z")
        assertEquals(
            listOf("2026-09-07", "2026-09-14", "2026-09-21", "2026-09-28", "2026-10-05").map { "$it 10:00+03:00 Бассейн" },
            show(ics),
        )
    }

    // --- helpers ------------------------------------------------------------------------------

    private class Edited(val updated: String, val created: CreatedResource?)

    private fun edit(
        name: String,
        instanceId: String,
        scope: EditScope,
        draft: EventDraft,
        repeat: RepeatChange = RepeatChange.Keep,
    ): Edited {
        val change = editor.edit(fixture(name), instanceId, scope, draft, repeat)
        return Edited(assertNotNull(change.updated, "an edit must not delete the resource"), change.created)
    }

    private fun instance(local: String, zone: ZoneId): String =
        LocalDateTime.parse(local).atZone(zone).toInstant().toString()

    private fun draft(
        title: String,
        start: String,
        end: String,
        zone: ZoneId,
        description: String? = null,
        location: String? = null,
    ) = EventDraft(
        title = title,
        description = description,
        location = location,
        time = EventTime.Timed(LocalDateTime.parse(start).atZone(zone), LocalDateTime.parse(end).atZone(zone)),
    )

    private fun occurrences(ics: String, from: String, to: String): List<Occurrence> =
        IcsParser.parse(ics)
            .flatMap { expander.expand(it, "cal", "/e.ics", Instant.parse(from), Instant.parse(to)) }
            .sortedBy { it.time.startInstant(jerusalem) }

    /** What the calendar shows, one line per occurrence, times in the event's own zone. */
    private fun show(ics: String, from: String = "2026-09-01T00:00:00Z", to: String = "2026-11-10T00:00:00Z"): List<String> =
        occurrences(ics, from, to).map { occurrence ->
            when (val time = occurrence.time) {
                is EventTime.AllDay -> "${time.start} ${occurrence.title}"
                is EventTime.Timed -> "${time.start.format(WHEN)} ${occurrence.title}"
            }
        }

    private fun occurrenceOn(ics: String, date: String): Occurrence =
        occurrences(ics, "2026-09-01T00:00:00Z", "2026-11-10T00:00:00Z").single {
            (it.time as EventTime.Timed).start.toLocalDate().toString() == date
        }

    /** The event's RRULE as parts — ical4j may order them its own way, which is fine. */
    private fun rule(ics: String, frequency: String): Map<String, String> {
        val unfolded = ics.replace("\r\n ", "")
        val line = unfolded.lines().single { it.startsWith("RRULE:") && "FREQ=$frequency" in it }
        return ruleParts(line.removePrefix("RRULE:")).toMap()
    }

    private fun count(text: String, needle: String) = Regex(Regex.escape(needle)).findAll(text).count()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture: $name" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private companion object {
        val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mmxxx")
    }
}
