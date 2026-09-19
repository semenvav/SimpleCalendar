import { useQuery } from '@tanstack/react-query'
import { fetchIntegration } from '../api/client'

/**
 * What an integration last managed to fetch, or `null` while there is nothing to show.
 *
 * The server does the polling and keeps the last good answer, so [everySeconds] is only about how
 * quickly the wall notices a new one — not about how often the service itself is asked. A layout
 * that does not call this costs nothing: an integration nobody reads is still only polled once
 * on the server, and one that is not configured answers 404 and shows nothing at all.
 */
export function useIntegration<T>(id: string, everySeconds: number): T | null {
  const query = useQuery({
    queryKey: ['integration', id],
    queryFn: () => fetchIntegration<T>(id),
    refetchInterval: everySeconds * 1000,
    staleTime: everySeconds * 1000,
    // A service that is down must not blank the corner: keep showing what we had.
    retry: false,
  })

  return query.data ?? null
}
