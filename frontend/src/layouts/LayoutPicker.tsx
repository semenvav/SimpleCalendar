import { useState } from 'react'
import { useLayoutChoice } from './choice'

/**
 * A pane split in two — «arrangement», at the size a fingertip aims for.
 *
 * Drawn rather than typed: the toolbar's other glyphs are common enough to count on, but a
 * layout symbol is not, and a tofu box on the kitchen wall is worse than a word.
 */
function LayoutIcon() {
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
      <rect x="2.5" y="3.5" width="15" height="13" rx="2" />
      <path d="M10 3.5v13" />
    </svg>
  )
}

interface LayoutPickerProps {
  /** Lets each layout dress the button like its own toolbar buttons. */
  className?: string
  /**
   * Shows an icon instead of the current layout's name.
   *
   * On a wall the name is a poor trade: it is as wide as the two buttons next to it and says
   * something the screen already shows. The menu still marks which layout is current.
   */
  compact?: boolean
}

/**
 * Switches this device between layouts, so a new version can be tried on the real wall and
 * swapped back if it turns out worse.
 *
 * Shows nothing while there is only one layout.
 */
export function LayoutPicker({ className = 'layout-picker-button', compact = false }: LayoutPickerProps) {
  const { current, all, choose } = useLayoutChoice()
  const [open, setOpen] = useState(false)

  if (all.length < 2) return null

  return (
    <div className="layout-picker">
      <button
        type="button"
        className={className}
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={compact ? `Раскладка: ${current.name}` : undefined}
        title="Раскладка"
      >
        {compact ? <LayoutIcon /> : current.name}
      </button>

      {open && (
        <>
          <div className="layout-picker-backdrop" onClick={() => setOpen(false)} />
          <ul className="layout-picker-menu" role="menu">
            {all.map((layout) => (
              <li key={layout.id}>
                <button
                  type="button"
                  role="menuitemradio"
                  aria-checked={layout.id === current.id}
                  className={`layout-picker-item${layout.id === current.id ? ' current' : ''}`}
                  onClick={() => {
                    setOpen(false)
                    choose(layout.id)
                  }}
                >
                  <span className="layout-picker-name">{layout.name}</span>
                  <span className="layout-picker-description">{layout.description}</span>
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}
