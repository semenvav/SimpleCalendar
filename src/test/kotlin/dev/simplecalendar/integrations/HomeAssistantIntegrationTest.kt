package dev.simplecalendar.integrations

import dev.simplecalendar.integrations.homeassistant.HomeAssistantIntegration
import dev.simplecalendar.integrations.homeassistant.SensorPair
import dev.simplecalendar.plugins.UpstreamException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeAssistantIntegrationTest {

    private val token = "long-lived-token"

    /** Answers like Home Assistant's REST API, including its refusals. */
    private fun withHomeAssistant(block: suspend (baseUrl: String, context: IntegrationContext) -> Unit) =
        withFakeService(
            routes = {
                get("/api/states/{entityId}") {
                    if (call.request.headers[HttpHeaders.Authorization] != "Bearer $token") {
                        return@get call.respondText("401: Unauthorized", status = HttpStatusCode.Unauthorized)
                    }
                    when (call.parameters["entityId"]) {
                        "sensor.living_room_temperature" ->
                            call.respondText(fixture("ha-state-temperature.json"), ContentType.Application.Json)
                        "sensor.living_room_humidity" ->
                            call.respondText(fixture("ha-state-humidity.json"), ContentType.Application.Json)
                        "person.mama" ->
                            call.respondText(fixture("ha-state-person.json"), ContentType.Application.Json)
                        else -> call.respondText(
                            """{"message":"Entity not found."}""",
                            ContentType.Application.Json,
                            HttpStatusCode.NotFound,
                        )
                    }
                }
            },
            block = block,
        )

    @Test
    fun `reads the chosen entities in the order they were listed`() = withHomeAssistant { baseUrl, context ->
        val states = HomeAssistantIntegration(
            context.http, baseUrl, token,
            listOf("person.mama", "sensor.renamed_away", "sensor.living_room_temperature"),
        ).fetch()

        assertEquals(listOf("person.mama", "sensor.living_room_temperature"), states.entities.map { it.entityId })
        assertEquals(listOf("sensor.renamed_away"), states.missing, "one typo must not hide the rest")

        val temperature = states.entities[1]
        assertEquals("Гостиная Температура", temperature.name)
        assertEquals("23.4", temperature.state)
        assertEquals("°C", temperature.unit)
        assertEquals("temperature", temperature.deviceClass)
        assertEquals("2026-09-16T09:12:03.123456+00:00", temperature.lastChanged)

        val mama = states.entities[0]
        assertEquals("home", mama.state)
        assertNull(mama.unit)
        assertEquals("device_tracker.phone", mama.attributes["source"]?.jsonPrimitive?.content, "attributes pass through")
    }

    @Test
    fun `a sensor pair becomes one line with both numbers`() = withHomeAssistant { baseUrl, context ->
        val states = HomeAssistantIntegration(
            context.http, baseUrl, token,
            entityIds = emptyList(),
            sensors = listOf(
                SensorPair("Гостиная", "sensor.living_room_temperature", "sensor.living_room_humidity"),
                SensorPair("Улица", "sensor.gone", "sensor.also_gone"),
            ),
        ).fetch()

        assertEquals(listOf("Гостиная", "Улица"), states.sensors.map { it.name }, "the name on the wall is ours")
        with(states.sensors[0]) {
            assertEquals(23.4, temperature)
            assertEquals("°C", temperatureUnit)
            assertEquals(47.0, humidity)
            assertEquals("%", humidityUnit)
        }
        // A place whose sensors have gone keeps its line, empty: an absent line is easy to miss.
        assertEquals(listOf(null, null), listOf(states.sensors[1].temperature, states.sensors[1].humidity))
        assertEquals(emptyList(), states.entities, "pairs are not listed twice as plain entities")
    }

    @Test
    fun `a rejected token fails the whole fetch`() = withHomeAssistant { baseUrl, context ->
        val error = assertFailsWith<UpstreamException> {
            HomeAssistantIntegration(context.http, baseUrl, "stale", listOf("person.mama")).fetch()
        }
        assertTrue("401" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `settings`() {
        assertNull(HomeAssistantIntegration.fromEnv(contextWith()))

        val configured = HomeAssistantIntegration.fromEnv(
            contextWith(
                "SC_HA_URL" to "http://homeassistant:8123/",
                "SC_HA_TOKEN" to token,
                "SC_HA_ENTITIES" to "sensor.living_room_temperature, person.mama",
            ),
        )
        assertEquals("http://homeassistant:8123", assertNotNull(configured).endpoint)

        // Sensor pairs alone are enough: a wall that shows only two rooms needs no entity list.
        assertNotNull(
            HomeAssistantIntegration.fromEnv(
                contextWith(
                    "SC_HA_URL" to "http://ha:8123",
                    "SC_HA_TOKEN" to token,
                    "SC_HA_SENSORS" to "Спальня=sensor.bed_t+sensor.bed_h",
                ),
            ),
        )

        assertFailsWith<IllegalStateException>("the token is required") {
            HomeAssistantIntegration.fromEnv(contextWith("SC_HA_URL" to "http://ha:8123", "SC_HA_ENTITIES" to "person.mama"))
        }
        assertFailsWith<IllegalArgumentException>("something to show is required") {
            HomeAssistantIntegration.fromEnv(contextWith("SC_HA_URL" to "http://ha:8123", "SC_HA_TOKEN" to token))
        }
        assertFailsWith<IllegalArgumentException>("an id that would escape the path") {
            HomeAssistantIntegration.fromEnv(
                contextWith("SC_HA_URL" to "http://ha:8123", "SC_HA_TOKEN" to token, "SC_HA_ENTITIES" to "../config"),
            )
        }
    }

    @Test
    fun `SC_HA_SENSORS is read as name equals temperature plus humidity`() {
        val parsed = HomeAssistantIntegration.parseSensors(
            " Спальня = sensor.bed_t + sensor.bed_h , Улица=sensor.out_t ",
        )
        assertEquals(
            listOf(
                SensorPair("Спальня", "sensor.bed_t", "sensor.bed_h"),
                SensorPair("Улица", "sensor.out_t", null),
            ),
            parsed,
            "spaces are noise, and the humidity half is optional",
        )
        assertEquals(emptyList(), HomeAssistantIntegration.parseSensors(null))

        assertFailsWith<IllegalArgumentException>("no name") { HomeAssistantIntegration.parseSensors("sensor.bed_t") }
        assertFailsWith<IllegalArgumentException>("three entities") {
            HomeAssistantIntegration.parseSensors("Спальня=sensor.a+sensor.b+sensor.c")
        }
        assertFailsWith<IllegalArgumentException>("an id that would escape the path") {
            HomeAssistantIntegration.parseSensors("Спальня=../config")
        }
    }
}
