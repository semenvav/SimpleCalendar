import type { EventMark } from '../api/types'

/**
 * How a hand-made mark looks and what it is called.
 *
 * One place for it, because three things have to agree: the entry on the calendar, the button
 * on the event card and the question about a repeating series.
 */

/** The left half of an entry stays the calendar's colour; this is what the right half says. */
export const MARK_COLOR: Record<EventMark, string> = {
  moved: '#ef7711',
  cancelled: '#d00005',
}

/** On the event card, next to «Когда» and «Календарь». */
export const MARK_LABEL: Record<EventMark, string> = {
  moved: 'Перенесено',
  cancelled: 'Отменено',
}

/** On the button that puts the mark on — or, tapped again, takes it off. */
export const MARK_ACTION: Record<EventMark, string> = {
  moved: 'Перенести',
  cancelled: 'Отменить',
}

/** The heading of the question a repeating event asks before it is marked. */
export const MARK_QUESTION: Record<EventMark, string> = {
  moved: 'Отметить перенос',
  cancelled: 'Отметить отмену',
}

/** In toolbar order, and the order the buttons sit in on the card. */
export const MARKS: readonly EventMark[] = ['cancelled', 'moved']
