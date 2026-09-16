package dev.simplecalendar.api

import dev.simplecalendar.integrations.IntegrationHub
import dev.simplecalendar.integrations.IntegrationStatus
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class IntegrationStatusDto(
    val id: String,
    val refreshSeconds: Long,
    /** When the snapshot on show was fetched; absent until the first fetch succeeds. */
    val fetchedAt: String?,
    /** Why the latest fetch failed. The previous snapshot, if any, is still served. */
    val lastError: String?,
)

@Serializable
data class IntegrationSnapshotDto(
    val id: String,
    val fetchedAt: String?,
    val lastError: String?,
    /** The integration's own shape; absent until the first fetch succeeds. */
    val data: JsonElement?,
)

/**
 * `GET /api/integrations` lists what is configured; `GET /api/integrations/<id>` serves each
 * one's last snapshot, next to whatever endpoints the integration adds for itself.
 *
 * An integration that is not configured has no routes at all, so asking for it is a plain 404.
 */
fun Route.integrationRoutes(hub: IntegrationHub) {
    route("/integrations") {
        get {
            call.respond(hub.status().map { it.toDto() })
        }

        for (integration in hub.integrations) {
            route("/${integration.id}") {
                get {
                    val status = hub.status(integration)
                    call.respond(
                        IntegrationSnapshotDto(
                            id = status.id,
                            fetchedAt = status.snapshot?.fetchedAt?.toString(),
                            lastError = status.lastError,
                            data = status.snapshot?.data,
                        ),
                    )
                }
                with(integration) { routes() }
            }
        }
    }
}

private fun IntegrationStatus.toDto() = IntegrationStatusDto(
    id = id,
    refreshSeconds = refreshEvery.inWholeSeconds,
    fetchedAt = snapshot?.fetchedAt?.toString(),
    lastError = lastError,
)
