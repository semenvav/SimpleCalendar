package dev.simplecalendar.sync

import dev.simplecalendar.caldav.CalDavClient
import dev.simplecalendar.caldav.CalDavResource
import dev.simplecalendar.caldav.SyncOutcome
import dev.simplecalendar.ical.EventExpander
import dev.simplecalendar.ical.IcsParser
import dev.simplecalendar.model.CalendarCollection
import dev.simplecalendar.model.StoredEvent
import dev.simplecalendar.store.CalendarRepository
import dev.simplecalendar.store.EventRepository
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

data class SyncStatus(
    val configured: Boolean,
    val lastSuccessAt: Instant?,
    val lastError: String?,
    val calendarCount: Int,
    val eventCount: Int,
    /** Increments whenever a sync actually changed cached data; lets clients cheaply detect staleness. */
    val revision: Long,
)

/**
 * Keeps the local cache in step with the CalDAV server.
 *
 * Baikal is the source of truth throughout: we never write a local-only change and never treat
 * the cache as authoritative. The loop prefers RFC 6578 `sync-collection`, which returns only
 * what changed, and degrades through two fallbacks — a full sync-collection pass when the server
 * rejects our token, then ETag comparison for servers that do not implement it at all.
 */
class SyncService(
    private val client: CalDavClient,
    private val calendars: CalendarRepository,
    private val events: EventRepository,
    private val expander: EventExpander,
    private val intervalSeconds: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("caldav-sync"))
    private val mutex = Mutex()
    private val revision = AtomicLong(0)

    @Volatile private var lastSuccessAt: Instant? = null
    @Volatile private var lastError: String? = null

    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    syncAll()
                } catch (e: Exception) {
                    lastError = e.message ?: e::class.simpleName
                    log.warn("Sync failed: {}", lastError)
                }
                delay(intervalSeconds * 1_000)
            }
        }
        log.info("Sync loop started, every {}s", intervalSeconds)
    }

    fun stop() {
        // The HTTP client is shared with the write service and closed by the owner.
        scope.cancel()
    }

    /** Records that cached data changed outside the sync loop — after a write, for instance. */
    fun markChanged() {
        revision.incrementAndGet()
    }

    fun status(): SyncStatus = SyncStatus(
        configured = true,
        lastSuccessAt = lastSuccessAt,
        lastError = lastError,
        calendarCount = calendars.all().size,
        eventCount = events.count(),
        revision = revision.get(),
    )

    /** Runs one full pass. Serialised, so a manual trigger can never overlap the background loop. */
    suspend fun syncAll() = mutex.withLock {
        val discovered = client.discoverCalendars()
        if (discovered.isEmpty()) {
            // Never let an empty answer wipe the cache — a transient server hiccup looks like this.
            log.warn("Discovery returned no calendars; keeping the existing cache")
        } else {
            calendars.upsertDiscovered(discovered)
            val removed = calendars.deleteMissing(discovered.map { it.id })
            if (removed > 0) log.info("Removed {} calendar(s) no longer on the server", removed)
        }

        for (calendar in calendars.all()) {
            try {
                syncCalendar(calendar)
            } catch (e: Exception) {
                // One broken collection must not stop the others.
                log.warn("Sync of calendar '{}' failed: {}", calendar.name, e.message)
                lastError = "${calendar.name}: ${e.message}"
            }
        }
        lastSuccessAt = Instant.now()
        lastError = null
    }

    private suspend fun syncCalendar(calendar: CalendarCollection) {
        when (val outcome = client.syncCollection(calendar.url, calendar.syncToken)) {
            is SyncOutcome.Delta -> applyDelta(calendar, outcome, full = calendar.syncToken == null)

            SyncOutcome.TokenRejected -> {
                calendars.clearSyncToken(calendar.id)
                when (val retry = client.syncCollection(calendar.url, null)) {
                    is SyncOutcome.Delta -> applyDelta(calendar, retry, full = true)
                    else -> syncByEtags(calendar)
                }
            }

            SyncOutcome.Unsupported -> syncByEtags(calendar)
        }
    }

    /**
     * Applies a sync-collection result.
     *
     * [full] marks a pass that started without a token, meaning the server listed the entire
     * collection and reported no deletions — so anything cached but absent from the listing was
     * deleted while we were not looking and has to go.
     */
    private suspend fun applyDelta(calendar: CalendarCollection, delta: SyncOutcome.Delta, full: Boolean) {
        val cached = events.etagsOf(calendar.id)

        // The server already told us the new ETags, so re-downloading unchanged bodies is waste.
        val toFetch = delta.changed
            .filter { it.etag == null || cached[it.href] != it.etag }
            .map { it.href }

        val toDelete = buildSet {
            addAll(delta.removed)
            if (full) addAll(cached.keys - delta.changed.mapTo(mutableSetOf()) { it.href })
        }

        val changedCount = fetchAndStore(calendar, toFetch)
        if (toDelete.isNotEmpty()) events.deleteHrefs(calendar.id, toDelete)

        // The ctag is only consulted by the ETag fallback, which fetches it itself. Asking for it
        // here would add a PROPFIND per calendar per tick for a value nothing on this path reads.
        calendars.updateSyncState(calendar.id, delta.syncToken, calendar.ctag, Instant.now())

        if (changedCount > 0 || toDelete.isNotEmpty()) {
            revision.incrementAndGet()
            log.info(
                "Calendar '{}': {} updated, {} removed",
                calendar.name, changedCount, toDelete.size,
            )
        }
    }

    /** Fallback for servers without RFC 6578: compare the collection tag, then compare ETags. */
    private suspend fun syncByEtags(calendar: CalendarCollection) {
        val ctag = client.getCtag(calendar.url)
        if (ctag != null && ctag == calendar.ctag) {
            calendars.updateSyncState(calendar.id, calendar.syncToken, ctag, Instant.now())
            return
        }

        val remote = client.listEventEtags(calendar.url)
        val cached = events.etagsOf(calendar.id)

        val toFetch = remote.filter { (href, etag) -> etag == null || cached[href] != etag }.keys.toList()
        val toDelete = cached.keys - remote.keys

        val changedCount = fetchAndStore(calendar, toFetch)
        if (toDelete.isNotEmpty()) events.deleteHrefs(calendar.id, toDelete)

        calendars.updateSyncState(calendar.id, calendar.syncToken, ctag, Instant.now())

        if (changedCount > 0 || toDelete.isNotEmpty()) {
            revision.incrementAndGet()
            log.info(
                "Calendar '{}' (etag sync): {} updated, {} removed",
                calendar.name, changedCount, toDelete.size,
            )
        }
    }

    private suspend fun fetchAndStore(calendar: CalendarCollection, hrefs: List<String>): Int {
        if (hrefs.isEmpty()) return 0
        val resources = client.multiget(calendar.url, hrefs)

        // One unreadable resource is skipped, not fatal: a single bad event must never blank
        // the wall display.
        val stored = resources.mapNotNull { resource ->
            val ics = resource.ics?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            expander.toStoredEvent(calendar.id, resource.href, resource.etag, ics)
                ?: null.also { log.warn("Skipping resource with no usable event: {}", resource.href) }
        }

        events.upsertAll(stored)
        return stored.size
    }
}
