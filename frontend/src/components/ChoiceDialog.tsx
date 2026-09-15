export interface Choice<T extends string> {
  value: T
  label: string
  disabled?: boolean
  /** Shown under the choice — typically why it is unavailable. */
  hint?: string
}

interface ChoiceDialogProps<T extends string> {
  title: string
  message?: string
  choices: Choice<T>[]
  /** A delete asks in red, an edit in the usual blue. */
  tone: 'primary' | 'danger'
  busy: boolean
  onChoose: (value: T) => void
  onCancel: () => void
}

/** A short question with a few big answers — sized for a finger on the wall, not a mouse. */
export function ChoiceDialog<T extends string>({
  title,
  message,
  choices,
  tone,
  busy,
  onChoose,
  onCancel,
}: ChoiceDialogProps<T>) {
  return (
    <div className="details-backdrop choice-backdrop" onClick={busy ? undefined : onCancel}>
      <div
        className="details choice-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="details-header form-header">
          <h2>{title}</h2>
        </div>

        {message && <p className="choice-message">{message}</p>}

        <div className="choice-list">
          {choices.map((choice) => (
            <div key={choice.value}>
              <button
                type="button"
                className={`button ${tone} choice-button`}
                disabled={busy || choice.disabled}
                onClick={() => onChoose(choice.value)}
              >
                {choice.label}
              </button>
              {choice.hint && <p className="choice-hint">{choice.hint}</p>}
            </div>
          ))}
        </div>

        <div className="form-actions">
          <span className="form-actions-spacer" />
          <button type="button" className="button" onClick={onCancel} disabled={busy}>
            Отмена
          </button>
        </div>
      </div>
    </div>
  )
}
