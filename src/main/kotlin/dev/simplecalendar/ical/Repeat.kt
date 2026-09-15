package dev.simplecalendar.ical

import dev.simplecalendar.model.Frequency
import dev.simplecalendar.model.RepeatRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.Temporal

/** What an edit asks for a series' rule. */
sealed interface RepeatChange {
    /** Leave the rule exactly as the file has it — simple or not. */
    data object Keep : RepeatChange

    /** Replace it; `null` stops the event repeating. */
    data class To(val rule: RepeatRule?) : RepeatChange
}

/** RRULE parts, in order: `FREQ=WEEKLY;BYDAY=MO` becomes `[FREQ=WEEKLY, BYDAY=MO]`. */
internal typealias RuleParts = List<Pair<String, String>>

internal fun ruleParts(value: String): RuleParts =
    value.split(';')
        .filter { '=' in it }
        .map { it.substringBefore('=').trim().uppercase() to it.substringAfter('=').trim() }

internal fun RuleParts.joinRule(): String = joinToString(";") { (key, value) -> "$key=$value" }

internal fun RuleParts.without(vararg keys: String): RuleParts = filterNot { it.first in keys }

/** RRULE text for [this] on a series whose DTSTART is written in [form]. */
internal fun RepeatRule.toRRuleValue(form: TimeForm, household: ZoneId): String = buildList {
    add("FREQ" to frequency.name)
    if (interval != 1) add("INTERVAL" to "$interval")
    until?.let { add("UNTIL" to untilThrough(it, form, household)) }
}.joinRule()

/** UNTIL that lets a series run through the whole of [lastDay]. */
internal fun untilThrough(lastDay: LocalDate, form: TimeForm, household: ZoneId): String = when (form) {
    TimeForm.Date -> form.untilText(lastDay, household)
    TimeForm.Floating -> form.untilText(lastDay.plusDays(1).atStartOfDay().minusSeconds(1), household)
    is TimeForm.Zoned -> form.untilText(lastDay.plusDays(1).atStartOfDay(form.zone).minusSeconds(1), household)
    TimeForm.Utc -> form.untilText(lastDay.plusDays(1).atStartOfDay(household).minusSeconds(1), household)
}

/** UNTIL that ends a series the moment before the instance starting at [instance]. */
internal fun untilBefore(instance: Temporal, form: TimeForm, household: ZoneId): String = when (form) {
    TimeForm.Date -> form.untilText(instance.dateIn(household).minusDays(1), household)
    TimeForm.Floating -> form.untilText(instance.wallClockIn(household, household).minusSeconds(1), household)
    is TimeForm.Zoned, TimeForm.Utc -> form.untilText(instance.toInstantIn(household).minusSeconds(1), household)
}

/**
 * Frequency and interval of [rrule] when the rule is no richer than a [RepeatRule], else `null`.
 *
 * Several spellings mean the same simple rule — Google writes a weekly event as
 * `FREQ=WEEKLY;BYDAY=MO`, Apple as plain `FREQ=WEEKLY` — so BY-parts that only restate what
 * the first instance on [start] already implies are accepted. The end (UNTIL or COUNT) is the
 * caller's business, because only expansion can tell where a COUNT runs out.
 */
internal fun simpleShape(rrule: String, start: LocalDate): Pair<Frequency, Int>? {
    val parts = ruleParts(rrule).toMap()
    if (!SIMPLE_KEYS.containsAll(parts.keys)) return null

    val frequency = Frequency.entries.firstOrNull { it.name == parts["FREQ"]?.uppercase() } ?: return null
    val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
    if (interval < 1) return null

    val byDay = parts["BYDAY"]?.uppercase()
    val byMonthDay = parts["BYMONTHDAY"]
    val byMonth = parts["BYMONTH"]
    val day = start.dayOfMonth.toString()
    val month = start.monthValue.toString()

    val onlyImplied = when (frequency) {
        Frequency.DAILY -> byDay == null && byMonthDay == null && byMonth == null
        Frequency.WEEKLY ->
            byMonthDay == null && byMonth == null && (byDay == null || byDay == WEEKDAY_CODES[start.dayOfWeek])
        Frequency.MONTHLY -> byDay == null && byMonth == null && (byMonthDay == null || byMonthDay == day)
        Frequency.YEARLY ->
            byDay == null && (byMonth == null || byMonth == month) && (byMonthDay == null || byMonthDay == day)
    }
    return if (onlyImplied) frequency to interval else null
}

private val SIMPLE_KEYS = setOf("FREQ", "INTERVAL", "UNTIL", "COUNT", "WKST", "BYDAY", "BYMONTHDAY", "BYMONTH")

internal val WEEKDAY_CODES: Map<DayOfWeek, String> = mapOf(
    DayOfWeek.MONDAY to "MO",
    DayOfWeek.TUESDAY to "TU",
    DayOfWeek.WEDNESDAY to "WE",
    DayOfWeek.THURSDAY to "TH",
    DayOfWeek.FRIDAY to "FR",
    DayOfWeek.SATURDAY to "SA",
    DayOfWeek.SUNDAY to "SU",
)
