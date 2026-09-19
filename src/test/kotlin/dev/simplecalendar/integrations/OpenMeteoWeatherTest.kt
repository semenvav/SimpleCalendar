package dev.simplecalendar.integrations

import dev.simplecalendar.integrations.weather.OpenMeteoWeather
import dev.simplecalendar.integrations.weather.OpenWeatherMapWeather
import dev.simplecalendar.integrations.weather.condition
import dev.simplecalendar.integrations.weather.weatherIntegration
import dev.simplecalendar.plugins.UpstreamException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenMeteoWeatherTest {

    @Test
    fun `reads Open-Meteo's forecast into days and current conditions`() {
        var query = ""
        withFakeService(
            routes = {
                get("/v1/forecast") {
                    query = URLDecoder.decode(call.request.uri, Charsets.UTF_8)
                    call.respondText(fixture("open-meteo-forecast.json"), ContentType.Application.Json)
                }
            },
        ) { baseUrl, context ->
            val weather = OpenMeteoWeather(context.http, 52.52, 13.41, context.zone, baseUrl).fetch()

            assertTrue("latitude=52.52" in query && "longitude=13.41" in query, query)
            assertTrue("timezone=Asia/Jerusalem" in query, "days must be household days: $query")
            assertTrue("forecast_days=16" in query, query)

            with(weather.current) {
                assertEquals("2026-09-16T23:45", time)
                assertEquals("clear-night", condition, "a clear sky after dark is not sunny")
                assertEquals(21.4, temperature)
                assertEquals(20.9, feelsLike)
                assertEquals(78, humidity)
                assertEquals(6.1, windSpeed)
            }

            assertEquals(16, weather.days.size)
            with(weather.days[0]) {
                assertEquals("2026-09-16", date)
                assertEquals("sunny", condition, "daily conditions are always the daytime ones")
                assertEquals(31.2, temperatureMax)
                assertEquals(22.5, temperatureMin)
                assertEquals(64, humidity)
                assertEquals(0, precipitationProbability)
            }
            assertEquals(listOf("sunny", "pouring", "hail"), weather.days.take(3).map { it.condition })
            assertNull(weather.days.last().precipitationProbability, "Open-Meteo sends null far ahead")
            assertNull(weather.days.last().humidity)
        }
    }

    @Test
    fun `Open-Meteo's reason for refusing ends up in the error`() {
        withFakeService(
            routes = {
                get("/v1/forecast") {
                    call.respondText(
                        """{"error":true,"reason":"Latitude must be in range of -90 to 90°. Given: 100.0."}""",
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                }
            },
        ) { baseUrl, context ->
            val error = assertFailsWith<UpstreamException> {
                OpenMeteoWeather(context.http, 1.0, 1.0, context.zone, baseUrl).fetch()
            }
            assertTrue("400" in error.message.orEmpty() && "Latitude must be" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `every WMO code Open-Meteo documents has a condition`() {
        val documented = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        assertEquals(emptyList(), documented.filter { condition(it) == null })
        assertNull(condition(null))
    }

    @Test
    fun `settings`() {
        assertNull(weatherIntegration(contextWith()))

        val coordinates = arrayOf("SC_WEATHER_LATITUDE" to "32.08", "SC_WEATHER_LONGITUDE" to "34.78")
        assertIs<OpenMeteoWeather>(weatherIntegration(contextWith(*coordinates)), "no key means no account needed")

        // A key is the whole of the choice; both providers serve the same `/api/integrations/weather`.
        val withKey = assertNotNull(weatherIntegration(contextWith(*coordinates, "SC_OWM_API_KEY" to "k")))
        assertIs<OpenWeatherMapWeather>(withKey)
        assertEquals("weather", withKey.id)

        assertFailsWith<IllegalArgumentException> { weatherIntegration(contextWith("SC_WEATHER_LATITUDE" to "32.08")) }
        assertFailsWith<IllegalArgumentException> {
            weatherIntegration(contextWith("SC_WEATHER_LATITUDE" to "132", "SC_WEATHER_LONGITUDE" to "34"))
        }
        // Coordinates copied with a comma decimal separator are a mistake, not a zero.
        assertFailsWith<IllegalStateException> {
            weatherIntegration(contextWith("SC_WEATHER_LATITUDE" to "32,08", "SC_WEATHER_LONGITUDE" to "34.78"))
        }
    }
}
