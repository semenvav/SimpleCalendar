import type { ComponentType } from 'react'

/**
 * One way of arranging the calendar on screen.
 *
 * A layout is a top-level component: it takes its state and actions from `useCalendarApp` and
 * decides everything the family sees — toolbar, views, legend, styles. Everything that talks to
 * the server is shared, so two layouts can never disagree about the data, only about the look.
 */
export interface LayoutDefinition {
  /** Stable: it lives in URLs and in every device's storage. Never reuse one for something else. */
  id: string
  /** What the picker calls it. */
  name: string
  /** One line on what sets it apart, shown in the picker under the name. */
  description: string
  Component: ComponentType
}
