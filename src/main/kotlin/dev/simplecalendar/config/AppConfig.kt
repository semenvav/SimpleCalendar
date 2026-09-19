package dev.simplecalendar.config

import dev.simplecalendar.net.ProxySettings
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
    /**
     * Which build this is, as `/api/health` reports it: the numbered tag of the image, `0.5.7`.
     * Baked in by the Dockerfile; outside a container there is no published build, hence `dev`.
     */
    val version: String,
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
    /** How outgoing requests reach the outside: `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY`. */
    val proxy: ProxySettings,
    /** The settings themselves, for integrations: each reads its own, see `integrations/`. */
    val env: Env,
) {
    companion object {
        fun fromEnv(env: Env = Env.system()): AppConfig {
            val caldav = env.string("SC_CALDAV_URL")?.let { url ->
                CalDavConfig(
                    baseUrl = url,
                    username = env.required("SC_CALDAV_USERNAME", because = "SC_CALDAV_URL is set"),
                    password = env.required("SC_CALDAV_PASSWORD", because = "SC_CALDAV_URL is set"),
                )
            }

            val dataDir = Path(env.string("SC_DATA_DIR") ?: "data").toAbsolutePath().normalize()
            Files.createDirectories(dataDir)

            // Absent or non-existent static dir means "running in dev, Vite serves the UI".
            val staticDir = env.string("SC_STATIC_DIR")
                ?.let { Path(it).toAbsolutePath().normalize() }
                ?.takeIf { Files.isDirectory(it) }

            return AppConfig(
                version = env.string("SC_VERSION") ?: "dev",
                host = env.string("SC_HOST") ?: "0.0.0.0",
                port = env.string("SC_PORT")?.toIntOrNull() ?: 8080,
                dataDir = dataDir,
                staticDir = staticDir,
                timeZone = env.string("SC_TIMEZONE")?.let(ZoneId::of) ?: ZoneId.systemDefault(),
                syncIntervalSeconds = env.string("SC_SYNC_INTERVAL_SECONDS")?.toLongOrNull() ?: 60L,
                caldav = caldav,
                devCors = env.string("SC_DEV_CORS")?.toBooleanStrictOrNull() ?: false,
                proxy = ProxySettings.from(env),
                env = env,
            )
        }
    }
}
