import type { CSSProperties } from 'react'
import type { EventDetailsData } from '../calendar/adapter'
import { addDays, capitalise, formatDayMonth, formatTime, formatWeekday, isSameDay, parseLocal } from '../lib/dates'

interface EventDetailsProps {
  event: EventDetailsData
  busy: boolean
  onEdit: () => void
  onDelete: () => void
  onClose: () => void
}

export function EventDetails({ event, busy, onEdit, onDelete, onClose }: EventDetailsProps) {
  const source = event.source
  const editable = !source.readOnly && !source.recurring

  return (
    <div className="details-backdrop" onClick={onClose}>
      <aside className="details" onClick={(e) => e.stopPropagation()}>
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

          {source.location && (
            <>
              <dt>Место</dt>
              <dd>{source.location}</dd>
            </>
          )}

          {source.recurring && (
            <>
              <dt>Повтор</dt>
              <dd>Повторяющееся событие</dd>
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
          <>
            {source.recurring && (
              <p className="details-note">
                Правка отдельных повторений появится на следующем этапе. Сейчас серию можно удалить
                только целиком.
              </p>
            )}
            <div className="form-actions">
              <button
                type="button"
                className="button danger"
                onClick={onDelete}
                disabled={busy}
              >
                {source.recurring ? 'Удалить все повторения' : 'Удалить'}
              </button>
              <span className="form-actions-spacer" />
              {editable && (
                <button type="button" className="button primary" onClick={onEdit} disabled={busy}>
                  Изменить
                </button>
              )}
            </div>
          </>
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
