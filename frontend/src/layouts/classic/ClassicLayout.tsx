import { Dialogs } from '../../app/Dialogs'
import { useCalendarApp } from '../../app/useCalendarApp'
import { CalendarView } from '../../calendar/CalendarView'
import { addMonths, capitalise, formatMonthYear, toIsoDate } from '../../lib/dates'
import { EC_VIEW, type ViewId } from '../../views'
import { Toolbar } from './Toolbar'
import './classic.css'

/** In toolbar order. */
const VIEWS: readonly ViewId[] = ['twoMonths', 'month', 'week', 'list']

/**
 * The first layout, as the family first saw it: navigation and views across the top, calendars
 * in a row beneath, the calendar filling the rest.
 *
 * Frozen — read `layouts/registry.ts` before changing anything here.
 */
export function ClassicLayout() {
  // The storage key predates layouts, so devices keep the view they already had.
  const app = useCalendarApp({ views: VIEWS, defaultView: 'month', viewKey: 'simple-calendar.view' })

  return (
    <div className="layout-classic">
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
