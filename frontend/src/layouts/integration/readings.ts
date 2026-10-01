import type { SensorReading } from '../../api/types'

/** Numbers as a Russian wall reads them: a comma, and never more than one decimal. */
const NUMBER = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 })

/** Pressure the way Home Assistant shows it, which is the way the household knows it: `1 008,30`. */
const PRESSURE = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/**
 * One value with its unit — `23,4°`, `47%` — or a dash where there is nothing to show.
 *
 * A dash rather than a blank: a sensor that has gone quiet should say so, not disappear and
 * leave the wall looking as if nobody ever set it up.
 */
export function reading(value: number | null | undefined, unit: string): string {
  return value == null ? '—' : `${NUMBER.format(value)}${unit}`
}

/**
 * Temperature and humidity together, the one form the wall uses for both: `27,9° 53,3%`.
 *
 * The degree sign goes without its letter whatever Home Assistant reports — the household has
 * one scale, and the C was only taking room.
 */
export function climateReading(temperature: number | null | undefined, humidity: number | null | undefined): string {
  return `${reading(temperature, '°')} ${reading(humidity, '%')}`
}

/**
 * A place's numbers: `27,9° 53,3%`, or just `27,9°` for one with no humidity sensor at all — a
 * place whose sensor has merely gone quiet keeps its place and shows a dash.
 */
export function placeReading(place: SensorReading): string {
  const hasHumidity = place.humidity != null || place.humidityUnit != null
  return hasHumidity ? climateReading(place.temperature, place.humidity) : reading(place.temperature, '°')
}

/** hPa and millibars are the same number; the wall says the word the household uses. */
export function pressureReading(hPa: number | null | undefined): string {
  return hPa == null ? '—' : `${PRESSURE.format(hPa)} мбар`
}
