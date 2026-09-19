package dev.simplecalendar.api

import dev.simplecalendar.AppComponents
import dev.simplecalendar.ical.RepeatChange
import dev.simplecalendar.plugins.ApiError
import dev.simplecalendar.plugins.NotFoundException
import dev.simplecalendar.plugins.NotSupportedException
import dev.simplecalendar.service.EventWriteService
import dev.simplecalendar.sync.SyncStatus
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Refuses absurd windows so one bad request cannot pin a CPU expanding a century of recurrences. */
private val MAX_RANGE: Duration = Duration.ofDays(1100)

private val COLOR_PATTERN = Regex("^#[0-9a-fA-F]{6}$")

fun Application.installRoutes(components: AppComponents) {
    val zone = components.config.timeZone

    routing {
        route("/api") {

            get("/health") {
                val status = components.sync?.status() ?: NOT_CONFIGURED
                call.respond(
                    HealthDto(
                        status = "ok",
                        version = components.config.version,
                        timeZone = zone.id,
                        sync = status.toDto(),
                    ),
                )
            }

            get("/calendars") {
                call.respond(components.calendars.all().map { it.toDto() })
            }

            patch("/calendars/{id}") {
                val id = call.parameters["id"].orEmpty()
                val existing = components.calendars.byId(id)
                    ?: throw NotFoundException("Calendar '$id' not found")
                val patch = call.receive<CalendarPatch>()

                patch.color?.takeIf { it.isNotBlank() }?.let {
                    require(COLOR_PATTERN.matches(it)) { "Colour must look like #rrggbb, got '$it'" }
                }

                components.calendars.updateCustomisation(
                    id = id,
                    // An explicit empty string clears the override and restores the server's value;
                    // an absent field leaves it alone.
                    customName = if (patch.name == null) existing.customName else patch.name.ifBlank { null },
                    customColor = if (patch.color == null) existing.customColor else patch.color.ifBlank { null },
                    visible = patch.visible ?: existing.visible,
                    sortOrder = patch.sortOrder ?: existing.sortOrder,
                )
                call.respond(checkNotNull(components.calendars.byId(id)).toDto())
            }

            get("/events") {
                val from = parseMoment(call.parameters["from"] ?: missing("from"), zone)
                val to = parseMoment(call.parameters["to"] ?: missing("to"), zone)
                require(from.isBefore(to)) { "'from' must be before 'to'" }
                require(Duration.between(from, to) <= MAX_RANGE) {
                    "Requested range is longer than ${MAX_RANGE.toDays()} days"
                }

                val calendarIds = call.parameters["calendars"]
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)

                val readOnlyById = components.calendars.all().associate { it.id to it.readOnly }
                val events = components.query.occurrences(from, to, calendarIds)
                    .map { it.toDto(readOnly = readOnlyById[it.calendarId] ?: true) }

                call.respond(events)
            }

            post("/events") {
                val write = requireWriteService(components)
                val request = call.receive<EventWriteRequest>()
                val calendarId = request.calendarId?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Не указано, в какой календарь добавить событие.")

                val draft = request.toDraft(zone)
                val rule = (request.repeatChange(draft) as? RepeatChange.To)?.rule
                val created = write.create(calendarId, draft, rule)
                call.respond(HttpStatusCode.Created, created.toDto(readOnly = false))
            }

            patch("/events") {
                val write = requireWriteService(components)
                val event = parseEventId(call.parameters["id"] ?: missing("id"))
                val scope = parseScope(call.parameters["scope"])
                val request = call.receive<EventWriteRequest>()

                val draft = request.toDraft(zone)
                val updated = write.update(
                    calendarId = event.calendarId,
                    href = event.href,
                    draft = draft,
                    instanceId = event.instanceId,
                    scope = scope,
                    repeat = request.repeatChange(draft),
                )
                call.respond(updated.toDto(readOnly = false))
            }

            // Separate from PATCH /events: a mark is not an edit. It carries no draft, it never
            // moves anything, and it must stay possible on an event the form could not describe.
            patch("/events/mark") {
                val write = requireWriteService(components)
                val event = parseEventId(call.parameters["id"] ?: missing("id"))
                val scope = parseScope(call.parameters["scope"])
                val request = call.receive<EventMarkRequest>()

                val marked = write.mark(
                    calendarId = event.calendarId,
                    href = event.href,
                    mark = request.toMark(),
                    instanceId = event.instanceId,
                    scope = scope,
                )
                call.respond(marked.toDto(readOnly = false))
            }

            delete("/events") {
                val write = requireWriteService(components)
                val event = parseEventId(call.parameters["id"] ?: missing("id"))
                val scope = parseScope(call.parameters["scope"])

                write.delete(event.calendarId, event.href, event.instanceId, scope)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/sync") {
                val sync = components.sync
                    ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, NOT_CONFIGURED.toDto())
                sync.syncAll()
                call.respond(sync.status().toDto())
            }

            integrationRoutes(components.integrations)
        }

        // In production the frontend is baked into the image and served from here. In development
        // SC_STATIC_DIR is unset, Vite serves the UI on its own port and proxies /api back to us.
        //
        // Served by hand rather than with the staticFiles plugin: pairing that plugin with a
        // single-page fallback route makes the two compete, and the fallback wins — every asset
        // request then comes back as index.html and the page fails to load its own code. One
        // route that looks for a real file first and falls back afterwards has no such ambiguity.
        components.config.staticDir?.let { dir ->
            val root = dir.toRealPath()
            val index = root.resolve("index.html").toFile()

            get("/{path...}") {
                val segments = call.parameters.getAll("path").orEmpty()

                // An unknown API path must not be answered with the HTML shell.
                if (segments.firstOrNull() == "api") {
                    return@get call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("not_found", "No such endpoint"),
                    )
                }

                val file = resolveStaticFile(root, segments)
                when {
                    file != null -> {
                        // Vite fingerprints asset filenames, so they can be cached indefinitely.
                        call.response.header(
                            HttpHeaders.CacheControl,
                            if (segments.firstOrNull() == "assets") IMMUTABLE else "no-cache",
                        )
                        call.respondFile(file)
                    }
                    // A client-side route has no file behind it; hand over the shell.
                    index.isFile -> {
                        call.response.header(HttpHeaders.CacheControl, "no-cache")
                        call.respondFile(index)
                    }
                    else -> call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Not found"))
                }
            }
        }
    }
}

