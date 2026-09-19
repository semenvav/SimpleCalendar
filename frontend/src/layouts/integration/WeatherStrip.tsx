import type { Weather } from '../../api/types'
import { useIntegration } from '../../app/useIntegration'
import { addDays, toIsoDate } from '../../lib/dates'
import { reading } from './readings'

/**
 * Today and tomorrow: «Сегодня: 27,4° 64%», «Завтра: 30° 62%».
 *
 * Today is the reading as it is now — what somebody stepping out of the door is about to walk
 * into — while tomorrow can only be the forecast's high for the day. Tomorrow is found by its
 * date rather than taken as `days[1]`: a provider whose forecast starts at the next three-hour
 * slot may have no entry for today at all.
 */
export function WeatherStrip() {
  const weather = useIntegration<Weather>('weather', 300)
  if (!weather) return null

  const tomorrow = weather.days.find((day) => day.date === toIsoDate(addDays(new Date(), 1)))

  return (
    <div className="strip weather">
      <p className="strip-line">
        <span className="strip-name">Сегодня:</span> {reading(weather.current.temperature, '°')}{' '}
        {reading(weather.current.humidity, '%')}
      </p>
      {tomorrow && (
        <p className="strip-line">
          <span className="strip-name">Завтра:</span> {reading(tomorrow.temperatureMax, '°')}{' '}
          {reading(tomorrow.humidity, '%')}
        </p>
      )}
    </div>
  )
}
