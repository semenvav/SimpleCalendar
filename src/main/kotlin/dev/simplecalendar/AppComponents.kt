package dev.simplecalendar

import dev.simplecalendar.caldav.CalDavClient
import dev.simplecalendar.config.AppConfig
import dev.simplecalendar.ical.EventExpander
import dev.simplecalendar.service.EventQueryService
import dev.simplecalendar.service.EventWriteService
import dev.simplecalendar.store.CalendarRepository
import dev.simplecalendar.store.Database
import dev.simplecalendar.store.EventRepository
import dev.simplecalendar.store.SettingsRepository
import dev.simplecalendar.sync.SyncService

/**
 * Everything the application is made of, wired by hand.
 *
 * The object graph is a dozen nodes deep at most, so a DI framework would add a layer of
 * indirection without removing any work. [sync] and [write] are null when no CalDAV source is
 * configured, which is what lets a fresh `docker compose up` start and explain itself instead of
 * crashing.
 */
class AppComponents(val config: AppConfig) : AutoCloseable {

    private val database = Database(config.dataDir)

    val calendars = CalendarRepository(database)
    val events = EventRepository(database)
    val settings = SettingsRepository(database)

    private val expander = EventExpander(config.timeZone)

    /** Shared by the sync loop and the write path, so the container runs one HTTP stack. */
    private val caldav: CalDavClient? = config.caldav?.let(::CalDavClient)

    val query = EventQueryService(calendars, events, expander, config.timeZone)

    val sync: SyncService? = caldav?.let { client ->
        SyncService(
            client = client,
            calendars = calendars,
            events = events,
            expander = expander,
            intervalSeconds = config.syncIntervalSeconds,
        )
    }

    val write: EventWriteService? = caldav?.let { client ->
        EventWriteService(
            calendars = calendars,
            events = events,
            client = client,
            expander = expander,
            zone = config.timeZone,
            // Keeps the revision counter honest for writes that bypass the sync loop.
            onChange = { sync?.markChanged() },
        )
    }

    fun start() {
        sync?.start()
    }

    override fun close() {
        sync?.stop()
        caldav?.close()
        database.close()
    }
}
