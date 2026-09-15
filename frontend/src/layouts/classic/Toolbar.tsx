import type { CSSProperties } from 'react'
import type { CalendarApp } from '../../app/useCalendarApp'
import type { ViewId } from '../../views'
import { LayoutPicker } from '../LayoutPicker'

const VIEW_LABELS: Record<ViewId, string> = {
  twoMonths: '2 месяца',
  month: 'Месяц',
  week: 'Неделя',
  list: 'Список',
}

interface ToolbarProps {
  app: CalendarApp
  views: readonly ViewId[]
}

export function Toolbar({ app, views }: ToolbarProps) {
  return (
    <header className="toolbar">
      <div className="toolbar-row">
        <div className="nav-group">
          <button className="nav-button" onClick={app.prev} aria-label="Назад">
            ‹
          </button>
          <button className="nav-button today" onClick={app.today}>
            Сегодня
          </button>
          <button className="nav-button" onClick={app.next} aria-label="Вперёд">
            ›
          </button>
        </div>

        <h1 className="toolbar-title">{app.title}</h1>

        <div className="view-group">
          {views.map((id) => (
            <button
              key={id}
              className={`view-button${app.view === id ? ' active' : ''}`}
              onClick={() => app.setView(id)}
            >
              {VIEW_LABELS[id]}
            </button>
          ))}
          <button
            className={`nav-button refresh${app.syncing ? ' spinning' : ''}`}
            onClick={app.refresh}
            disabled={app.syncing}
            aria-label="Обновить"
            title="Синхронизировать с Baikal"
          >
            ⟳
          </button>
          <button
            className="nav-button create"
            onClick={() => app.startCreate()}
            disabled={!app.canWrite}
            aria-label="Новое событие"
            title="Новое событие"
          >
            +
          </button>
          <LayoutPicker className="nav-button" />
        </div>
      </div>

      {app.calendars.length > 0 && (
        <div className="legend">
          {app.calendars.map((calendar) => {
            const off = app.hidden.has(calendar.id)
            return (
              <button
                key={calendar.id}
                className={`legend-chip${off ? ' off' : ''}`}
                onClick={() => app.toggleCalendar(calendar.id)}
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
