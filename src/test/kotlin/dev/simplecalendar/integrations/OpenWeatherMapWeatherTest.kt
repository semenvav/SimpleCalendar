package dev.simplecalendar.integrations

import dev.simplecalendar.integrations.weather.OpenWeatherMapWeather
import dev.simplecalendar.integrations.weather.owmCondition
import dev.simplecalendar.plugins.UpstreamException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenWeatherMapWeatherTest {

    private val key = "owm-key"

    @Test
    fun `folds three-hourly slots into household days`() {
        val queries = mutableListOf<String>()
        withFakeService(
            routes = {
                get("/data/2.5/{path}") {
                    queries += call.request.uri
                    when (call.parameters["path"]) {
                        "weather" -> call.respondText(fixture("owm-current.json"), ContentType.Application.Json)
                        else -> call.respondText(fixture("owm-forecast.json"), ContentType.Application.Json)
                    }
                }
            },
        ) { baseUrl, context ->
            val weather = OpenWeatherMapWeather(context.http, 32.08, 34.78, key, context.zone, baseUrl).fetch()

            assertTrue(queries.all { "appid=$key" in it && "units=metric" in it }, "$queries")

            with(weather.current) {
                assertEquals("2026-09-16T23:45", time, "the reading's own moment, on the household clock")
                assertEquals("clear-night", condition, "the icon's 'n' is what says the sun was down")
                assertEquals(21.4, temperature)
                assertEquals(20.9, feelsLike)
                assertEquals(78, humidity)
                assertEquals(6.1, windSpeed, "1.7 m/s, in the km/h every other source reports")
            }

            // The slots span two household days, and it is the household zone that decides which:
            // the first slot is 21:00 UTC, which is already the 17th in Jerusalem.
            assertEquals(listOf("2026-09-17", "2026-09-18"), weather.days.map { it.date })

            with(weather.days[0]) {
                assertEquals(29.7, temperatureMax)
                assertEquals(19.6, temperatureMin)
                assertEquals(73, humidity, "the mean over the day, not the reading at any one hour")
                assertEquals(35, precipitationProbability, "the likeliest three hours of the day")
                assertEquals("partlycloudy", condition, "midday decides, not the rain before dawn")
            }
            with(weather.days[1]) {
                assertEquals(31.4, temperatureMax)
                assertEquals(21.6, temperatureMin)
                assertEquals(62, humidity)
                assertEquals(60, precipitationProbability)
                assertEquals("lightning-rainy", condition)
            }
        }
    }

    @Test
    fun `a rejected key fails the fetch with what OpenWeatherMap said`() {
        withFakeService(
            routes = {
                get("/data/2.5/{path}") {
                    call.respondText(
                        """{"cod":401,"message":"Invalid API key."}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Unauthorized,
                    )
                }
            },
        ) { baseUrl, context ->
            val error = assertFailsWith<UpstreamException> {
                OpenWeatherMapWeather(context.http, 32.08, 34.78, "stale", context.zone, baseUrl).fetch()
            }
            assertTrue("401" in error.message.orEmpty() && "Invalid API key" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `every condition group OpenWeatherMap documents has a name`() {
        val documented = listOf(
            200, 201, 202, 210, 211, 212, 221, 230, 231, 232,
            300, 301, 302, 310, 311, 312, 313, 314, 321,
            500, 501, 502, 503, 504, 511, 520, 521, 522, 531,
            600, 601, 602, 611, 612, 613, 615, 616, 620, 621, 622,
            701, 711, 721, 731, 741, 751, 761, 762, 771, 781,
            800, 801, 802, 803, 804,
        )
        assertEquals(emptyList(), documented.filter { owmCondition(it) == null })
        assertNull(owmCondition(null))

        assertEquals("sunny", owmCondition(800, isDay = true))
        assertEquals("clear-night", owmCondition(800, isDay = false))
        assertEquals("pouring", owmCondition(503), "heavy rain is not the same coat as a shower")
        assertEquals("exceptional", owmCondition(781), "a tornado must not read as ordinary weather")
    }
}
