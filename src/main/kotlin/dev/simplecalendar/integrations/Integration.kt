package dev.simplecalendar.integrations

import dev.simplecalendar.config.Env
import dev.simplecalendar.integrations.homeassistant.HomeAssistantIntegration
import dev.simplecalendar.integrations.immich.ImmichIntegration
import dev.simplecalendar.integrations.weather.WeatherIntegration
import dev.simplecalendar.plugins.UpstreamException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.server.routing.Route
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.time.ZoneId
import kotlin.time.Duration

/**
 * Something from outside the calendar that the wall shows next to it: the weather, a sensor in
 * Home Assistant, photos from Immich.
 *
 * An integration only knows how to fetch. [IntegrationHub] decides when, keeps the last good
 * result, records failures and serves the result as `GET /api/integrations/<id>` — so the browser
 * reads our copy, never the service itself, and keys and tokens stay on the server.
 */
interface Integration<T : Any> {

    /** Stable: it is part of the API path the frontend asks for. */
    val id: String

    /** The service it talks to. Only for the start-up log, which also says whether a proxy is used. */
    val endpoint: String

    val refreshEvery: Duration

    val snapshotSerializer: KSerializer<T>

    /** Fetches a fresh snapshot. Throws on failure; the previous snapshot stays on show. */
    suspend fun fetch(): T

    /**
     * Endpoints for what a snapshot cannot carry — photo bytes, for one. Mounted under
     * `/api/integrations/<id>`.
     */
    fun Route.routes() {}
}

/** What an integration is built from. */
class IntegrationContext(
    val env: Env,
    val http: HttpClient,
    val zone: ZoneId,
)

/**
 * Every integration the application knows, each answering null when its settings are absent.
 *
 * Adding one is a package next to these and a line here. Polling, status, failures and the
 * snapshot endpoint come from [IntegrationHub] for free.
 */
private val FACTORIES: List<(IntegrationContext) -> Integration<*>?> = listOf(
    { WeatherIntegration.fromEnv(it) },
    { HomeAssistantIntegration.fromEnv(it) },
    { ImmichIntegration.fromEnv(it) },
)

fun configuredIntegrations(context: IntegrationContext): List<Integration<*>> =
    FACTORIES.mapNotNull { it(context) }

/**
 * One client for all integrations. CIO like the CalDAV client, so still one HTTP stack in the
 * container, and routed through the same proxy selector (see `net/Proxy.kt`).
 */
fun integrationHttpClient(): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
    }
    install(UserAgent) { agent = "SimpleCalendar" }
}

/** Reads what services send: fields we do not model are skipped, absent nullable ones are null. */
val integrationJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Passes a successful response through; otherwise throws with the status and the start of the body, where services put the reason. */
suspend fun HttpResponse.requireOk(service: String): HttpResponse {
    if (status.isSuccess()) return this
    val reason = runCatching { bodyAsText() }.getOrDefault("").trim().take(200)
    throw UpstreamException("$service answered ${status.value}" + if (reason.isEmpty()) "" else ": $reason")
}

/** A base URL as people paste it: without the trailing slash, and without `/api`, which we add ourselves. */
fun normaliseBaseUrl(raw: String): String = raw.trimEnd('/').removeSuffix("/api").trimEnd('/')
