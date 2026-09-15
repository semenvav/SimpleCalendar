import { useRef, type MouseEvent, type PointerEvent } from 'react'

/**
 * Props for a dialog's backdrop that close the dialog on a tap outside it — a tap that both
 * started and ended on the backdrop.
 *
 * A click that merely *lands* there must not close anything: the tail of the tap that opened the
 * dialog (see `afterClick` in CalendarView), or a finger sliding off a field while selecting its
 * text. Either would throw away what somebody had typed.
 */
export function useBackdropDismiss(onDismiss: () => void) {
  const pressed = useRef(false)

  return {
    onPointerDown: (e: PointerEvent<HTMLElement>) => {
      pressed.current = e.target === e.currentTarget
    },
    onClick: (e: MouseEvent<HTMLElement>) => {
      const wasPressed = pressed.current
      pressed.current = false
      if (wasPressed && e.target === e.currentTarget) onDismiss()
    },
  }
}