private const val IMMUTABLE = "public, max-age=31536000, immutable"

/**
 * Maps URL segments to a file inside the static root, or `null` if there is no such file.
 *
 * Normalising and then re-checking the prefix is what stops `../../etc/passwd` from escaping —
 * the check has to happen after normalisation, not before.
 */
private fun resolveStaticFile(root: Path, segments: List<String>): File? {
    if (segments.isEmpty() || segments.any { it.isEmpty() }) return null

    val candidate = segments.fold(root) { path, segment -> path.resolve(segment) }.normalize()
    if (!candidate.startsWith(root)) return null

    return candidate.toFile().takeIf { it.isFile }
}

private val NOT_CONFIGURED = SyncStatus(
    configured = false,
    lastSuccessAt = null,
    lastError = "SC_CALDAV_URL is not set",
    calendarCount = 0,
    eventCount = 0,
    revision = 0,
)

private fun missing(name: String): Nothing =
    throw IllegalArgumentException("Query parameter '$name' is required")

private fun requireWriteService(components: AppComponents): EventWriteService =
    components.write
        ?: throw NotSupportedException("Источник календарей не настроен — писать пока некуда.")

/** Which resource, and for a repeating event which instance of it, a write is about. */
private data class EventRef(val calendarId: String, val href: String, val instanceId: String?)

/**
 * Splits the composite id the read API hands out: `calendarId|href|instanceId`, the last part
 * `-` for an event that does not repeat.
 *
 * An href is a URL path and can never contain `|`, so splitting is unambiguous. Passing the id
 * as a query parameter rather than in the path keeps the encoded slashes inside it from being
 * re-split into path segments.
 */
private fun parseEventId(id: String): EventRef {
    val parts = id.split('|', limit = 3)
    require(parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
        "Неверный идентификатор события: «$id»."
    }
    return EventRef(parts[0], parts[1], parts.getOrNull(2)?.takeIf { it.isNotBlank() && it != "-" })
}

/**
 * Accepts the shapes a calendar UI naturally sends: a UTC instant, an offset date-time, a plain
 * local date-time, or a bare date. The last two are read in the household zone.
 */
private fun parseMoment(raw: String, zone: ZoneId): Instant =
    runCatching { Instant.parse(raw) }
        .recoverCatching { OffsetDateTime.parse(raw).toInstant() }
        .recoverCatching { LocalDateTime.parse(raw).atZone(zone).toInstant() }
        .recoverCatching { LocalDate.parse(raw).atStartOfDay(zone).toInstant() }
        .getOrElse { throw IllegalArgumentException("Cannot read '$raw' as a date or time") }
