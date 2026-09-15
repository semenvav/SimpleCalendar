/**
 * Remembers small bits of UI state across page reloads.
 *
 * Browser storage can be missing or throw — private windows, blocked site data, a full quota —
 * and remembering is only a convenience, so every access quietly degrades to "nothing remembered"
 * instead of breaking the page.
 */
type Kind = 'local' | 'session'

function store(kind: Kind): Storage | null {
  try {
    return kind === 'local' ? window.localStorage : window.sessionStorage
  } catch {
    return null
  }
}

export function recall(kind: Kind, key: string): string | null {
  try {
    return store(kind)?.getItem(key) ?? null
  } catch {
    return null
  }
}

export function remember(kind: Kind, key: string, value: string): void {
  try {
    store(kind)?.setItem(key, value)
  } catch {
    // Forgetting is acceptable; failing to render the calendar is not.
  }
}
