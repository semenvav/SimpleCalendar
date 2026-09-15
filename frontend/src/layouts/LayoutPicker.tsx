import { useState } from 'react'
import { useLayoutChoice } from './choice'

/**
 * Switches this device between layouts, so a new version can be tried on the real wall and
 * swapped back if it turns out worse.
 *
 * Shows nothing while there is only one layout. [className] lets each layout dress the button
 * like its own toolbar buttons.
 */
export function LayoutPicker({ className = 'layout-picker-button' }: { className?: string }) {
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
        title="Раскладка"
      >
        {current.name}
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
