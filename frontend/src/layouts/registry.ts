import { ClassicLayout } from './classic/ClassicLayout'
import { IntegrationLayout } from './integration/IntegrationLayout'
import type { LayoutDefinition } from './types'

/**
 * Every layout the app can show, oldest first.
 *
 * A layout that people have used is frozen. To change the look, copy its folder under a new id,
 * change the copy and add it here: the old one stays selectable, so the family can live with both
 * on the real wall and say which is better. A layout goes away only on purpose, once nobody wants
 * it back. How to make one is in docs/ARCHITECTURE.md, «Раскладки».
 */
export const LAYOUTS: readonly LayoutDefinition[] = [
  {
    id: 'classic',
    name: 'Классика',
    description: 'Первая версия: навигация и виды сверху, календари строкой под ними.',
    Component: ClassicLayout,
  },
  {
    id: 'integration',
    name: 'С датчиками',
    description: 'Два месяца, датчики и погода в панели, отметки «отменено» и «перенесено».',
    Component: IntegrationLayout,
  },
]

/** What a device shows until somebody picks a layout on it. */
export const DEFAULT_LAYOUT_ID = 'classic'

export const findLayout = (id: string | null): LayoutDefinition | undefined =>
  LAYOUTS.find((layout) => layout.id === id)
