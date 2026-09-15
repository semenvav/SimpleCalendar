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
