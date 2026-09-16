import { useState, type FormEvent } from 'react'
import type { EventWriteRequest } from '../api/client'
import type { CalendarDto, EventDto, RepeatDto } from '../api/types'
import { parseLocal, shiftIsoDate, toIsoDate, toIsoTime } from '../lib/dates'
import {
  PRESET_ORDER,
  choiceOf,
  describeRepeat,
  isPreset,
  presetLabel,
  ruleFor,
  type RepeatChoice,
} from '../lib/repeat'
import { useBackdropDismiss } from './useBackdropDismiss'

interface EventFormProps {
  calendars: CalendarDto[]
  /** The event being edited, or `null` when creating a new one. */
  event: EventDto | null
  /** Day a new event should land on — whichever the user tapped. */
  defaultDate: Date
  busy: boolean
  error: string | null
  onSubmit: (body: EventWriteRequest) => void
  onDelete: (() => void) | null
  onClose: () => void
}

interface FormValues {
  calendarId: string
  title: string
  description: string
  location: string
  allDay: boolean
  startDate: string
  startTime: string
  endDate: string
  endTime: string
  repeat: RepeatChoice
  /** Last day of the repetition, `YYYY-MM-DD`; empty repeats forever. */
  repeatUntil: string
}

/**
 * Creating and editing an event.
 *
 * The header and the buttons stay put and only the fields scroll between them, so Save is always
 * one tap away however long the form gets — the way phone calendars do it.
 */
export function EventForm({
  calendars,
  event,
  defaultDate,
  busy,
  error,
  onSubmit,
  onDelete,
  onClose,
}: EventFormProps) {
  const writable = calendars.filter((c) => !c.readOnly)
  // Kept apart from the live values: the repeat is sent only if the person actually changed it.
  const [initial] = useState<FormValues>(() => initialValues(event, defaultDate, writable))
  const [values, setValues] = useState<FormValues>(initial)
  const [problem, setProblem] = useState<string | null>(null)
  const backdrop = useBackdropDismiss(onClose)

  const set = <K extends keyof FormValues>(key: K, value: FormValues[K]) =>
    setValues((current) => ({ ...current, [key]: value }))

  const submit = (e: FormEvent) => {
    e.preventDefault()

    const complaint = validate(values)
    if (complaint) {
      setProblem(complaint)
      return
    }
    setProblem(null)
    onSubmit(toRequest(values, initial, event))
  }

  const noCalendars = writable.length === 0
  const firstDay = values.startDate ? parseLocal(values.startDate) : defaultDate
  const repeating = isPreset(values.repeat)

  return (
    <div className="details-backdrop" {...backdrop}>
      <form className="details event-form" onSubmit={submit}>
        <div className="details-header form-header">
          <h2>{event ? 'Изменить событие' : 'Новое событие'}</h2>
          <button type="button" className="details-close" onClick={onClose} aria-label="Закрыть">
            ✕
          </button>
        </div>

        <div className="form-body">
          <label className="field">
            <span>Название</span>
            <input
              type="text"
              value={values.title}
              onChange={(e) => set('title', e.target.value)}
              placeholder="Название события"
              autoFocus
              required
            />
          </label>

          <label className="field">
            <span>Календарь</span>
            <select
              value={values.calendarId}
              onChange={(e) => set('calendarId', e.target.value)}
              // Moving an event between calendars means deleting and recreating it on the
              // server; until that exists, editing keeps the event where it already is.
              disabled={event !== null || noCalendars}
            >
              {writable.map((calendar) => (
                <option key={calendar.id} value={calendar.id}>
                  {calendar.name}
                </option>
              ))}
            </select>
          </label>

          <label className="field field-inline">
            <input
              type="checkbox"
              checked={values.allDay}
              onChange={(e) => set('allDay', e.target.checked)}
            />
            <span>Весь день</span>
          </label>

          <div className="field-row">
            <label className="field">
              <span>Начало</span>
              <input
                type="date"
                value={values.startDate}
                onChange={(e) => set('startDate', e.target.value)}
                required
              />
            </label>
            {!values.allDay && (
              <label className="field field-time">
                <span>Время</span>
                <input
                  type="time"
                  value={values.startTime}
                  onChange={(e) => set('startTime', e.target.value)}
                  required
                />
              </label>
            )}
          </div>

          <div className="field-row">
            <label className="field">
              <span>{values.allDay ? 'Последний день' : 'Конец'}</span>
              <input
                type="date"
                value={values.endDate}
                onChange={(e) => set('endDate', e.target.value)}
                required
              />
            </label>
            {!values.allDay && (
              <label className="field field-time">
                <span>Время</span>
                <input
                  type="time"
                  value={values.endTime}
                  onChange={(e) => set('endTime', e.target.value)}
                  required
                />
              </label>
            )}
          </div>

          <label className="field">
            <span>Место</span>
            <input
              type="text"
              value={values.location}
              onChange={(e) => set('location', e.target.value)}
              placeholder="Не обязательно"
            />
          </label>

          <label className="field">
            <span>Описание</span>
            <textarea
              value={values.description}
              onChange={(e) => set('description', e.target.value)}
              rows={3}
              placeholder="Не обязательно"
            />
          </label>

          {/* Last: a repeating event is made far less often than a one-off one. */}
          <div className="field-row">
            <label className="field">
              <span>Повтор</span>
              <select value={values.repeat} onChange={(e) => set('repeat', e.target.value as RepeatChoice)}>
                <option value="none">Не повторяется</option>
                {PRESET_ORDER.map((id) => (
                  <option key={id} value={id}>
                    {presetLabel(id, firstDay)}
                  </option>
                ))}
                {/* A rule the form cannot show stays selectable as it is — and stays untouched. */}
                {initial.repeat === 'custom' && event && (
                  <option value="custom">Как сейчас: {describeRepeat(event)?.toLowerCase()}</option>
                )}
              </select>
            </label>
            {repeating && (
              <label className="field field-until">
                <span>До какого дня</span>
                <input
                  type="date"
                  value={values.repeatUntil}
                  min={values.startDate}
                  onChange={(e) => set('repeatUntil', e.target.value)}
                />
              </label>
            )}
          </div>
          {repeating && !values.repeatUntil && <p className="field-hint">Без даты — повторяется без конца.</p>}

          {noCalendars && (
            <p className="form-error">Нет ни одного календаря, доступного для записи.</p>
          )}
          {(problem ?? error) && <p className="form-error">{problem ?? error}</p>}
        </div>

        <div className="form-actions">
          {onDelete && (
            <button type="button" className="button danger" onClick={onDelete} disabled={busy}>
              Удалить
            </button>
          )}
          <span className="form-actions-spacer" />
          <button type="button" className="button" onClick={onClose} disabled={busy}>
            Отмена
          </button>
          <button type="submit" className="button primary" disabled={busy || noCalendars}>
            {busy ? 'Сохраняю…' : 'Сохранить'}
          </button>
        </div>
      </form>
    </div>
  )
}

