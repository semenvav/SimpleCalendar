import { createContext, useContext } from 'react'
import type { LayoutDefinition } from './types'

export interface LayoutChoice {
  current: LayoutDefinition
  all: readonly LayoutDefinition[]
  /** Switches this device to another layout; the device remembers it. */
  choose: (id: string) => void
}

export const LayoutChoiceContext = createContext<LayoutChoice | null>(null)

export function useLayoutChoice(): LayoutChoice {
  const choice = useContext(LayoutChoiceContext)
  if (!choice) throw new Error('useLayoutChoice is only available inside a layout')
  return choice
}
