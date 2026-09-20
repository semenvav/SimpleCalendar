import type { CSSProperties } from 'react'
import type { CalendarApp } from '../../app/useCalendarApp'
import type { ViewId } from '../../views'
import { LayoutPicker } from '../LayoutPicker'
import { SensorStrip } from './SensorStrip'
import { WeatherStrip } from './WeatherStrip'

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

/**
 * Navigation on the left, views on the right, and between them what the house itself has to say —
 * the sensors and the weather. Calendars in a row beneath, as in «Классика».
 *
 * The readings take a row of the toolbar rather than one of their own, and are sized to stay
 * within the height of a button. Both together are what keeps this toolbar exactly as tall as the
 * classic one, so switching layouts on the wall does not shift the months up or down.
 */
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

        {/*
          No heading: the calendar names its own period. The spacer is what keeps the readings
          against the edges of the middle whichever of them is configured — with one strip alone,
          `space-between` would simply put it on the left.
        */}
        <div className="toolbar-middle">
          <SensorStrip />
          <span className="toolbar-gap" />
          <WeatherStrip />
        </div>

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
          <LayoutPicker className="nav-button layout" compact />
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
