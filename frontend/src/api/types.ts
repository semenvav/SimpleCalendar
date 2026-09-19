export interface CalendarDto {
  id: string
  name: string
  color: string
  readOnly: boolean
  visible: boolean
  sortOrder: number
}

export type Frequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY'

/**
 * A repetition as the form speaks of it. In a request, `NONE` stops it, and leaving `repeat` out
 * altogether keeps whatever rule the event has — including rules richer than this.
 */
export interface RepeatDto {
  frequency: Frequency | 'NONE'
  interval?: number
  /** Last day an instance may fall on, `YYYY-MM-DD`, inclusive; absent repeats forever. */
  until?: string
}

/** How much of a repeating series an edit or a delete covers. */
export type EditScope = 'this' | 'following' | 'all'

/**
 * A hand-made note on one occurrence: it is not happening as written, but the record of it stays
 * on the calendar. Nothing else follows from it — see `lib/marks.ts` for how it is shown.
 */
export type EventMark = 'cancelled' | 'moved'

export interface EventDto {
  id: string
  calendarId: string
  uid: string
  recurrenceId?: string
  title: string
  description?: string
  location?: string
  allDay: boolean
  /** `YYYY-MM-DD` for all-day entries, ISO-8601 with an offset otherwise. */
  start: string
  /** Exclusive, following the iCalendar convention. */
  end: string
  recurring: boolean
  /** The series' rule when the form can show it; absent for single events and richer rules. */
  repeat?: RepeatDto
  /** Set when somebody marked this occurrence cancelled or moved. */
  mark?: EventMark
  readOnly: boolean
}

export interface SyncStatusDto {
  configured: boolean
  lastSuccessAt?: string
  lastError?: string
  calendarCount: number
  eventCount: number
  revision: number
}

export interface HealthDto {
  status: string
  version: string
  timeZone: string
  sync: SyncStatusDto
}

// --- Integrations ------------------------------------------------------------------------------
//
// Only the parts a layout actually shows. Each integration sends more (see
// `src/main/kotlin/dev/simplecalendar/integrations/`); adding a field here is enough to use it.

/** Whatever provider is configured, the shape is the same — see `weather/Weather.kt`. */
export interface WeatherNow {
  /** Household wall-clock time the reading is for, `2026-09-16T11:45`. */
  time: string
  condition: string | null
  temperature: number | null
  feelsLike: number | null
  humidity: number | null
  windSpeed: number | null
}

export interface WeatherDay {
  /** `YYYY-MM-DD`, a day in the household zone. */
  date: string
  condition: string | null
  temperatureMax: number | null
  temperatureMin: number | null
  /** Mean relative humidity over the day, 0–100. */
  humidity: number | null
  precipitationProbability: number | null
}

export interface Weather {
  current: WeatherNow
  /** Today first. How far ahead depends on the provider. */
  days: WeatherDay[]
}

/** One place in the house and its two numbers, as `SC_HA_SENSORS` pairs them up. */
export interface SensorReading {
  name: string
  temperature: number | null
  temperatureUnit: string | null
  humidity: number | null
  humidityUnit: string | null
}

export interface HomeAssistantStates {
  sensors: SensorReading[]
}
