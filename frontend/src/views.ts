import {
  addDays,
  addMonths,
  capitalise,
  endOfMonth,
  formatMonth,
  formatMonthYear,
  formatWeekRange,
  startOfMonth,
  startOfWeek,
  toIsoDate,
} from './lib/dates'

export type ViewId = 'month' | 'twoMonths' | 'week' | 'list'

/** Event Calendar's view id for each of ours. */
export const EC_VIEW: Record<ViewId, string> = {
  month: 'dayGridMonth',
  twoMonths: 'dayGridMonth',
  week: 'timeGridWeek',
  list: 'listMonth',
}

/**
 * The window to ask the server for.
 *
 * Padded by a week on either side of a month so the six-week grid — which always shows a few
 * days of the neighbouring months — is never short of events. Over-fetching a fortnight costs
 * nothing at household scale and removes a whole class of "the 1st looks empty" reports.
 */
export function rangeFor(view: ViewId, anchor: Date): { from: string; to: string } {
  switch (view) {
    case 'month':
    case 'list':
      return {
        from: toIsoDate(addDays(startOfMonth(anchor), -7)),
        to: toIsoDate(addDays(endOfMonth(anchor), 8)),
      }
    case 'twoMonths':
      return {
        from: toIsoDate(addDays(startOfMonth(anchor), -7)),
        to: toIsoDate(addDays(endOfMonth(addMonths(anchor, 1)), 8)),
      }
    case 'week': {
      const start = startOfWeek(anchor)
      return { from: toIsoDate(addDays(start, -1)), to: toIsoDate(addDays(start, 8)) }
    }
  }
}

/** How far one press of the arrows moves. */
export function step(view: ViewId, anchor: Date, direction: 1 | -1): Date {
  return view === 'week' ? addDays(anchor, 7 * direction) : addMonths(anchor, direction)
}

export function titleFor(view: ViewId, anchor: Date): string {
  switch (view) {
    case 'month':
    case 'list':
      return capitalise(formatMonthYear(anchor))
    case 'twoMonths':
      return `${capitalise(formatMonth(anchor))} — ${capitalise(formatMonthYear(addMonths(anchor, 1)))}`
    case 'week':
      return capitalise(formatWeekRange(anchor))
  }
}
