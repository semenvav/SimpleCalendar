import type { HomeAssistantStates } from '../../api/types'
import { useIntegration } from '../../app/useIntegration'
import { reading } from './readings'

/**
 * The rooms of `SC_HA_SENSORS`, one line each: «Спальня: 23,4°C 47%».
 *
 * Nothing at all until Home Assistant is configured and has answered once — an empty strip would
 * only push the title off centre for no reason.
 */
export function SensorStrip() {
  const states = useIntegration<HomeAssistantStates>('homeassistant', 60)
  const sensors = states?.sensors ?? []

  if (sensors.length === 0) return null

  return (
    <div className="strip sensors">
      {sensors.map((sensor) => (
        <p className="strip-line" key={sensor.name}>
          <span className="strip-name">{sensor.name}:</span>{' '}
          {reading(sensor.temperature, sensor.temperatureUnit ?? '°')}
          {/* A room paired with no humidity entity at all says nothing about humidity; one whose
              sensor has merely gone quiet keeps its place and shows a dash. */}
          {(sensor.humidity !== null || sensor.humidityUnit !== null) && (
            <> {reading(sensor.humidity, sensor.humidityUnit ?? '%')}</>
          )}
        </p>
      ))}
    </div>
  )
}
