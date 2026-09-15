import type { CalendarDto, EditScope, EventDto, HealthDto, RepeatDto, SyncStatusDto } from './types'

const BASE = '/api'

class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })

  if (!response.ok) {
    // The server answers every failure with {error, message}; fall back if it did not.
    const body = await response.json().catch(() => null)
    throw new ApiError(response.status, body?.message ?? `${response.status} ${response.statusText}`)
  }

  return response.json() as Promise<T>
}

export const fetchHealth = () => request<HealthDto>('/health')

export const fetchCalendars = () => request<CalendarDto[]>('/calendars')

export function fetchEvents(from: string, to: string, calendarIds?: string[]): Promise<EventDto[]> {
  const params = new URLSearchParams({ from, to })
  if (calendarIds?.length) params.set('calendars', calendarIds.join(','))
  return request<EventDto[]>(`/events?${params}`)
}

export const triggerSync = () => request<SyncStatusDto>('/sync', { method: 'POST' })

export interface EventWriteRequest {
  calendarId?: string
  title: string
  description?: string
  location?: string
  allDay: boolean
  /** `YYYY-MM-DD` when all-day, otherwise `YYYY-MM-DDTHH:mm` in the household zone. */
  start: string
  /** Exclusive, same as everywhere else in the API. */
  end: string
  /** Left out, the event's rule stays exactly as it is. */
  repeat?: RepeatDto
}

/** `id=…&scope=…`. The id carries `|` and `:`, so it always goes through URLSearchParams. */
function eventQuery(id: string, scope?: EditScope): string {
  const params = new URLSearchParams({ id })
  if (scope) params.set('scope', scope)
  return params.toString()
}

export const createEvent = (body: EventWriteRequest) =>
  request<EventDto>('/events', { method: 'POST', body: JSON.stringify(body) })

/** For a repeating event, [scope] says how much of the series the change covers. */
export const updateEvent = (id: string, body: EventWriteRequest, scope?: EditScope) =>
  request<EventDto>(`/events?${eventQuery(id, scope)}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  })

export async function deleteEvent(id: string, scope?: EditScope): Promise<void> {
  const response = await fetch(`${BASE}/events?${eventQuery(id, scope)}`, { method: 'DELETE' })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new ApiError(response.status, body?.message ?? `${response.status} ${response.statusText}`)
  }
}

export function updateCalendar(
  id: string,
  patch: { name?: string; color?: string; visible?: boolean; sortOrder?: number },
): Promise<CalendarDto> {
  return request<CalendarDto>(`/calendars/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(patch),
  })
}

export { ApiError }
