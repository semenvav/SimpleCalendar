package dev.simplecalendar.integrations

import dev.simplecalendar.config.Env
import dev.simplecalendar.integrations.homeassistant.HomeAssistantIntegration
import dev.simplecalendar.integrations.homeassistant.SensorPlace
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
                        "sensor.living_room_pressure" ->
                            call.respondText(fixture("ha-state-pressure.json"), ContentType.Application.Json)
                        "sensor.hall_pressure" ->
                            call.respondText(state("sensor.hall_pressure", "756", "mmHg"), ContentType.Application.Json)
                        "sensor.balcony_pressure" ->
                            call.respondText(state("sensor.balcony_pressure", "unavailable", "hPa"), ContentType.Application.Json)
                        "sensor.attic_pressure" ->
                            call.respondText(state("sensor.attic_pressure", "1", "atm"), ContentType.Application.Json)
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

    /** A state with nothing but its unit, for the cases no fixture covers. */
    private fun state(entityId: String, state: String, unit: String) =
        """{"entity_id":"$entityId","state":"$state","attributes":{"unit_of_measurement":"$unit"},"last_changed":null}"""

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
    fun `a place becomes one line with its numbers`() = withHomeAssistant { baseUrl, context ->
        val states = HomeAssistantIntegration(
            context.http, baseUrl, token,
            entityIds = emptyList(),
            sensors = listOf(
                SensorPlace("Гостиная", "sensor.living_room_temperature", "sensor.living_room_humidity"),
                SensorPlace("Улица", "sensor.gone", "sensor.also_gone"),
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
        assertEquals(emptyList(), states.entities, "places are not listed twice as plain entities")
    }

    @Test
    fun `the climate window has places of its own, and the pressure is their mean in hPa`() =
        withHomeAssistant { baseUrl, context ->
            val states = HomeAssistantIntegration(
                context.http, baseUrl, token,
                entityIds = emptyList(),
                sensors = listOf(SensorPlace("Гостиная", "sensor.living_room_temperature")),
                climate = listOf(
                    SensorPlace(
                        "Гостиная",
                        "sensor.living_room_temperature", "sensor.living_room_humidity", "sensor.living_room_pressure",
                    ),
                    SensorPlace("Коридор", "sensor.gone", "sensor.also_gone", "sensor.hall_pressure"),
                    SensorPlace("Балкон", "sensor.gone", null, "sensor.balcony_pressure"),
                    SensorPlace("Чердак", "sensor.gone", null, "sensor.attic_pressure"),
                ),
            ).fetch()

            assertEquals(listOf("Гостиная"), states.sensors.map { it.name }, "the strip keeps to its own list")
            assertEquals(listOf("Гостиная", "Коридор", "Балкон", "Чердак"), states.climate.map { it.name })
            with(states.climate[0]) {
                assertEquals(23.4, temperature)
                assertEquals(47.0, humidity)
                assertEquals(1008.3, pressure)
                assertEquals("hPa", pressureUnit)
            }

            // 756 mmHg is 1007.92 hPa. The sensor that went quiet and the unit Home Assistant does
            // not offer stay out of the mean rather than drag it to nonsense.
            assertEquals(1008.11, assertNotNull(states.pressure), 0.01)
        }

    @Test
    fun `without a list of its own the climate window shows the strip's places`() =
        withHomeAssistant { baseUrl, context ->
            val env = Env.of(
                mapOf(
                    "SC_HA_URL" to baseUrl,
                    "SC_HA_TOKEN" to token,
                    "SC_HA_SENSORS" to "Гостиная=sensor.living_room_temperature+sensor.living_room_humidity",
                ),
            )
            val states = assertNotNull(HomeAssistantIntegration.fromEnv(IntegrationContext(env, context.http, context.zone)))
                .fetch()

            assertEquals(listOf("Гостиная"), states.climate.map { it.name })
            assertEquals(states.sensors, states.climate)
            assertNull(states.pressure, "nobody named a pressure sensor")
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

        // So is the window's list alone.
        assertNotNull(
            HomeAssistantIntegration.fromEnv(
                contextWith(
                    "SC_HA_URL" to "http://ha:8123",
                    "SC_HA_TOKEN" to token,
                    "SC_HA_CLIMATE" to "Спальня=sensor.bed_t+sensor.bed_h+sensor.bed_p",
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
    fun `places are read as name equals temperature plus humidity plus pressure`() {
        fun parse(raw: String?) = HomeAssistantIntegration.parseSensors("SC_HA_CLIMATE", raw)

        assertEquals(
            listOf(
                SensorPlace("Спальня", "sensor.bed_t", "sensor.bed_h", "sensor.bed_p"),
                SensorPlace("Кухня", "sensor.kitchen_t", "sensor.kitchen_h"),
                SensorPlace("Улица", "sensor.out_t"),
            ),
            parse(" Спальня = sensor.bed_t + sensor.bed_h + sensor.bed_p , Кухня=sensor.kitchen_t+sensor.kitchen_h, Улица=sensor.out_t "),
            "spaces are noise, and humidity and pressure are optional",
        )
        assertEquals(emptyList(), parse(null))

        assertFailsWith<IllegalArgumentException>("no name") { parse("sensor.bed_t") }
        val tooMany = assertFailsWith<IllegalArgumentException>("four entities") {
            parse("Спальня=sensor.a+sensor.b+sensor.c+sensor.d")
        }
        assertTrue("SC_HA_CLIMATE" in tooMany.message.orEmpty(), "the error names the variable to fix: ${tooMany.message}")
        assertFailsWith<IllegalArgumentException>("an id that would escape the path") { parse("Спальня=../config") }
    }
}
