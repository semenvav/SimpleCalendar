package dev.simplecalendar.api

import dev.simplecalendar.model.EventTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Validation of what the UI sends.
 *
 * These messages reach the user verbatim, so the tests check them as part of the contract rather
 * than only checking that something was rejected.
 */
class EventWriteRequestTest {

    private val moscow: ZoneId = ZoneId.of("Europe/Moscow")

    @Test
    fun `a timed request is read in the household zone`() {
        val draft = request(start = "2026-09-15T19:00", end = "2026-09-15T21:00").toDraft(moscow)
        val time = draft.time as EventTime.Timed

        assertEquals(Instant.parse("2026-09-15T16:00:00Z"), time.start.toInstant())
        assertEquals(Instant.parse("2026-09-15T18:00:00Z"), time.end.toInstant())
    }

    @Test
    fun `an explicit offset wins over the household zone`() {
        val draft = request(start = "2026-09-15T19:00:00+01:00", end = "2026-09-15T21:00:00+01:00")
            .toDraft(moscow)
        val time = draft.time as EventTime.Timed

        assertEquals(Instant.parse("2026-09-15T18:00:00Z"), time.start.toInstant())
    }

    @Test
    fun `an all-day request keeps its exclusive end and never gains a time`() {
        val draft = request(allDay = true, start = "2026-09-10", end = "2026-09-13").toDraft(moscow)

        assertEquals(
            EventTime.AllDay(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 13)),
            draft.time,
        )
    }

    @Test
    fun `an empty title is rejected with a readable message`() {
        val failure = assertFailsWith<IllegalArgumentException> { request(title = "   ").toDraft(moscow) }
        assertEquals("У события должно быть название.", failure.message)
    }

    @Test
    fun `an end before the start is rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            request(start = "2026-09-15T21:00", end = "2026-09-15T19:00").toDraft(moscow)
        }
        assertTrue(failure.message!!.contains("заканчиваться"), failure.message)
    }

    @Test
    fun `a zero-length event is allowed, a backwards all-day one is not`() {
        // A moment-in-time reminder is legitimate; a negative span never is.
        request(start = "2026-09-15T19:00", end = "2026-09-15T19:00").toDraft(moscow)

        assertFailsWith<IllegalArgumentException> {
            request(allDay = true, start = "2026-09-13", end = "2026-09-13").toDraft(moscow)
        }
    }

    @Test
    fun `an unreadable date says which value it could not read`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            request(allDay = true, start = "15 сентября", end = "2026-09-16").toDraft(moscow)
        }
        assertTrue(failure.message!!.contains("15 сентября"), failure.message)
    }

    @Test
    fun `blank optional fields become absent rather than empty strings`() {
        val draft = request(description = "   ", location = "").toDraft(moscow)

        assertNull(draft.description, "an empty description must not be written as a blank property")
        assertNull(draft.location)
    }

    @Test
    fun `surrounding whitespace is trimmed from the title`() {
        assertEquals("Ужин", request(title = "  Ужин  ").toDraft(moscow).title)
    }

    private fun request(
        title: String = "Ужин",
        description: String? = "Торт",
        location: String? = "Дома",
        allDay: Boolean = false,
        start: String = "2026-09-15T19:00",
        end: String = "2026-09-15T21:00",
    ) = EventWriteRequest(
        calendarId = "mum",
        title = title,
        description = description,
        location = location,
        allDay = allDay,
        start = start,
        end = end,
    )
}
