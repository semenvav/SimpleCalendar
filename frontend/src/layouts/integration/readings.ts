/** Numbers as a Russian wall reads them: a comma, and never more than one decimal. */
const NUMBER = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 })

/**
 * One value with its unit — `23,4°C`, `47%` — or a dash where there is nothing to show.
 *
 * A dash rather than a blank: a sensor that has gone quiet should say so, not disappear and
 * leave the wall looking as if nobody ever set it up.
 */
export function reading(value: number | null | undefined, unit: string): string {
  return value === null || value === undefined ? '—' : `${NUMBER.format(value)}${unit}`
}
