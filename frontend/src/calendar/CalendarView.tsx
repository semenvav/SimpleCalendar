import { useEffect, useRef } from 'react'
import {
  createCalendar,
  destroyCalendar,
  DayGrid,
  Interaction,
  List,
  TimeGrid,
  type Calendar,
} from '@event-calendar/core'
import '@event-calendar/core/index.css'
import type { CalendarEvent, EventDetailsData } from './adapter'

export interface CalendarViewProps {
  /** An Event Calendar view id: `dayGridMonth`, `timeGridWeek`, `listMonth`, … */
  view: string
  /** `YYYY-MM-DD` — the day the view is anchored on. A string, so React can compare it cheaply. */
  date: string
  events: CalendarEvent[]
  onEventClick?: (details: EventDetailsData) => void
  /** Tapping empty space starts a new event on that day. */
  onDateClick?: (date: Date) => void
}

/** Hides the library's own toolbar; navigation lives in our React toolbar instead. */
const NO_TOOLBAR = { start: '', center: '', end: '' }

/**
 * Runs [handler] once the browser's own click for the current tap is over.
 *
 * Event Calendar reports a tap on an empty day from `pointerup`, before the `click` the browser
 * sends for that same tap. On a touch screen that click goes to whatever is under the finger at
 * the moment it is sent — so a dialog opened straight away caught it: on its backdrop it closed
 * again at once, on a date field it opened the date picker. A mouse click goes where the button
 * went down, which is why this never showed up on a computer. The timeout covers a `pointerup`
 * that no click follows.
 */
function afterClick(handler: () => void) {
  let done = false
  const run = () => {
    if (done) return
    done = true
    window.removeEventListener('click', onClick, true)
    window.clearTimeout(fallback)
    handler()
  }
  // Called while that click is still being dispatched; step out of it before opening anything.
  const onClick = () => window.setTimeout(run, 0)
  window.addEventListener('click', onClick, true)
  const fallback = window.setTimeout(run, 500)
}

/**
 * React wrapper around Event Calendar.
 *
 * Navigation is deliberately *not* delegated to the library: the surrounding app owns the
 * current date and view and pushes them down. That keeps one source of truth, avoids the
 * feedback loop a `datesSet` callback would create, and leaves the toolbar entirely ours to
 * restyle — which is the part the family will have opinions about.
 */
export function CalendarView({ view, date, events, onEventClick, onDateClick }: CalendarViewProps) {
  const host = useRef<HTMLDivElement>(null)
  const instance = useRef<Calendar | null>(null)

  // Kept in refs so changing a handler never forces the calendar to be rebuilt.
  const clickHandler = useRef(onEventClick)
  clickHandler.current = onEventClick
  const dateHandler = useRef(onDateClick)
  dateHandler.current = onDateClick

  useEffect(() => {
    if (!host.current) return

    const calendar = createCalendar(host.current, [DayGrid, TimeGrid, List, Interaction], {
      view,
      date,
      events,
      headerToolbar: NO_TOOLBAR,
      firstDay: 1,
      locale: 'ru-RU',
      height: '100%',
      dayMaxEvents: true,
      nowIndicator: true,
      // Family hours: a wall calendar showing 00:00–06:00 wastes half the screen.
      slotMinTime: '06:00:00',
      slotMaxTime: '24:00:00',
      slotDuration: '00:30:00',
      eventTimeFormat: { hour: '2-digit', minute: '2-digit' },
      buttonText: { today: 'Сегодня' },
      noEventsContent: 'Событий нет',
      // Reported from the click itself, so a card opened here cannot catch it.
      eventClick: (info: Calendar.EventClickInfo) => {
        clickHandler.current?.(info.event.extendedProps as EventDetailsData)
      },
      dateClick: (info: Calendar.DateClickInfo) => {
        const tapped = info.date
        afterClick(() => dateHandler.current?.(tapped))
      },
    })

    instance.current = calendar
    return () => {
      void destroyCalendar(calendar)
      instance.current = null
    }
    // Created once; every subsequent change goes through setOption below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    instance.current?.setOption('view', view)
  }, [view])

  useEffect(() => {
    instance.current?.setOption('date', date)
  }, [date])

  useEffect(() => {
    instance.current?.setOption('events', events)
  }, [events])

  return <div className="calendar-host" ref={host} />
}
