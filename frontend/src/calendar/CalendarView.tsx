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
      eventClick: (info: Calendar.EventClickInfo) => {
        clickHandler.current?.(info.event.extendedProps as EventDetailsData)
      },
      dateClick: (info: Calendar.DateClickInfo) => {
        dateHandler.current?.(info.date)
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
