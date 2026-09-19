import { Dialogs } from '../../app/Dialogs'
import { useCalendarApp } from '../../app/useCalendarApp'
import { CalendarView } from '../../calendar/CalendarView'
import { addMonths, capitalise, formatMonthYear, toIsoDate } from '../../lib/dates'
import { EC_VIEW, type ViewId } from '../../views'
import { Toolbar } from './Toolbar'
import './integration.css'

/** In toolbar order. */
const VIEWS: readonly ViewId[] = ['twoMonths', 'month', 'week', 'list']

/**
 * The wall as it is meant to be read from across the kitchen: two months side by side, what the
 * house's own sensors say and what the weather is doing, all in the one strip across the top.
 *
 * It also offers the hand-made marks — cancelled, moved — which «Классика» deliberately does
 * not: an appointment that fell through stays on the calendar, half its own colour and half the
 * colour of what happened to it.
 */
export function IntegrationLayout() {
  const app = useCalendarApp({
    views: VIEWS,
    defaultView: 'twoMonths',
    // Its own key: this layout starts on two months, and «Классика» must not inherit that.
    viewKey: 'simple-calendar.view.integration',
    marks: true,
  })

  return (
    <div className="layout-integration">
      <Toolbar app={app} views={VIEWS} />

      {app.notice && <div className="notice">{app.notice}</div>}

      <main className={app.view === 'twoMonths' ? 'calendar-area split' : 'calendar-area'}>
        {app.view === 'twoMonths' ? (
          <div className="two-months">
            {[0, 1].map((offset) => {
              const monthDate = addMonths(app.anchor, offset)
              return (
                <section className="month-pane" key={offset}>
                  <h2 className="month-pane-title">{capitalise(formatMonthYear(monthDate))}</h2>
                  <CalendarView
                    view="dayGridMonth"
                    date={toIsoDate(monthDate)}
                    events={app.events}
                    onEventClick={app.openEvent}
                    onDateClick={app.startCreate}
                  />
                </section>
              )
            })}
          </div>
        ) : (
          <CalendarView
            view={EC_VIEW[app.view]}
            date={toIsoDate(app.anchor)}
            events={app.events}
            onEventClick={app.openEvent}
            onDateClick={app.startCreate}
          />
        )}
      </main>

      <Dialogs app={app} />
    </div>
  )
}
