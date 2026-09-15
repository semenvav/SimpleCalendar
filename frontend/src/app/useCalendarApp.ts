import { useEffect, useMemo, useState } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createEvent,
  deleteEvent,
  fetchCalendars,
  fetchEvents,
  fetchHealth,
  triggerSync,
  updateEvent,
  type EventWriteRequest,
} from '../api/client'
import type { EditScope, EventDto, HealthDto } from '../api/types'
import { toCalendarEvents, type EventDetailsData } from '../calendar/adapter'
import { parseLocal, toIsoDate } from '../lib/dates'
import { recall, remember } from '../lib/persist'
import { isViewId, rangeFor, step, titleFor, type ViewId } from '../views'

/**
 * Everything a layout needs to show and change the calendar: what is on it, where we are
 * looking, and the dialogs in flight.
 *
 * Layouts differ in how things look, never in how they talk to the server. So all of that lives
 * here, once, and a new layout is a new arrangement of the same state and actions.
 */

/** Which event the form is working on, and which day a new one should land on. */
export interface Editing {
  event: EventDto | null
  defaultDate: Date
}

/** A write waiting on an answer: which part of a series is meant, or whether to delete at all. */
export type Question =
  | { kind: 'save'; event: EventDto; body: EventWriteRequest }
  | { kind: 'delete'; event: EventDto }

export interface CalendarAppOptions {
  /** The views this layout offers. */
  views: readonly ViewId[]
  /** The view a device starts on until somebody picks another. */
  defaultView: ViewId
  /** Where the device remembers its view in this layout. */
  viewKey: string
}

/**
 * The open period survives only a reload of the same tab — an accidental swipe, say. Opening the
 * calendar afresh, the next morning for instance, starts at today rather than wherever somebody
 * left it the day before. Shared by all layouts, so switching keeps the same period in view.
 */
const ANCHOR_KEY = 'simple-calendar.anchor'

const initialAnchor = (): Date => {
  const stored = recall('session', ANCHOR_KEY)
  return stored && /^\d{4}-\d{2}-\d{2}$/.test(stored) ? parseLocal(stored) : new Date()
}

