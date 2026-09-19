package dev.simplecalendar.integrations.weather

import dev.simplecalendar.integrations.Integration
import dev.simplecalendar.integrations.IntegrationContext
import kotlinx.serialization.Serializable

/**
 * The weather as the wall shows it, whoever the forecast came from.
 *
 * Provider codes and units stop at the integration: everything here is °C, %, km/h, household
 * days, and conditions named in Home Assistant's vocabulary. That is what lets the same strip on
 * the wall be fed by Open-Meteo or by OpenWeatherMap without the frontend knowing which.
 */
@Serializable
data class Weather(
    val current: CurrentWeather,
    /** Today first. How many days follow depends on the provider — five at worst. */
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
    /** Mean relative humidity over the day, 0–100. */
    val humidity: Int? = null,
    /** The highest chance of precipitation in any hour of the day, 0–100. */
    val precipitationProbability: Int?,
)

/** Stable: the frontend asks for `/api/integrations/weather` whichever provider answers. */
const val WEATHER_ID = "weather"

/**
 * The household's weather source: OpenWeatherMap when there is a key for it, Open-Meteo otherwise.
 *
 * Both need only the coordinates of the house; the key is the whole of the choice. Open-Meteo
 * stays the default because it needs no account and reaches further ahead, and OpenWeatherMap is
 * there for a household that already has a key and would rather have one provider for everything.
 */
fun weatherIntegration(context: IntegrationContext): Integration<Weather>? {
    val env = context.env
    val latitude = env.double("SC_WEATHER_LATITUDE")
    val longitude = env.double("SC_WEATHER_LONGITUDE")
    if (latitude == null && longitude == null) return null

    requireNotNull(latitude) { "SC_WEATHER_LONGITUDE is set, but SC_WEATHER_LATITUDE is missing" }
    requireNotNull(longitude) { "SC_WEATHER_LATITUDE is set, but SC_WEATHER_LONGITUDE is missing" }
    require(latitude in -90.0..90.0) { "SC_WEATHER_LATITUDE must be between -90 and 90, got $latitude" }
    require(longitude in -180.0..180.0) { "SC_WEATHER_LONGITUDE must be between -180 and 180, got $longitude" }

    val apiKey = env.string("SC_OWM_API_KEY")
    return if (apiKey != null) {
        OpenWeatherMapWeather(context.http, latitude, longitude, apiKey, context.zone)
    } else {
        OpenMeteoWeather(context.http, latitude, longitude, context.zone)
    }
}
