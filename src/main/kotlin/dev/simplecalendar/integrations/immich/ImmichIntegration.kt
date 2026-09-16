package dev.simplecalendar.integrations.immich

import dev.simplecalendar.integrations.Integration
import dev.simplecalendar.integrations.IntegrationContext
import dev.simplecalendar.integrations.integrationJson
import dev.simplecalendar.integrations.normaliseBaseUrl
import dev.simplecalendar.integrations.requireOk
import dev.simplecalendar.plugins.NotFoundException
import dev.simplecalendar.plugins.UpstreamException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.time.Duration.Companion.hours

@Serializable
data class ImmichPhotos(
    /** A fresh random pick every refresh, in no particular order. */
    val photos: List<Photo>,
)

@Serializable
data class Photo(
    val id: String,
    /** Where the browser gets the picture: through us, since the API key must not reach it. */
    val url: String,
    /** The day it was taken, as the camera's clock saw it. */
    val takenOn: String?,
    val city: String?,
    val country: String?,
    val description: String?,
)

/**
 * Photos from chosen Immich albums, for a picture frame beside the calendar.
 *
 * Every refresh asks Immich for a new random handful. The pictures themselves are fetched through
 * `/api/integrations/immich/photos/<id>`, which serves only photos from the current or the
 * previous handful: the calendar must not become a way to pull anything else out of the library.
 *
 * Albums are required. A random pick from a whole family library would sooner or later put a
 * screenshot or a passport scan on the kitchen wall; an album is a choice somebody made.
 */
class ImmichIntegration(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val albumIds: List<String>,
) : Integration<ImmichPhotos> {

    override val id = "immich"
    override val endpoint get() = baseUrl
    override val refreshEvery = 1.hours
    override val snapshotSerializer = ImmichPhotos.serializer()

    // The previous handful stays servable, so a wall still showing it does not hit a gap in the
    // minute before it picks up the new one.
    @Volatile private var current: Set<String> = emptySet()
    @Volatile private var previous: Set<String> = emptySet()

    override suspend fun fetch(): ImmichPhotos {
        // One request per album: given several, Immich returns only photos that are in all of them.
        val perAlbum = ceil(PHOTOS.toDouble() / albumIds.size).toInt()
        val assets = albumIds.flatMap { album -> randomFrom(album, perAlbum) }
            .filter { it.type == "IMAGE" }
            .distinctBy { it.id }
            .shuffled()

        val photos = assets.map { asset ->
            Photo(
                id = asset.id,
                url = "/api/integrations/$id/photos/${asset.id}",
                // Immich writes local wall-clock time with a `Z` it does not mean; the date part is right.
                takenOn = asset.localDateTime?.take(10),
                city = asset.exifInfo?.city?.ifBlank { null },
                country = asset.exifInfo?.country?.ifBlank { null },
                description = asset.exifInfo?.description?.ifBlank { null },
            )
        }

        previous = current
        current = photos.mapTo(HashSet()) { it.id }
        return ImmichPhotos(photos)
    }

    private suspend fun randomFrom(albumId: String, size: Int): List<Asset> {
        val response = http.post("$baseUrl/api/search/random") {
            header(API_KEY_HEADER, apiKey)
            contentType(ContentType.Application.Json)
            // The flat filters are deprecated since Immich 3.2 in favour of `filter`, but still
            // accepted — and they are all that 1.x and 2.x understand.
            setBody(integrationJson.encodeToString(RandomSearch.serializer(), RandomSearch(size, listOf(albumId))))
        }.requireOk("Immich")
        return integrationJson.decodeFromString<List<Asset>>(response.bodyAsText())
    }

    override fun Route.routes() {
        get("/photos/{assetId}") {
            val assetId = call.parameters["assetId"].orEmpty()
            if (assetId !in current && assetId !in previous) {
                throw NotFoundException("No such photo among the ones on show")
            }

            val upstream = http.get("$baseUrl/api/assets/$assetId/thumbnail") {
                header(API_KEY_HEADER, apiKey)
                // About 1440 px on the long side: sharp on a tablet, a fraction of the original.
                parameter("size", "preview")
            }
            if (!upstream.status.isSuccess()) throw UpstreamException("Immich answered ${upstream.status.value}")

            val type = upstream.contentType()
                ?.takeUnless { it.match(ContentType.Application.OctetStream) }
                ?: ContentType.Image.JPEG
            // A photo under an id never changes, so the tablet need not ask twice in a day.
            call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
            call.respondBytes(upstream.readRawBytes(), type)
        }
    }

    companion object {
        /** How many to pick per refresh, across all albums. */
        const val PHOTOS = 100

        private const val API_KEY_HEADER = "x-api-key"

        private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun fromEnv(context: IntegrationContext): ImmichIntegration? {
            val env = context.env
            val url = env.string("SC_IMMICH_URL") ?: return null
            val albums = env.list("SC_IMMICH_ALBUMS")
            require(albums.isNotEmpty()) {
                "SC_IMMICH_URL is set, but SC_IMMICH_ALBUMS is missing: list the ids of the albums to show"
            }
            val invalid = albums.filterNot(UUID::matches)
            require(invalid.isEmpty()) {
                "SC_IMMICH_ALBUMS: not album ids: $invalid — the id is the last part of the album's address in Immich"
            }

            return ImmichIntegration(
                http = context.http,
                baseUrl = normaliseBaseUrl(url),
                apiKey = env.required("SC_IMMICH_API_KEY", because = "SC_IMMICH_URL is set"),
                albumIds = albums.distinct(),
            )
        }
    }
}

@Serializable
private class RandomSearch(
    val size: Int,
    val albumIds: List<String>,
    val type: String = "IMAGE",
    val withExif: Boolean = true,
)

@Serializable
private class Asset(
    val id: String,
    val type: String,
    val localDateTime: String?,
    val exifInfo: Exif?,
)

@Serializable
private class Exif(
    val city: String?,
    val country: String?,
    val description: String?,
)
