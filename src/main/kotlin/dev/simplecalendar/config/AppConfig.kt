package dev.simplecalendar.config

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.io.path.Path

/**
 * Connection details for the upstream CalDAV server (Baikal).
 *
 * [baseUrl] may point either at the DAV root (`http://baikal/dav.php/`) or directly at a
 * principal or calendar-home collection — discovery walks down from whatever it is given.
 */
data class CalDavConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
)

/**
 * All runtime configuration, read from environment variables so it can be driven from
 * docker-compose. Every value has a usable default except the CalDAV credentials; when those
 * are absent the server still boots and serves an "unconfigured" state, which keeps the very
 * first `docker compose up` from failing in a confusing way.
 */
data class AppConfig(
    val host: String,
    val port: Int,
    val dataDir: Path,
    val staticDir: Path?,
    /** Household time zone. Defines what "today" means and how floating times are rendered. */
    val timeZone: ZoneId,
    val syncIntervalSeconds: Long,
    val caldav: CalDavConfig?,
    /** Allow cross-origin calls from the Vite dev server. Never enable in production. */
    val devCors: Boolean,
) {
    companion object {
        fun fromEnv(env: (String) -> String? = { System.getenv(it) }): AppConfig {
            // Real environment variables win; a .env file in the working directory fills the gaps,
            // so `./gradlew run` picks up the same settings docker compose uses.
            val dotEnv = loadDotEnv(Path(".env"))
            fun str(name: String): String? = (env(name) ?: dotEnv[name])?.trim()?.takeIf { it.isNotEmpty() }

            val caldav = str("SC_CALDAV_URL")?.let { url ->
                CalDavConfig(
                    baseUrl = url,
                    username = str("SC_CALDAV_USERNAME")
                        ?: error("SC_CALDAV_URL is set but SC_CALDAV_USERNAME is missing"),
                    password = str("SC_CALDAV_PASSWORD")
                        ?: error("SC_CALDAV_URL is set but SC_CALDAV_PASSWORD is missing"),
                )
            }

            val dataDir = Path(str("SC_DATA_DIR") ?: "data").toAbsolutePath().normalize()
            Files.createDirectories(dataDir)

            // Absent or non-existent static dir means "running in dev, Vite serves the UI".
            val staticDir = str("SC_STATIC_DIR")
                ?.let { Path(it).toAbsolutePath().normalize() }
                ?.takeIf { Files.isDirectory(it) }

            return AppConfig(
                host = str("SC_HOST") ?: "0.0.0.0",
                port = str("SC_PORT")?.toIntOrNull() ?: 8080,
                dataDir = dataDir,
                staticDir = staticDir,
                timeZone = str("SC_TIMEZONE")?.let(ZoneId::of) ?: ZoneId.systemDefault(),
                syncIntervalSeconds = str("SC_SYNC_INTERVAL_SECONDS")?.toLongOrNull() ?: 60L,
                caldav = caldav,
                devCors = str("SC_DEV_CORS")?.toBooleanStrictOrNull() ?: false,
            )
        }

        /** Minimal `KEY=value` reader: comments, blank lines and surrounding quotes, nothing more. */
        private fun loadDotEnv(path: Path): Map<String, String> {
            if (!Files.isRegularFile(path)) return emptyMap()
            return Files.readAllLines(path, Charsets.UTF_8)
                .asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) return@mapNotNull null
                    val key = line.take(separator).trim()
                    val value = line.substring(separator + 1).trim()
                        .removeSurrounding("\"")
                        .removeSurrounding("'")
                    key to value
                }
                .toMap()
        }
    }
}
