package dev.simplecalendar.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Path
import java.sql.Connection

/**
 * SQLite-backed storage.
 *
 * Schema changes are applied as an ordered list of migrations tracked by SQLite's built-in
 * `user_version` pragma — no migration library, no version table of our own.
 */
class Database(dataDir: Path) : AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)
    private val dataSource: HikariDataSource

    init {
        val dbFile = dataDir.resolve("simplecalendar.db")

        val sqliteConfig = SQLiteConfig().apply {
            // WAL lets the sync loop write while HTTP handlers read.
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
            enforceForeignKeys(true)
            setBusyTimeout(5_000)
        }
        val sqlite = SQLiteDataSource(sqliteConfig).apply { url = "jdbc:sqlite:$dbFile" }

        val hikariConfig = HikariConfig()
        hikariConfig.dataSource = sqlite
        hikariConfig.poolName = "sqlite"
        hikariConfig.maximumPoolSize = 4

        dataSource = HikariDataSource(hikariConfig)
        log.info("Database at {}", dbFile)
        migrate()
    }

    /** Runs [block] on a pooled connection in autocommit mode. Use for reads. */
    fun <T> read(block: (Connection) -> T): T = dataSource.connection.use(block)

    /** Runs [block] in a transaction, committing on success and rolling back on any throw. */
    fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { conn ->
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            result
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    override fun close() = dataSource.close()

    private fun migrate() {
        dataSource.connection.use { conn ->
            val current = conn.createStatement().use { st ->
                st.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) }
            }
            if (current >= MIGRATIONS.size) {
                log.info("Schema is up to date (version {})", current)
                return
            }
            for (version in current until MIGRATIONS.size) {
                log.info("Applying migration {} -> {}", version, version + 1)
                conn.autoCommit = false
                try {
                    for (sql in MIGRATIONS[version]) {
                        conn.createStatement().use { it.execute(sql) }
                    }
                    // Pragmas cannot be parameterised; the value is a loop index, not user input.
                    conn.createStatement().use { it.execute("PRAGMA user_version = ${version + 1}") }
                    conn.commit()
                } catch (e: Throwable) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
        }
    }

    private companion object {
        val MIGRATIONS: List<List<String>> = listOf(
            // v1 — initial schema
            listOf(
                """
                CREATE TABLE calendars (
                    id           TEXT PRIMARY KEY,
                    url          TEXT NOT NULL UNIQUE,
                    server_name  TEXT NOT NULL,
                    server_color TEXT,
                    custom_name  TEXT,
                    custom_color TEXT,
                    read_only    INTEGER NOT NULL DEFAULT 0,
                    visible      INTEGER NOT NULL DEFAULT 1,
                    sort_order   INTEGER NOT NULL DEFAULT 0,
                    sync_token   TEXT,
                    ctag         TEXT,
                    last_sync_at INTEGER
                )
                """.trimIndent(),
                """
                CREATE TABLE events (
                    calendar_id     TEXT    NOT NULL REFERENCES calendars(id) ON DELETE CASCADE,
                    href            TEXT    NOT NULL,
                    etag            TEXT,
                    uid             TEXT    NOT NULL,
                    ics             TEXT    NOT NULL,
                    first_start_utc INTEGER NOT NULL,
                    last_end_utc    INTEGER,
                    recurring       INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (calendar_id, href)
                )
                """.trimIndent(),
                "CREATE INDEX idx_events_window ON events (calendar_id, first_start_utc)",
                "CREATE INDEX idx_events_uid ON events (calendar_id, uid)",
                """
                CREATE TABLE settings (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent(),
            ),
        )
    }
}
