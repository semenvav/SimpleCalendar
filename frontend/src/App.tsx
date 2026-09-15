import { useEffect, useMemo, useState } from 'react'
import { recall, remember } from './lib/persist'
import { LayoutChoiceContext } from './layouts/choice'
import { DEFAULT_LAYOUT_ID, LAYOUTS, findLayout } from './layouts/registry'

/** Which layout this device shows; see `layouts/registry.ts`. */
const LAYOUT_KEY = 'simple-calendar.layout'

/**
 * The layout this device starts with.
 *
 * `?layout=<id>` switches the device to that layout and is then taken out of the address, so the
 * choice lives in one place — the device — and a reload keeps whatever was picked since.
 */
function initialLayoutId(): string {
  const url = new URL(window.location.href)
  const requested = url.searchParams.get('layout')
  if (requested !== null) {
    url.searchParams.delete('layout')
    window.history.replaceState(window.history.state, '', url)
    if (findLayout(requested)) {
      remember('local', LAYOUT_KEY, requested)
      return requested
    }
  }
  const stored = recall('local', LAYOUT_KEY)
  return stored !== null && findLayout(stored) ? stored : DEFAULT_LAYOUT_ID
}

export default function App() {
  const [layoutId, setLayoutId] = useState(initialLayoutId)
  useEffect(() => remember('local', LAYOUT_KEY, layoutId), [layoutId])

  const layout = findLayout(layoutId) ?? LAYOUTS[0]
  const choice = useMemo(() => ({ current: layout, all: LAYOUTS, choose: setLayoutId }), [layout])

  return (
    <LayoutChoiceContext.Provider value={choice}>
      {/* A fresh mount per layout, so no state carries over from one arrangement into the next. */}
      <layout.Component key={layout.id} />
    </LayoutChoiceContext.Provider>
  )
}
