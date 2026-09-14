package dev.simplecalendar.plugins

import dev.simplecalendar.config.AppConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

/** Error body returned for every failed API call, so the frontend has one shape to handle. */
@Serializable
data class ApiError(val error: String, val message: String)

/** Thrown by handlers when the caller asked for something that does not exist. */
class NotFoundException(message: String) : RuntimeException(message)

/** Thrown when the upstream CalDAV server rejected a write because the resource moved on. */
class ConflictException(message: String) : RuntimeException(message)

/** Thrown when the caller may see something but not change it — a read-only calendar. */
class ForbiddenException(message: String) : RuntimeException(message)

/**
 * Thrown for a request that is well-formed but asks for something not built yet — editing a
 * single occurrence of a repeating event, for instance. Distinct from a bad request on purpose:
 * the caller did nothing wrong, and the message says what to do instead.
 */
class NotSupportedException(message: String) : RuntimeException(message)

fun Application.installPlugins(config: AppConfig) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        })
    }

    install(CallLogging) {
        level = Level.INFO
        // Health checks are polled by Docker every few seconds; logging them is pure noise.
        filter { call -> !call.request.local.uri.startsWith("/api/health") }
    }

    install(Compression) {
        gzip()
    }

    if (config.devCors) {
        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Delete)
        }
    }

    install(StatusPages) {
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", cause.message ?: "Not found"))
        }
        exception<ConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ApiError("conflict", cause.message ?: "Conflict"))
        }
        exception<ForbiddenException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, ApiError("forbidden", cause.message ?: "Forbidden"))
        }
        exception<NotSupportedException> { call, cause ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ApiError("not_supported", cause.message ?: "Not supported yet"),
            )
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", cause.message ?: "Bad request"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", cause.message ?: "Bad request"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error on ${call.request.local.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError("internal_error", cause.message ?: cause::class.simpleName ?: "Internal error"),
            )
        }
    }
}
