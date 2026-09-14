/**
 * Small date helpers, deliberately hand-rolled.
 *
 * Everything here works in the browser's local zone, which on the wall tablet is the household
 * zone. `toIsoDate` builds the string from local components rather than `toISOString()`, because
 * the latter converts to UTC first and quietly shifts the date by a day near midnight.
 */

export const startOfDay = (d: Date): Date => new Date(d.getFullYear(), d.getMonth(), d.getDate())

export const addDays = (d: Date, days: number): Date =>
  new Date(d.getFullYear(), d.getMonth(), d.getDate() + days)

export const addMonths = (d: Date, months: number): Date =>
  new Date(d.getFullYear(), d.getMonth() + months, 1)

export const startOfMonth = (d: Date): Date => new Date(d.getFullYear(), d.getMonth(), 1)

export const endOfMonth = (d: Date): Date => new Date(d.getFullYear(), d.getMonth() + 1, 0)

/** Monday-first, which is what a Russian household expects. */
export function startOfWeek(d: Date): Date {
  const date = startOfDay(d)
  const shift = (date.getDay() + 6) % 7
  return addDays(date, -shift)
}

export function toIsoDate(d: Date): string {
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${month}-${day}`
}

export function toIsoTime(d: Date): string {
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

export const isSameDay = (a: Date, b: Date): boolean => toIsoDate(a) === toIsoDate(b)

/** Shifts a `YYYY-MM-DD` string by whole days without going near time zones. */
export function shiftIsoDate(iso: string, days: number): string {
  return toIsoDate(addDays(parseLocal(iso), days))
}

const monthYear = new Intl.DateTimeFormat('ru-RU', { month: 'long', year: 'numeric' })
const monthOnly = new Intl.DateTimeFormat('ru-RU', { month: 'long' })
const dayMonth = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long' })
const weekdayLong = new Intl.DateTimeFormat('ru-RU', { weekday: 'long', day: 'numeric', month: 'long' })
const timeOnly = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' })

export const formatMonthYear = (d: Date): string => monthYear.format(d)
export const formatMonth = (d: Date): string => monthOnly.format(d)
export const formatDayMonth = (d: Date): string => dayMonth.format(d)
export const formatWeekday = (d: Date): string => weekdayLong.format(d)
export const formatTime = (d: Date): string => timeOnly.format(d)

export function formatWeekRange(d: Date): string {
  const start = startOfWeek(d)
  const end = addDays(start, 6)
  return `${dayMonth.format(start)} — ${dayMonth.format(end)} ${end.getFullYear()}`
}

/** Capitalises the first letter; Intl gives lowercase month names in Russian. */
export const capitalise = (s: string): string => s.charAt(0).toUpperCase() + s.slice(1)

/**
 * Parses a value from the API into a local `Date`.
 *
 * A bare `YYYY-MM-DD` must not go through `new Date(string)`: the spec parses date-only strings
 * as UTC midnight, so east of Greenwich every all-day event would render a day early.
 */
export function parseLocal(value: string): Date {
  const dateOnly = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (dateOnly) {
    return new Date(Number(dateOnly[1]), Number(dateOnly[2]) - 1, Number(dateOnly[3]))
  }
  return new Date(value)
}
