import { useState } from 'react'
import type { HomeAssistantStates, Weather, WeatherDay } from '../../api/types'
import { useIntegration } from '../../app/useIntegration'
import { useBackdropDismiss } from '../../components/useBackdropDismiss'
import { addDays, toIsoDate } from '../../lib/dates'
import { climateReading, placeReading, pressureReading } from './readings'

/** A thermometer, drawn for the same reason as the layout icon: a glyph may not exist on the tablet. */
function ThermometerIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      width="20"
      height="20"
      aria-hidden="true"
      focusable="false"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
    >
      <path d="M8 11.5V4a2 2 0 0 1 4 0v7.5a3.5 3.5 0 1 1-4 0z" />
      <path d="M10 13V7" />
      <circle cx="10" cy="14.4" r="1.5" fill="currentColor" stroke="none" />
    </svg>
  )
}

/**
 * The button beside the sensor strip, and the window it opens.
 *
 * The strip stays what it was — a glance from across the kitchen, as many rooms as fit in the
 * height of a button. Everything else is one tap away: every room in the house, and the day's
 * extremes outdoors.
 *
 * Shows nothing until there is something to open: Home Assistant or the weather has to have
 * answered at least once.
 */
export function ClimateButton() {
  const states = useIntegration<HomeAssistantStates>('homeassistant', 60)
  const weather = useIntegration<Weather>('weather', 300)
  const [open, setOpen] = useState(false)

  if (!states?.climate?.length && !weather) return null

  return (
    <>
      <button
        type="button"
        className="nav-button climate"
        onClick={() => setOpen(true)}
        aria-label="Климат"
        title="Все комнаты и прогноз"
      >
        <ThermometerIcon />
      </button>
      {open && <ClimateDialog states={states} weather={weather} onClose={() => setOpen(false)} />}
    </>
  )
}

interface ClimateDialogProps {
  states: HomeAssistantStates | null
  weather: Weather | null
  onClose: () => void
}

/**
 * Two columns, each scrolling on its own: the rooms on the left, outdoors on the right.
 *
 * Every line is one moment — «Максимум: 31° 45%» is the warmest hour and the humidity at that
 * hour, not the day's two highest numbers side by side. The pressure sits with the weather: it is
 * the weather's, and the sensors indoors only measure it.
 */
function ClimateDialog({ states, weather, onClose }: ClimateDialogProps) {
  const backdrop = useBackdropDismiss(onClose)
  const places = states?.climate ?? []

  // By date rather than by position, as in the strip: a provider whose forecast starts at the
  // next three-hour slot may have nothing for the rest of today.
  const today = weather?.days.find((day) => day.date === toIsoDate(new Date()))
  const tomorrow = weather?.days.find((day) => day.date === toIsoDate(addDays(new Date(), 1)))

  return (
    <div className="details-backdrop" {...backdrop}>
      <div className="details climate-dialog" role="dialog" aria-modal="true" aria-label="Климат">
        <div className="details-header form-header">
          <h2>Климат</h2>
          <button className="details-close" onClick={onClose} aria-label="Закрыть">
            ✕
          </button>
        </div>

        <div className="climate-body">
          {places.length > 0 && (
            <section className="climate-column">
              {places.map((place) => (
                <Line key={place.name} name={place.name} value={placeReading(place)} />
              ))}
            </section>
          )}

          {(weather || places.length > 0) && (
            <section className="climate-column">
              {weather && (
                <Line name="Сейчас" value={climateReading(weather.current.temperature, weather.current.humidity)} />
              )}
              {places.length > 0 && <Line name="Давление" value={pressureReading(states?.pressure)} />}
              {weather && (
                <>
                  <Day title="Сегодня" day={today} />
                  <Day title="Завтра" day={tomorrow} />
                </>
              )}
            </section>
          )}
        </div>
      </div>
    </div>
  )
}

/** A day's extremes; dashes for a day the forecast does not reach. */
function Day({ title, day }: { title: string; day: WeatherDay | undefined }) {
  return (
    <>
      <h3 className="climate-heading">{title}</h3>
      <Line name="Максимум" value={climateReading(day?.temperatureMax, day?.humidityAtMax)} />
      <Line name="Минимум" value={climateReading(day?.temperatureMin, day?.humidityAtMin)} />
    </>
  )
}

/** «Коридор: 27,9° 53,3%» — the name muted, the numbers kept on one line. */
function Line({ name, value }: { name: string; value: string }) {
  return (
    <p className="climate-line">
      <span className="climate-name">{name}:</span> <span className="climate-value">{value}</span>
    </p>
  )
}
