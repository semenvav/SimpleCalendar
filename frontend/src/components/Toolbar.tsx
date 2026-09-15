import type { CSSProperties } from 'react'
import type { CalendarDto } from '../api/types'
import type { ViewId } from '../views'

const VIEW_LABELS: Array<{ id: ViewId; label: string }> = [
  { id: 'twoMonths', label: '2 месяца' },
  { id: 'month', label: 'Месяц' },
  { id: 'week', label: 'Неделя' },
  { id: 'list', label: 'Список' },
]

interface ToolbarProps {
  title: string
  view: ViewId
  calendars: CalendarDto[]
  hidden: ReadonlySet<string>
  syncing: boolean
  onViewChange: (view: ViewId) => void
  onPrev: () => void
  onNext: () => void
  onToday: () => void
  onToggleCalendar: (id: string) => void
  onRefresh: () => void
  onCreate: () => void
  canCreate: boolean
}

export function Toolbar({
  title,
  view,
  calendars,
  hidden,
  syncing,
  onViewChange,
  onPrev,
  onNext,
  onToday,
  onToggleCalendar,
  onRefresh,
  onCreate,
  canCreate,
}: ToolbarProps) {
  return (
    <header className="toolbar">
      <div className="toolbar-row">
        <div className="nav-group">
          <button className="nav-button" onClick={onPrev} aria-label="Назад">
            ‹
          </button>
          <button className="nav-button today" onClick={onToday}>
            Сегодня
          </button>
          <button className="nav-button" onClick={onNext} aria-label="Вперёд">
            ›
          </button>
        </div>

        <h1 className="toolbar-title">{title}</h1>

        <div className="view-group">
          {VIEW_LABELS.map(({ id, label }) => (
            <button
              key={id}
              className={`view-button${view === id ? ' active' : ''}`}
              onClick={() => onViewChange(id)}
            >
              {label}
            </button>
          ))}
          <button
            className={`nav-button refresh${syncing ? ' spinning' : ''}`}
            onClick={onRefresh}
            disabled={syncing}
            aria-label="Обновить"
            title="Синхронизировать с Baikal"
          >
            ⟳
          </button>
          <button
            className="nav-button create"
            onClick={onCreate}
            disabled={!canCreate}
            aria-label="Новое событие"
            title="Новое событие"
          >
            +
          </button>
        </div>
      </div>

      {calendars.length > 0 && (
        <div className="legend">
          {calendars.map((calendar) => {
            const off = hidden.has(calendar.id)
            return (
              <button
                key={calendar.id}
                className={`legend-chip${off ? ' off' : ''}`}
                onClick={() => onToggleCalendar(calendar.id)}
                style={{ '--chip-color': calendar.color } as CSSProperties}
              >
                <span className="legend-dot" />
                {calendar.name}
              </button>
            )
          })}
        </div>
      )}
    </header>
  )
}