function initialValues(event: EventDto | null, defaultDate: Date, writable: CalendarDto[]): FormValues {
  if (!event) {
    // A new event starts at the next whole hour and runs for one.
    const start = new Date(defaultDate)
    start.setMinutes(0, 0, 0)
    if (start.getHours() === 0) start.setHours(9)
    else start.setHours(start.getHours() + 1)
    const end = new Date(start.getTime() + 60 * 60 * 1000)

    return {
      calendarId: writable[0]?.id ?? '',
      title: '',
      description: '',
      location: '',
      allDay: false,
      startDate: toIsoDate(start),
      startTime: toIsoTime(start),
      endDate: toIsoDate(end),
      endTime: toIsoTime(end),
      repeat: 'none',
      repeatUntil: '',
    }
  }

  const start = parseLocal(event.start)
  const end = parseLocal(event.end)

  return {
    calendarId: event.calendarId,
    title: event.title,
    description: event.description ?? '',
    location: event.location ?? '',
    allDay: event.allDay,
    startDate: toIsoDate(start),
    startTime: toIsoTime(start),
    // The API's all-day end is exclusive; people think in terms of the last day included.
    endDate: event.allDay ? shiftIsoDate(toIsoDate(end), -1) : toIsoDate(end),
    endTime: toIsoTime(end),
    repeat: choiceOf(event),
    repeatUntil: event.repeat?.until ?? '',
  }
}

function validate(values: FormValues): string | null {
  if (!values.title.trim()) return 'Впишите название события.'
  if (!values.calendarId) return 'Выберите календарь.'
  if (isPreset(values.repeat) && values.repeatUntil && values.repeatUntil < values.startDate) {
    return 'Повтор не может закончиться раньше, чем начнётся событие.'
  }

  if (values.allDay) {
    if (values.endDate < values.startDate) return 'Последний день не может быть раньше первого.'
    return null
  }

  const start = `${values.startDate}T${values.startTime}`
  const end = `${values.endDate}T${values.endTime}`
  if (end < start) return 'Событие не может заканчиваться раньше, чем начинается.'
  return null
}

/**
 * The `repeat` to send, or undefined to leave the rule alone — which is also what keeps a rule the
 * form cannot show exactly as it was. Only a change the person made goes out.
 */
function repeatRequest(values: FormValues, initial: FormValues): RepeatDto | undefined {
  const changed =
    values.repeat !== initial.repeat || (isPreset(values.repeat) && values.repeatUntil !== initial.repeatUntil)
  if (!changed) return undefined
  if (values.repeat === 'none') return { frequency: 'NONE' }
  return isPreset(values.repeat) ? ruleFor(values.repeat, values.repeatUntil) : undefined
}

function toRequest(values: FormValues, initial: FormValues, event: EventDto | null): EventWriteRequest {
  const base = {
    calendarId: event ? undefined : values.calendarId,
    title: values.title.trim(),
    description: values.description.trim() || undefined,
    location: values.location.trim() || undefined,
    repeat: repeatRequest(values, initial),
  }

  return values.allDay
    ? {
        ...base,
        allDay: true,
        start: values.startDate,
        // Back to the exclusive end the API expects.
        end: shiftIsoDate(values.endDate, 1),
      }
    : {
        ...base,
        allDay: false,
        start: `${values.startDate}T${values.startTime}`,
        end: `${values.endDate}T${values.endTime}`,
      }
}
