import type { CalendarDto, EventDto, HealthDto, SyncStatusDto } from './types'

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
}

export const createEvent = (body: EventWriteRequest) =>
  request<EventDto>('/events', { method: 'POST', body: JSON.stringify(body) })

export const updateEvent = (id: string, body: EventWriteRequest) =>
  request<EventDto>(`/events?id=${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  })

export async function deleteEvent(id: string): Promise<void> {
  const response = await fetch(`${BASE}/events?id=${encodeURIComponent(id)}`, { method: 'DELETE' })
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
