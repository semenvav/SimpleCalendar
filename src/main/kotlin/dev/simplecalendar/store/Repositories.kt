package dev.simplecalendar.store

import dev.simplecalendar.model.CalendarCollection
import dev.simplecalendar.model.StoredEvent
import java.sql.ResultSet
import java.time.Instant

/** What a discovery pass learned about a collection; local overrides are none of its business. */
data class DiscoveredCalendar(
    val id: String,
    val url: String,
    val name: String,
    val color: String?,
    val readOnly: Boolean,
)

class CalendarRepository(private val db: Database) {

    fun all(): List<CalendarCollection> = db.read { conn ->
        conn.prepareStatement("$SELECT_ALL ORDER BY sort_order, server_name").use { st ->
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toCalendar() else null }.toList() }
        }
    }

    fun byId(id: String): CalendarCollection? = db.read { conn ->
        conn.prepareStatement("$SELECT_ALL WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.toCalendar() else null }
        }
    }

    /**
     * Writes what discovery found while preserving local overrides and sync state.
     *
     * The `ON CONFLICT` clause lists only server-owned columns on purpose: a sync must never
     * reset a colour the user picked or a sync token we are in the middle of using.
     */
    fun upsertDiscovered(calendars: List<DiscoveredCalendar>) = db.transaction { conn ->
        conn.prepareStatement(
            """
            INSERT INTO calendars (id, url, server_name, server_color, read_only, sort_order)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                url          = excluded.url,
                server_name  = excluded.server_name,
                server_color = excluded.server_color,
                read_only    = excluded.read_only
            """.trimIndent(),
        ).use { st ->
            calendars.forEachIndexed { index, cal ->
                st.setString(1, cal.id)
                st.setString(2, cal.url)
                st.setString(3, cal.name)
                st.setString(4, cal.color)
                st.setInt(5, if (cal.readOnly) 1 else 0)
                st.setInt(6, index)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    /** Drops collections that vanished from the server, cascading to their cached events. */
    fun deleteMissing(keepIds: Collection<String>): Int = db.transaction { conn ->
        if (keepIds.isEmpty()) {
            return@transaction conn.createStatement().use { it.executeUpdate("DELETE FROM calendars") }
        }
        val placeholders = keepIds.joinToString(",") { "?" }
        conn.prepareStatement("DELETE FROM calendars WHERE id NOT IN ($placeholders)").use { st ->
            keepIds.forEachIndexed { i, id -> st.setString(i + 1, id) }
            st.executeUpdate()
        }
    }

    fun updateSyncState(id: String, syncToken: String?, ctag: String?, at: Instant) = db.transaction { conn ->
        conn.prepareStatement(
            "UPDATE calendars SET sync_token = ?, ctag = ?, last_sync_at = ? WHERE id = ?",
        ).use { st ->
            st.setString(1, syncToken)
            st.setString(2, ctag)
            st.setLong(3, at.toEpochMilli())
            st.setString(4, id)
            st.executeUpdate()
        }
    }

    /** Invalidates the sync token so the next pass falls back to a full resynchronisation. */
    fun clearSyncToken(id: String) = db.transaction { conn ->
        conn.prepareStatement("UPDATE calendars SET sync_token = NULL, ctag = NULL WHERE id = ?").use { st ->
            st.setString(1, id)
            st.executeUpdate()
        }
    }

    fun updateCustomisation(
        id: String,
        customName: String?,
        customColor: String?,
        visible: Boolean,
        sortOrder: Int,
    ) = db.transaction { conn ->
        conn.prepareStatement(
            """
            UPDATE calendars
               SET custom_name = ?, custom_color = ?, visible = ?, sort_order = ?
             WHERE id = ?
            """.trimIndent(),
        ).use { st ->
            st.setString(1, customName)
            st.setString(2, customColor)
            st.setInt(3, if (visible) 1 else 0)
            st.setInt(4, sortOrder)
            st.setString(5, id)
            st.executeUpdate()
        }
    }

    private fun ResultSet.toCalendar() = CalendarCollection(
        id = getString("id"),
        url = getString("url"),
        serverName = getString("server_name"),
        serverColor = getString("server_color"),
        customName = getString("custom_name"),
        customColor = getString("custom_color"),
        readOnly = getInt("read_only") != 0,
        visible = getInt("visible") != 0,
        sortOrder = getInt("sort_order"),
        syncToken = getString("sync_token"),
        ctag = getString("ctag"),
        lastSyncAt = getLong("last_sync_at").takeIf { !wasNull() }?.let(Instant::ofEpochMilli),
    )

    private companion object {
        const val SELECT_ALL = """
            SELECT id, url, server_name, server_color, custom_name, custom_color,
                   read_only, visible, sort_order, sync_token, ctag, last_sync_at
              FROM calendars
        """
    }
}

class EventRepository(private val db: Database) {

    /**
     * Candidate events that could possibly appear in `[fromUtc, toUtc)`.
     *
     * This is a pre-filter, not an answer: recurring events are returned whenever their overall
     * span touches the window, and the caller still has to expand them to find real occurrences.
     * Open-ended recurrences (`last_end_utc IS NULL`) always qualify.
     */
    fun candidatesInRange(calendarIds: Collection<String>, fromUtc: Long, toUtc: Long): List<StoredEvent> {
        if (calendarIds.isEmpty()) return emptyList()
        val placeholders = calendarIds.joinToString(",") { "?" }
        return db.read { conn ->
            conn.prepareStatement(
                """
                $SELECT_ALL
                 WHERE calendar_id IN ($placeholders)
                   AND first_start_utc < ?
                   AND (last_end_utc IS NULL OR last_end_utc > ?)
                """.trimIndent(),
            ).use { st ->
                var i = 1
                calendarIds.forEach { st.setString(i++, it) }
                st.setLong(i++, toUtc)
                st.setLong(i, fromUtc)
                st.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs.toEvent() else null }.toList()
                }
            }
        }
    }

    fun byHref(calendarId: String, href: String): StoredEvent? = db.read { conn ->
        conn.prepareStatement("$SELECT_ALL WHERE calendar_id = ? AND href = ?").use { st ->
            st.setString(1, calendarId)
            st.setString(2, href)
            st.executeQuery().use { rs -> if (rs.next()) rs.toEvent() else null }
        }
    }

    fun byUid(calendarId: String, uid: String): StoredEvent? = db.read { conn ->
        conn.prepareStatement("$SELECT_ALL WHERE calendar_id = ? AND uid = ?").use { st ->
            st.setString(1, calendarId)
            st.setString(2, uid)
            st.executeQuery().use { rs -> if (rs.next()) rs.toEvent() else null }
        }
    }

    /** href -> etag for every cached resource of a calendar; drives ctag-based resynchronisation. */
    fun etagsOf(calendarId: String): Map<String, String?> = db.read { conn ->
        conn.prepareStatement("SELECT href, etag FROM events WHERE calendar_id = ?").use { st ->
            st.setString(1, calendarId)
            st.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getString("href"), rs.getString("etag")) }
            }
        }
    }

    fun upsertAll(events: Collection<StoredEvent>) {
        if (events.isEmpty()) return
        db.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO events (calendar_id, href, etag, uid, ics, first_start_utc, last_end_utc, recurring)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(calendar_id, href) DO UPDATE SET
                    etag            = excluded.etag,
                    uid             = excluded.uid,
                    ics             = excluded.ics,
                    first_start_utc = excluded.first_start_utc,
                    last_end_utc    = excluded.last_end_utc,
                    recurring       = excluded.recurring
                """.trimIndent(),
            ).use { st ->
                for (e in events) {
                    st.setString(1, e.calendarId)
                    st.setString(2, e.href)
                    st.setString(3, e.etag)
                    st.setString(4, e.uid)
                    st.setString(5, e.ics)
                    st.setLong(6, e.firstStartUtc)
                    if (e.lastEndUtc == null) st.setNull(7, java.sql.Types.INTEGER) else st.setLong(7, e.lastEndUtc)
                    st.setInt(8, if (e.recurring) 1 else 0)
                    st.addBatch()
                }
                st.executeBatch()
            }
        }
    }

    fun deleteHrefs(calendarId: String, hrefs: Collection<String>) {
        if (hrefs.isEmpty()) return
        db.transaction { conn ->
            conn.prepareStatement("DELETE FROM events WHERE calendar_id = ? AND href = ?").use { st ->
                for (href in hrefs) {
                    st.setString(1, calendarId)
                    st.setString(2, href)
                    st.addBatch()
                }
                st.executeBatch()
            }
        }
    }

    fun deleteAllOf(calendarId: String) = db.transaction { conn ->
        conn.prepareStatement("DELETE FROM events WHERE calendar_id = ?").use { st ->
            st.setString(1, calendarId)
            st.executeUpdate()
        }
    }

    fun count(): Int = db.read { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM events").use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private fun ResultSet.toEvent() = StoredEvent(
        calendarId = getString("calendar_id"),
        href = getString("href"),
        etag = getString("etag"),
        uid = getString("uid"),
        ics = getString("ics"),
        firstStartUtc = getLong("first_start_utc"),
        lastEndUtc = getLong("last_end_utc").takeIf { !wasNull() },
        recurring = getInt("recurring") != 0,
    )

    private companion object {
        const val SELECT_ALL = """
            SELECT calendar_id, href, etag, uid, ics, first_start_utc, last_end_utc, recurring
              FROM events
        """
    }
}

/** Key/value store for UI settings and screen layouts, kept as JSON blobs. */
class SettingsRepository(private val db: Database) {

    fun get(key: String): String? = db.read { conn ->
        conn.prepareStatement("SELECT value FROM settings WHERE key = ?").use { st ->
            st.setString(1, key)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun put(key: String, value: String) = db.transaction { conn ->
        conn.prepareStatement(
            "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        ).use { st ->
            st.setString(1, key)
            st.setString(2, value)
            st.executeUpdate()
        }
    }
}
