package dev.simplecalendar.integrations

import dev.simplecalendar.api.integrationRoutes
import dev.simplecalendar.config.AppConfig
import dev.simplecalendar.config.Env
import dev.simplecalendar.integrations.immich.ImmichIntegration
import dev.simplecalendar.plugins.UpstreamException
import dev.simplecalendar.plugins.installPlugins
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImmichIntegrationTest {

    private val apiKey = "immich-api-key"
    private val sea = "6f1a2b3c-4d5e-4f60-8a7b-9c0d1e2f3a4b"
    private val albumA = "11111111-2222-4333-8444-555555555555"
    private val albumB = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    /** Every search body Immich received. */
    private val searches: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Every thumbnail Immich was asked for. */
    private val thumbnails: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun withImmich(block: suspend (baseUrl: String, context: IntegrationContext) -> Unit) = withFakeService(
        routes = {
            route("/api") {
                post("/search/random") {
                    if (call.request.headers["x-api-key"] != apiKey) {
                        return@post call.respondText(
                            """{"message":"Invalid API key","error":"Unauthorized","statusCode":401}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized,
                        )
                    }
                    searches += call.receiveText()
                    call.respondText(fixture("immich-random.json"), ContentType.Application.Json)
                }
                get("/assets/{id}/thumbnail") {
                    thumbnails += "${call.parameters["id"]}?size=${call.request.queryParameters["size"]}"
                    call.respondBytes(jpeg, ContentType.Image.JPEG)
                }
            }
        },
        block = block,
    )

    @Test
    fun `picks random photos from each album, leaving videos out`() = withImmich { baseUrl, context ->
        val photos = ImmichIntegration(context.http, baseUrl, apiKey, listOf(albumA, albumB)).fetch().photos

        // Asked once per album: given both at once, Immich returns only photos that are in both.
        assertEquals(2, searches.size)
        val bodies = searches.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf(listOf(albumA), listOf(albumB)), bodies.map { body -> body["albumIds"]!!.jsonArray.map { it.jsonPrimitive.content } })
        assertTrue(bodies.all { it["type"]?.jsonPrimitive?.content == "IMAGE" && it["withExif"]?.jsonPrimitive?.boolean == true })
        assertEquals(ImmichIntegration.PHOTOS, bodies.sumOf { it["size"]!!.jsonPrimitive.content.toInt() })

        // The same picture found through both albums is shown once.
        assertEquals(2, photos.size, "the video must be left out and duplicates merged: $photos")

        val seaPhoto = photos.single { it.id == sea }
        assertEquals("/api/integrations/immich/photos/$sea", seaPhoto.url)
        assertEquals("2023-07-14", seaPhoto.takenOn, "the local date, not the UTC one")
        assertEquals("Хайфа", seaPhoto.city)
        assertEquals("Израиль", seaPhoto.country)
        assertEquals("Море после дождя", seaPhoto.description)

        val scan = photos.single { it.id != sea }
        assertEquals("2020-01-01", scan.takenOn)
        assertNull(scan.description, "Immich's empty description is no description")
    }

    @Test
    fun `a rejected key fails the fetch`() = withImmich { baseUrl, context ->
        val error = assertFailsWith<UpstreamException> {
            ImmichIntegration(context.http, baseUrl, "wrong", listOf(albumA)).fetch()
        }
        assertTrue("Invalid API key" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `photos are served through us, and only the ones on show`() = withImmich { baseUrl, context ->
        val immich = ImmichIntegration(context.http, baseUrl, apiKey, listOf(albumA))
        val hub = IntegrationHub(listOf(immich))
        val dataDir = Files.createTempDirectory("sc-immich-test")

        testApplication {
            application {
                installPlugins(AppConfig.fromEnv(Env.of(mapOf("SC_DATA_DIR" to dataDir.toString()))))
                routing { route("/api") { integrationRoutes(hub) } }
            }

            // Nothing fetched yet, so nothing may be served.
            assertEquals(HttpStatusCode.NotFound, client.get("/api/integrations/immich/photos/$sea").status)

            hub.refresh(immich)

            val snapshot = Json.parseToJsonElement(client.get("/api/integrations/immich").bodyAsText()).jsonObject
            assertTrue((snapshot["data"]!!.jsonObject["photos"] as JsonArray).isNotEmpty())
            assertNull(snapshot["lastError"])

            val photo = client.get("/api/integrations/immich/photos/$sea")
            assertEquals(HttpStatusCode.OK, photo.status)
            assertEquals(ContentType.Image.JPEG, photo.contentType()?.withoutParameters())
            assertContentEquals(jpeg, photo.readRawBytes())
            assertNotNull(photo.headers[HttpHeaders.CacheControl])
            assertEquals(listOf("$sea?size=preview"), thumbnails.toList(), "the preview, never the original")

            // Any other asset in the library is not ours to hand out.
            val other = "00000000-0000-4000-8000-000000000000"
            assertEquals(HttpStatusCode.NotFound, client.get("/api/integrations/immich/photos/$other").status)
            assertEquals(1, thumbnails.size, "Immich must not even be asked")

            val list = Json.parseToJsonElement(client.get("/api/integrations").bodyAsText()).jsonArray
            assertEquals(listOf("immich"), list.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        }
    }

    @Test
    fun `settings`() {
        assertNull(ImmichIntegration.fromEnv(contextWith()))
        assertNotNull(
            ImmichIntegration.fromEnv(
                contextWith("SC_IMMICH_URL" to "http://immich-server:2283/api", "SC_IMMICH_API_KEY" to apiKey, "SC_IMMICH_ALBUMS" to albumA),
            ),
        )
        assertFailsWith<IllegalArgumentException>("albums are required") {
            ImmichIntegration.fromEnv(contextWith("SC_IMMICH_URL" to "http://immich:2283", "SC_IMMICH_API_KEY" to apiKey))
        }
        assertFailsWith<IllegalArgumentException>("an album name is not an id") {
            ImmichIntegration.fromEnv(
                contextWith("SC_IMMICH_URL" to "http://immich:2283", "SC_IMMICH_API_KEY" to apiKey, "SC_IMMICH_ALBUMS" to "Стена"),
            )
        }
        assertFailsWith<IllegalStateException>("the key is required") {
            ImmichIntegration.fromEnv(contextWith("SC_IMMICH_URL" to "http://immich:2283", "SC_IMMICH_ALBUMS" to albumA))
        }
    }
}
