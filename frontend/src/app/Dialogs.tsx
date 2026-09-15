import type { EditScope } from '../api/types'
import { ChoiceDialog, type Choice } from '../components/ChoiceDialog'
import { EventDetails } from '../components/EventDetails'
import { EventForm } from '../components/EventForm'
import type { CalendarApp, Question } from './useCalendarApp'

/**
 * The event card, the form and the questions between them.
 *
 * The same in every layout unless a layout brings its own: they are about the event, not about
 * how the calendar is arranged.
 */
export function Dialogs({ app }: { app: CalendarApp }) {
  const { selected, editing, question } = app
  const editedEvent = editing?.event

  return (
    <>
      {selected && (
        <EventDetails
          event={selected}
          busy={app.writing}
          onEdit={() => app.startEdit(selected.source)}
          onDelete={() => app.requestDelete(selected.source)}
          onClose={app.closeEvent}
        />
      )}

      {editing && (
        <EventForm
          calendars={app.calendars}
          event={editing.event}
          defaultDate={editing.defaultDate}
          busy={app.writing}
          error={app.formError}
          onSubmit={app.submitForm}
          onDelete={editedEvent ? () => app.requestDelete(editedEvent) : null}
          onClose={app.closeForm}
        />
      )}

      {question && (
        <QuestionDialog
          question={question}
          busy={app.writing}
          onAnswer={app.answer}
          onCancel={app.dismissQuestion}
        />
      )}
    </>
  )
}

interface QuestionDialogProps {
  question: Question
  busy: boolean
  onAnswer: (scope: EditScope) => void
  onCancel: () => void
}

function QuestionDialog({ question, busy, onAnswer, onCancel }: QuestionDialogProps) {
  const { event } = question

  if (question.kind === 'delete' && !event.recurring) {
    return (
      <ChoiceDialog<EditScope>
        title={`Удалить «${event.title}»?`}
        tone="danger"
        choices={[{ value: 'all', label: 'Удалить' }]}
        busy={busy}
        onChoose={onAnswer}
        onCancel={onCancel}
      />
    )
  }

  const saving = question.kind === 'save'
  // A new rule makes new slots: it can apply to the series from here on, or to all of it, but
  // one day on its own cannot repeat differently.
  const ruleChanged = saving && question.body.repeat !== undefined

  const choices: Choice<EditScope>[] = [
    {
      value: 'this',
      label: 'Только это событие',
      disabled: ruleChanged,
      hint: ruleChanged ? 'Повтор меняется для всей серии или начиная с этого дня.' : undefined,
    },
    { value: 'following', label: 'Это и все следующие' },
    { value: 'all', label: 'Все события серии' },
  ]

  return (
    <ChoiceDialog
      title={saving ? 'Изменить повторяющееся событие' : 'Удалить повторяющееся событие'}
      message={saving ? 'К каким событиям применить изменения?' : 'Какие события удалить?'}
      tone={saving ? 'primary' : 'danger'}
      choices={choices}
      busy={busy}
      onChoose={onAnswer}
      onCancel={onCancel}
    />
  )
}
