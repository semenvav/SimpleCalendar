export interface CalendarDto {
  id: string
  name: string
  color: string
  readOnly: boolean
  visible: boolean
  sortOrder: number
}

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