export function useCalendarApp({ views, defaultView, viewKey }: CalendarAppOptions) {
  const queryClient = useQueryClient()

  // The display format is a preference of the device, so it outlives the tab.
  const [view, setView] = useState<ViewId>(() => {
    const stored = recall('local', viewKey)
    return isViewId(stored) && views.includes(stored) ? stored : defaultView
  })
  const [anchor, setAnchor] = useState(initialAnchor)

  useEffect(() => remember('local', viewKey, view), [viewKey, view])
  useEffect(() => remember('session', ANCHOR_KEY, toIsoDate(anchor)), [anchor])

  const [hidden, setHidden] = useState<ReadonlySet<string>>(() => new Set())
  const [selected, setSelected] = useState<EventDetailsData | null>(null)
  const [editing, setEditing] = useState<Editing | null>(null)
  const [question, setQuestion] = useState<Question | null>(null)

  const health = useQuery({ queryKey: ['health'], queryFn: fetchHealth, refetchInterval: 60_000 })

  const calendars = useQuery({
    queryKey: ['calendars'],
    queryFn: fetchCalendars,
    refetchInterval: 300_000,
  })

  const range = useMemo(() => rangeFor(view, anchor), [view, anchor])

  const events = useQuery({
    queryKey: ['events', range.from, range.to],
    queryFn: () => fetchEvents(range.from, range.to),
    refetchInterval: 60_000,
    // Keeps the previous month on screen while the next one loads, so navigating never blinks.
    placeholderData: keepPreviousData,
  })

  const sync = useMutation({
    mutationFn: triggerSync,
    onSuccess: () => queryClient.invalidateQueries(),
  })

  // Refetching after a write reads from the local cache, so it is quick enough that an
  // optimistic update would only add a rollback path without being noticeably faster.
  const afterWrite = () => {
    queryClient.invalidateQueries({ queryKey: ['events'] })
    queryClient.invalidateQueries({ queryKey: ['health'] })
    setEditing(null)
    setSelected(null)
    setQuestion(null)
  }

  // A failed write closes the question, so that the form or the notice can say why.
  const onError = () => setQuestion(null)

  const create = useMutation({ mutationFn: createEvent, onSuccess: afterWrite, onError })

  const update = useMutation({
    mutationFn: ({ id, body, scope }: { id: string; body: EventWriteRequest; scope?: EditScope }) =>
      updateEvent(id, body, scope),
    onSuccess: afterWrite,
    onError,
  })

  const remove = useMutation({
    mutationFn: ({ id, scope }: { id: string; scope?: EditScope }) => deleteEvent(id, scope),
    onSuccess: afterWrite,
    onError,
  })

  const calendarList = useMemo(
    () => (calendars.data ?? []).filter((c) => c.visible),
    [calendars.data],
  )

  const calendarEvents = useMemo(
    () => toCalendarEvents((events.data ?? []).filter((e) => !hidden.has(e.calendarId)), calendarList),
    [events.data, calendarList, hidden],
  )

  const canWrite = calendarList.some((c) => !c.readOnly) && health.data?.sync.configured === true

  return {
    view,
    anchor,
    title: titleFor(view, anchor),
    calendars: calendarList,
    events: calendarEvents,
    hidden,
    canWrite,
    writing: create.isPending || update.isPending || remove.isPending,
    syncing: sync.isPending,
    notice: describeProblem(health.data, events.error, calendars.error, remove.error),
    formError: errorMessage(create.error ?? update.error),
    selected,
    editing,
    question,

    setView,
    prev: () => setAnchor((d) => step(view, d, -1)),
    next: () => setAnchor((d) => step(view, d, 1)),
    today: () => setAnchor(new Date()),
    refresh: () => sync.mutate(),
    toggleCalendar: (id: string) =>
      setHidden((current) => {
        const next = new Set(current)
        if (next.has(id)) next.delete(id)
        else next.add(id)
        return next
      }),

    openEvent: setSelected,
    closeEvent: () => setSelected(null),

    /** Starts a new event on [date], or today. Does nothing where there is no calendar to write to. */
    startCreate: (date?: Date) => {
      if (canWrite) setEditing({ event: null, defaultDate: date ?? new Date() })
    },
    startEdit: (event: EventDto) => {
      setSelected(null)
      setEditing({ event, defaultDate: parseLocal(event.start) })
    },
    closeForm: () => {
      create.reset()
      update.reset()
      setEditing(null)
    },

    /** Saves the form — first asking, for a repeating event, how much of the series it is about. */
    submitForm: (body: EventWriteRequest) => {
      const event = editing?.event
      if (!event) create.mutate(body)
      else if (event.recurring) setQuestion({ kind: 'save', event, body })
      else update.mutate({ id: event.id, body })
    },
    requestDelete: (event: EventDto) => setQuestion({ kind: 'delete', event }),
    answer: (scope: EditScope) => {
      if (question?.kind === 'save') update.mutate({ id: question.event.id, body: question.body, scope })
      else if (question?.kind === 'delete') remove.mutate({ id: question.event.id, scope })
    },
    dismissQuestion: () => setQuestion(null),
  }
}

export type CalendarApp = ReturnType<typeof useCalendarApp>

const errorMessage = (error: unknown): string | null =>
  error instanceof Error ? error.message : null

/** One line explaining why the calendar might look empty or a write failed, or nothing. */
function describeProblem(health: HealthDto | undefined, ...errors: Array<unknown>): string | null {
  const failed = errors.find((e) => e instanceof Error) as Error | undefined
  if (failed) return failed.message

  if (!health) return null
  if (!health.sync.configured) {
    return 'Источник календарей не настроен — задайте SC_CALDAV_URL, SC_CALDAV_USERNAME и SC_CALDAV_PASSWORD.'
  }
  if (health.sync.lastError) return `Последняя синхронизация не удалась: ${health.sync.lastError}`
  return null
}
