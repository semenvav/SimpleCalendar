import type { CalendarDto, EventDto, EventMark } from '../api/types'
import { MARK_COLOR } from '../lib/marks'

/**
 * The only file that knows what shape the calendar library wants.
 *
 * Everything else works with our own `EventDto`. Swapping Event Calendar for something else —
 * or for hand-written views once the family has said what they actually want — means rewriting
 * this file and the thin component wrapper next to it, and nothing more.
 */

/** What we hang off an event so a click can show details without touching library types. */
export interface EventDetailsData {
  source: EventDto
  calendarName: string
  backgroundColor: string
  textColor: string
  /** The library types `extendedProps` as an open record; this keeps ours assignable to it. */
  [key: string]: unknown
}

export interface CalendarEvent {
  id: string
  start: string
  end: string
  title: string
  allDay: boolean
  backgroundColor: string
  textColor: string
  /** Extra classes on the entry; a layout's CSS decides what, if anything, they mean. */
  className?: string
  /** Inline custom properties for those classes to use. */
  style?: string
  extendedProps: EventDetailsData
}

/**
 * [marks] is a layout's decision: a layout that does not offer the hand-made marks must not show
 * them either, so nothing about a marked event reaches it.
 */
export function toCalendarEvents(
  events: EventDto[],
  calendars: CalendarDto[],
  marks = false,
): CalendarEvent[] {
  const byId = new Map(calendars.map((c) => [c.id, c]))

  return events.map((event) => {
    const calendar = byId.get(event.calendarId)
    const backgroundColor = calendar?.color ?? '#6b7280'
    const textColor = readableTextColor(backgroundColor)
    const mark = marks ? event.mark : undefined

    return {
      id: event.id,
      start: event.start,
      end: event.end,
      title: event.title,
      allDay: event.allDay,
      backgroundColor,
      textColor,
      className: mark ? `event-marked event-marked-${mark}` : undefined,
      style: mark ? markStyle(mark, textColor) : undefined,
      extendedProps: {
        source: event,
        calendarName: calendar?.name ?? 'Календарь',
        backgroundColor,
        textColor,
      },
    }
  })
}

/**
 * The colours a marked entry needs, as custom properties; the shape of the split is the layout's
 * business (see `layouts/integration/integration.css`).
 *
 * The title crosses both halves, so it also carries a shadow in the direction the text is not:
 * white letters get a dark one, dark letters a light one, and either stays readable over a colour
 * chosen for what it means rather than for what it sits under.
 */
function markStyle(mark: EventMark, textColor: string): string {
  const shadow = textColor === '#ffffff' ? 'rgb(0 0 0 / 55%)' : 'rgb(255 255 255 / 75%)'
  return `--mark-color:${MARK_COLOR[mark]};--mark-shadow:${shadow}`
}

/**
 * Picks black or white text for a coloured chip.
 *
 * Uses relative luminance rather than a naive brightness average, so a saturated green and a
 * saturated blue of the same nominal lightness still get legible text.
 */
export function readableTextColor(hex: string): string {
  const value = hex.replace('#', '')
  if (value.length < 6) return '#ffffff'

  const channel = (offset: number) => {
    const srgb = parseInt(value.slice(offset, offset + 2), 16) / 255
    return srgb <= 0.03928 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4)
  }

  const luminance = 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)
  return luminance > 0.45 ? '#16181d' : '#ffffff'
}
