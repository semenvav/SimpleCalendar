package dev.simplecalendar.integrations.weather

import dev.simplecalendar.integrations.Integration
import dev.simplecalendar.integrations.IntegrationContext
import dev.simplecalendar.integrations.integrationJson
import dev.simplecalendar.integrations.requireOk
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/** Current conditions and the days ahead, in °C, % and km/h. */
@Serializable
data class Weather(
    val current: CurrentWeather,
    /** Today first, [FORECAST_DAYS] days in all. */
    val days: List<DayWeather>,
)

@Serializable
data class CurrentWeather(
    /** Household wall-clock time the values are for, `2026-09-16T11:45`. */
    val time: String,
    /** See [condition]. */
    val condition: String?,
    val temperature: Double?,
    val feelsLike: Double?,
    val humidity: Int?,
    val windSpeed: Double?,
)

@Serializable
data class DayWeather(
    /** `YYYY-MM-DD`, a day in the household zone. */
    val date: String,
    val condition: String?,
    val temperatureMax: Double?,
    val temperatureMin: Double?,
    /** The highest chance of precipitation in any hour of the day, 0–100. */
    val precipitationProbability: Int?,
)

/**
 * The forecast from Open-Meteo: free, no key, and good enough for "do the kids need a jacket".
 *
 * Provider codes stop here. Conditions are named in Home Assistant's vocabulary, so the frontend
 * draws `rainy` rather than WMO code 61, and another source could stand behind the same shape.
 */
class WeatherIntegration(
    private val http: HttpClient,
    private val latitude: Double,
    private val longitude: Double,
    private val zone: ZoneId,
    private val baseUrl: String = OPEN_METEO,
) : Integration<Weather> {

    override val id = "weather"
    override val endpoint get() = baseUrl
    override val refreshEvery = 30.minutes
    override val snapshotSerializer = Weather.serializer()

    override suspend fun fetch(): Weather {
        val response = http.get("$baseUrl/v1/forecast") {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            // Days in the household zone, so "tomorrow" means what it means on the wall.
            parameter("timezone", zone.id)
            parameter("forecast_days", FORECAST_DAYS)
            parameter(
                "current",
                "temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code,is_day",
            )
            parameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
        }.requireOk("Open-Meteo")

        return integrationJson.decodeFromString<Forecast>(response.bodyAsText()).toWeather()
    }

    companion object {
        const val OPEN_METEO = "https://api.open-meteo.com"

        /** The most Open-Meteo gives. */
        const val FORECAST_DAYS = 16

        fun fromEnv(context: IntegrationContext): WeatherIntegration? {
            val env = context.env
            val latitude = env.double("SC_WEATHER_LATITUDE")
            val longitude = env.double("SC_WEATHER_LONGITUDE")
            if (latitude == null && longitude == null) return null

            requireNotNull(latitude) { "SC_WEATHER_LONGITUDE is set, but SC_WEATHER_LATITUDE is missing" }
            requireNotNull(longitude) { "SC_WEATHER_LATITUDE is set, but SC_WEATHER_LONGITUDE is missing" }
            require(latitude in -90.0..90.0) { "SC_WEATHER_LATITUDE must be between -90 and 90, got $latitude" }
            require(longitude in -180.0..180.0) { "SC_WEATHER_LONGITUDE must be between -180 and 180, got $longitude" }

            return WeatherIntegration(context.http, latitude, longitude, context.zone)
        }
    }
}

/**
 * WMO weather interpretation codes, as Open-Meteo reports them, in Home Assistant's condition
 * names. Intensity mostly folds away — a wall shows an icon, not a meteorological report — except
 * where it changes what to wear: heavy rain is `pouring`, a thunderstorm with hail is `hail`.
 */
internal fun condition(code: Int?, isDay: Boolean = true): String? = when (code) {
    0, 1 -> if (isDay) "sunny" else "clear-night"
    2 -> "partlycloudy"
    3 -> "cloudy"
    45, 48 -> "fog"
    51, 53, 55, 56, 57, 61, 63, 66, 80 -> "rainy"
    65, 67, 81, 82 -> "pouring"
    71, 73, 75, 77, 85, 86 -> "snowy"
    95 -> "lightning-rainy"
    96, 99 -> "hail"
    else -> null
}

// --- Open-Meteo's response, only the parts we read ---------------------------------------------

@Serializable
private class Forecast(val current: Current, val daily: Daily)

@Serializable
private class Current(
    val time: String,
    @SerialName("temperature_2m") val temperature: Double?,
    @SerialName("apparent_temperature") val feelsLike: Double?,
    @SerialName("relative_humidity_2m") val humidity: Int?,
    @SerialName("wind_speed_10m") val windSpeed: Double?,
    @SerialName("weather_code") val code: Int?,
    @SerialName("is_day") val isDay: Int?,
)

/** Column-wise, as Open-Meteo sends it: the n-th value of every list is for the n-th day. */
@Serializable
private class Daily(
    val time: List<String>,
    @SerialName("weather_code") val code: List<Int?> = emptyList(),
    @SerialName("temperature_2m_max") val max: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val min: List<Double?> = emptyList(),
    @SerialName("precipitation_probability_max") val precipitation: List<Int?> = emptyList(),
)

private fun Forecast.toWeather() = Weather(
    current = CurrentWeather(
        time = current.time,
        condition = condition(current.code, isDay = current.isDay != 0),
        temperature = current.temperature,
        feelsLike = current.feelsLike,
        humidity = current.humidity,
        windSpeed = current.windSpeed,
    ),
    days = daily.time.mapIndexed { i, date ->
        DayWeather(
            date = date,
            condition = condition(daily.code.getOrNull(i)),
            temperatureMax = daily.max.getOrNull(i),
            temperatureMin = daily.min.getOrNull(i),
            precipitationProbability = daily.precipitation.getOrNull(i),
        )
    },
)
