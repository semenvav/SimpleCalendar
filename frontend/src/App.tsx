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
} from './api/client'
import type { EventDto } from './api/types'
import { CalendarView } from './calendar/CalendarView'
import { toCalendarEvents, type EventDetailsData } from './calendar/adapter'
import { EventDetails } from './components/EventDetails'
import { EventForm } from './components/EventForm'
import { Toolbar } from './components/Toolbar'
import { addMonths, capitalise, formatMonthYear, parseLocal, toIsoDate } from './lib/dates'
import { recall, remember } from './lib/persist'
import { EC_VIEW, isViewId, rangeFor, step, titleFor, type ViewId } from './views'

/** Which event the form is working on, and which day a new one should land on. */
interface Editing {
  event: EventDto | null
  defaultDate: Date
}

/** The display format is a preference of the device, so it outlives the tab. */
const VIEW_KEY = 'simple-calendar.view'

/**
 * The open period survives only a reload of the same tab — an accidental swipe, say. Opening the
 * calendar afresh, the next morning for instance, starts at today rather than wherever somebody
 * left it the day before.
 */
const ANCHOR_KEY = 'simple-calendar.anchor'

const initialView = (): ViewId => {
  const stored = recall('local', VIEW_KEY)
  return isViewId(stored) ? stored : 'month'
}

const initialAnchor = (): Date => {
  const stored = recall('session', ANCHOR_KEY)
  return stored && /^\d{4}-\d{2}-\d{2}$/.test(stored) ? parseLocal(stored) : new Date()
}

export default function App() {
  const queryClient = useQueryClient()

  const [view, setView] = useState<ViewId>(initialView)
  const [anchor, setAnchor] = useState(initialAnchor)

  useEffect(() => remember('local', VIEW_KEY, view), [view])
  useEffect(() => remember('session', ANCHOR_KEY, toIsoDate(anchor)), [anchor])
  const [hidden, setHidden] = useState<ReadonlySet<string>>(() => new Set())
  const [selected, setSelected] = useState<EventDetailsData | null>(null)
  const [editing, setEditing] = useState<Editing | null>(null)

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
  }

  const create = useMutation({ mutationFn: createEvent, onSuccess: afterWrite })

  const update = useMutation({
    mutationFn: ({ id, body }: { id: string; body: EventWriteRequest }) => updateEvent(id, body),
    onSuccess: afterWrite,
  })

  const remove = useMutation({ mutationFn: deleteEvent, onSuccess: afterWrite })

  const calendarList = useMemo(
    () => (calendars.data ?? []).filter((c) => c.visible),
    [calendars.data],
  )

  const calendarEvents = useMemo(
    () => toCalendarEvents((events.data ?? []).filter((e) => !hidden.has(e.calendarId)), calendarList),
    [events.data, calendarList, hidden],
  )

  const canWrite = calendarList.some((c) => !c.readOnly) && health.data?.sync.configured === true
  const writing = create.isPending || update.isPending || remove.isPending

  const toggleCalendar = (id: string) =>
    setHidden((current) => {
      const next = new Set(current)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const startEdit = (source: EventDto) => {
    setSelected(null)
    setEditing({ event: source, defaultDate: parseLocal(source.start) })
  }

  const confirmDelete = (source: EventDto) => {
    const question = source.recurring
      ? `Удалить «${source.title}» вместе со всеми повторениями?`
      : `Удалить «${source.title}»?`
    if (window.confirm(question)) remove.mutate(source.id)
  }

  const submitForm = (body: EventWriteRequest) => {
    if (editing?.event) update.mutate({ id: editing.event.id, body })
    else create.mutate(body)
  }

  const notice = describeProblem(health.data, events.error, calendars.error, remove.error)

  return (
    <div className="app">
      <Toolbar
        title={titleFor(view, anchor)}
        view={view}
        calendars={calendarList}
        hidden={hidden}
        syncing={sync.isPending}
        canCreate={canWrite}
        onViewChange={setView}
        onPrev={() => setAnchor((d) => step(view, d, -1))}
        onNext={() => setAnchor((d) => step(view, d, 1))}
        onToday={() => setAnchor(new Date())}
        onToggleCalendar={toggleCalendar}
        onRefresh={() => sync.mutate()}
        onCreate={() => setEditing({ event: null, defaultDate: new Date() })}
      />

      {notice && <div className="notice">{notice}</div>}

      <main className={view === 'twoMonths' ? 'calendar-area split' : 'calendar-area'}>
        {view === 'twoMonths' ? (
          <div className="two-months">
            {[0, 1].map((offset) => {
              const monthDate = addMonths(anchor, offset)
              return (
                <section className="month-pane" key={offset}>
                  <h2 className="month-pane-title">{capitalise(formatMonthYear(monthDate))}</h2>
                  <CalendarView
                    view="dayGridMonth"
                    date={toIsoDate(monthDate)}
                    events={calendarEvents}
                    onEventClick={setSelected}
                    onDateClick={(date) => canWrite && setEditing({ event: null, defaultDate: date })}
                  />
                </section>
              )
            })}
          </div>
        ) : (
          <CalendarView
            view={EC_VIEW[view]}
            date={toIsoDate(anchor)}
            events={calendarEvents}
            onEventClick={setSelected}
            onDateClick={(date) => canWrite && setEditing({ event: null, defaultDate: date })}
          />
        )}
      </main>

      {selected && (
        <EventDetails
          event={selected}
          busy={writing}
          onEdit={() => startEdit(selected.source)}
          onDelete={() => confirmDelete(selected.source)}
          onClose={() => setSelected(null)}
        />
      )}

      {editing && (
        <EventForm
          calendars={calendarList}
          event={editing.event}
          defaultDate={editing.defaultDate}
          busy={writing}
          error={errorMessage(create.error ?? update.error)}
          onSubmit={submitForm}
          onDelete={editing.event ? () => confirmDelete(editing.event!) : null}
          onClose={() => {
            create.reset()
            update.reset()
            setEditing(null)
          }}
        />
      )}
    </div>
  )
}

const errorMessage = (error: unknown): string | null =>
  error instanceof Error ? error.message : null

/** One line explaining why the calendar might look empty or a write failed, or nothing. */
function describeProblem(
  health: { sync: { configured: boolean; lastError?: string } } | undefined,
  ...errors: Array<unknown>
): string | null {
  const failed = errors.find((e) => e instanceof Error) as Error | undefined
  if (failed) return failed.message

  if (!health) return null
  if (!health.sync.configured) {
    return 'Источник календарей не настроен — задайте SC_CALDAV_URL, SC_CALDAV_USERNAME и SC_CALDAV_PASSWORD.'
  }
  if (health.sync.lastError) return `Последняя синхронизация не удалась: ${health.sync.lastError}`
  return null
}
