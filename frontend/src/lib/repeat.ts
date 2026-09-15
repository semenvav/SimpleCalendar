import type { EventDto, Frequency, RepeatDto } from '../api/types'
import { formatDayMonth, parseLocal } from './dates'

/**
 * The repeat options of the event form, and how a rule reads in words.
 *
 * The form offers a handful of presets. Anything else a phone may have written — "the second
 * Tuesday", "Mon, Wed and Fri" — shows as "как сейчас" and is kept untouched unless somebody
 * picks another option.
 */

export type RepeatChoice = 'none' | 'daily' | 'weekly' | 'biweekly' | 'monthly' | 'yearly' | 'custom'

export type Preset = Exclude<RepeatChoice, 'none' | 'custom'>

const PRESETS: Record<Preset, { frequency: Frequency; interval: number }> = {
  daily: { frequency: 'DAILY', interval: 1 },
  weekly: { frequency: 'WEEKLY', interval: 1 },
  biweekly: { frequency: 'WEEKLY', interval: 2 },
  monthly: { frequency: 'MONTHLY', interval: 1 },
  yearly: { frequency: 'YEARLY', interval: 1 },
}

export const PRESET_ORDER: readonly Preset[] = ['daily', 'weekly', 'biweekly', 'monthly', 'yearly']

export const isPreset = (choice: RepeatChoice): choice is Preset => Object.hasOwn(PRESETS, choice)

/** Which option of the form an event's rule is. */
export function choiceOf(event: EventDto | null): RepeatChoice {
  if (!event?.recurring) return 'none'
  const rule = event.repeat
  if (!rule) return 'custom'
  const preset = PRESET_ORDER.find(
    (id) => PRESETS[id].frequency === rule.frequency && PRESETS[id].interval === (rule.interval ?? 1),
  )
  return preset ?? 'custom'
}

/** The rule a preset stands for; an empty [until] repeats forever. */
export const ruleFor = (choice: Preset, until: string): RepeatDto => ({
  ...PRESETS[choice],
  until: until || undefined,
})

/** As in "по понедельникам", indexed like `Date.getDay()`. */
const ON_WEEKDAYS = [
  'по воскресеньям',
  'по понедельникам',
  'по вторникам',
  'по средам',
  'по четвергам',
  'по пятницам',
  'по субботам',
]

/** A preset spelled out for the event's first day: "Каждую неделю, по вторникам". */
export function presetLabel(choice: Preset, start: Date): string {
  switch (choice) {
    case 'daily':
      return 'Каждый день'
    case 'weekly':
      return `Каждую неделю, ${ON_WEEKDAYS[start.getDay()]}`
    case 'biweekly':
      return `Раз в две недели, ${ON_WEEKDAYS[start.getDay()]}`
    case 'monthly':
      return `Каждый месяц, ${start.getDate()}-го числа`
    case 'yearly':
      return `Каждый год, ${formatDayMonth(start)}`
  }
}

/** A series' rule in words, for the event card; `null` for an event that does not repeat. */
export function describeRepeat(event: EventDto): string | null {
  if (!event.recurring) return null
  const rule = event.repeat
  if (!rule || rule.frequency === 'NONE') return 'По особому правилу'

  const start = parseLocal(event.start)
  const choice = choiceOf(event)
  const phrase = isPreset(choice) ? presetLabel(choice, start) : every(rule.frequency, rule.interval ?? 1)
  return rule.until ? `${phrase}, до ${formatUntil(parseLocal(rule.until), start)}` : phrase
}

const UNITS: Record<Frequency, [string, string, string]> = {
  DAILY: ['день', 'дня', 'дней'],
  WEEKLY: ['неделю', 'недели', 'недель'],
  MONTHLY: ['месяц', 'месяца', 'месяцев'],
  YEARLY: ['год', 'года', 'лет'],
}

/** "Раз в 3 недели", "раз в 5 лет" — for the intervals the presets do not cover. */
function every(frequency: Frequency, interval: number): string {
  const [one, few, many] = UNITS[frequency]
  const mod10 = interval % 10
  const mod100 = interval % 100
  const unit =
    mod10 === 1 && mod100 !== 11 ? one : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? few : many
  return `Раз в ${interval} ${unit}`
}

/** The last day, with the year only when it is not the year the event is in. */
function formatUntil(until: Date, start: Date): string {
  const day = formatDayMonth(until)
  return until.getFullYear() === start.getFullYear() ? day : `${day} ${until.getFullYear()}`
}
