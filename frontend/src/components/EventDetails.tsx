import type { CSSProperties } from 'react'
import type { EventMark } from '../api/types'
import type { EventDetailsData } from '../calendar/adapter'
import { addDays, capitalise, formatDayMonth, formatTime, formatWeekday, isSameDay, parseLocal } from '../lib/dates'
import { MARK_ACTION, MARK_COLOR, MARK_LABEL, MARKS } from '../lib/marks'
import { describeRepeat } from '../lib/repeat'
import { useBackdropDismiss } from './useBackdropDismiss'

interface EventDetailsProps {
  event: EventDetailsData
  busy: boolean
  onEdit: () => void
  onDelete: () => void
  /**
   * Puts a hand-made mark on the event, or — tapped on the mark it already has — takes it off.
   *
   * Absent in a layout that does not offer marks, and then the card says nothing about them at
   * all: an event somebody cancelled from a phone still looks ordinary there.
   */
  onMark?: ((mark: EventMark) => void) | null
  onClose: () => void
}

export function EventDetails({ event, busy, onEdit, onDelete, onMark, onClose }: EventDetailsProps) {
  const source = event.source
  const repeat = describeRepeat(source)
  const backdrop = useBackdropDismiss(onClose)
  // A read-only calendar gets no buttons at all, so it needs neither the marks nor the room.
  const marks = onMark && !source.readOnly ? onMark : null

  return (
    <div className="details-backdrop" {...backdrop}>
      {/* Wider than the plain card: it has two more buttons to hold on one row. */}
      <aside className={`details${marks ? ' details-wide' : ''}`}>
        <div className="details-header" style={{ background: event.backgroundColor, color: event.textColor }}>
          <h2>{source.title}</h2>
          <button className="details-close" onClick={onClose} aria-label="Закрыть">
            ✕
          </button>
        </div>

        <dl className="details-body">
          <dt>Когда</dt>
          <dd>{describeWhen(source.start, source.end, source.allDay)}</dd>

          <dt>Календарь</dt>
          <dd>
            <span className="legend-dot" style={{ '--chip-color': event.backgroundColor } as CSSProperties} />
            {event.calendarName}
          </dd>

          {marks && source.mark && (
            <>
              <dt>Статус</dt>
              <dd>
                <span className="legend-dot" style={{ '--chip-color': MARK_COLOR[source.mark] } as CSSProperties} />
                {MARK_LABEL[source.mark]}
              </dd>
            </>
          )}

          {source.location && (
            <>
              <dt>Место</dt>
              <dd>{source.location}</dd>
            </>
          )}

          {repeat && (
            <>
              <dt>Повтор</dt>
              <dd>{repeat}</dd>
            </>
          )}

          {source.description && (
            <>
              <dt>Описание</dt>
              <dd className="details-description">{source.description}</dd>
            </>
          )}
        </dl>

        {source.readOnly ? (
          <p className="details-note">Этот календарь доступен только для чтения.</p>
        ) : (
          // For a repeating event every button goes on to ask which part of the series is meant.
          //
          // With the marks there are four of them, and they are laid out by `.details-actions`
          // rather than by the stretching spacers the two-button case uses: spacers count as
          // flex children, so they add gaps of their own and push a row that would otherwise fit
          // onto a second line — which is how they ended up three and one.
          <div className={`form-actions${marks ? ' details-actions' : ''}`}>
            <button type="button" className="button danger" onClick={onDelete} disabled={busy}>
              Удалить
            </button>

            {marks ? (
              MARKS.map((mark) => (
                <button
                  key={mark}
                  type="button"
                  className={`button mark${source.mark === mark ? ' on' : ''}`}
                  style={{ '--mark-color': MARK_COLOR[mark] } as CSSProperties}
                  onClick={() => marks(mark)}
                  disabled={busy}
                  aria-pressed={source.mark === mark}
                  title={source.mark === mark ? `Снять отметку «${MARK_LABEL[mark]}»` : undefined}
                >
                  {MARK_ACTION[mark]}
                </button>
              ))
            ) : (
              <span className="form-actions-spacer" />
            )}

            <button type="button" className="button primary" onClick={onEdit} disabled={busy}>
              Изменить
            </button>
          </div>
        )}
      </aside>
    </div>
  )
}

function describeWhen(start: string, end: string, allDay: boolean): string {
  const from = parseLocal(start)

  if (allDay) {
    // The API end is exclusive, so the last day the event actually covers is one earlier.
    const lastDay = addDays(parseLocal(end), -1)
    if (isSameDay(from, lastDay)) return capitalise(formatWeekday(from))
    return `${capitalise(formatDayMonth(from))} — ${formatDayMonth(lastDay)}`
  }

  const to = parseLocal(end)
  if (isSameDay(from, to)) {
    return `${capitalise(formatWeekday(from))}, ${formatTime(from)} — ${formatTime(to)}`
  }
  return `${capitalise(formatDayMonth(from))} ${formatTime(from)} — ${formatDayMonth(to)} ${formatTime(to)}`
}
