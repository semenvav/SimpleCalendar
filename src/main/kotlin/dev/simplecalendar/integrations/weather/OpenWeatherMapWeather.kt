package dev.simplecalendar.integrations.weather

import dev.simplecalendar.integrations.Integration
import dev.simplecalendar.integrations.integrationJson
import dev.simplecalendar.integrations.requireOk
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes

/**
 * The same forecast from OpenWeatherMap, for a household that already has a key there.
 *
 * Two calls to the free endpoints — current conditions and the five-day, three-hourly forecast —
 * rather than One Call 3.0, which needs a paid subscription even at its free allowance. The
 * three-hourly readings are folded into household days here, so the snapshot is the same [Weather]
 * Open-Meteo produces and the wall cannot tell them apart. The price is reach: five days, not
 * sixteen.
 */
class OpenWeatherMapWeather(
    private val http: HttpClient,
    private val latitude: Double,
    private val longitude: Double,
    private val apiKey: String,
    private val zone: ZoneId,
    baseUrl: String = OPEN_WEATHER_MAP,
) : Integration<Weather> {

    private val base = baseUrl.trimEnd('/')

    override val id = WEATHER_ID
    override val endpoint get() = base
    override val refreshEvery = 30.minutes
    override val snapshotSerializer = Weather.serializer()

    override suspend fun fetch(): Weather {
        val now = integrationJson.decodeFromString<CurrentResponse>(request("weather"))
        val forecast = integrationJson.decodeFromString<ForecastResponse>(request("forecast"))
        return Weather(current = now.toCurrent(zone), days = forecast.toDays(zone))
    }

    private suspend fun request(path: String): String =
        http.get("$base/data/2.5/$path") {
            parameter("lat", latitude)
            parameter("lon", longitude)
            // Celsius; wind then comes in m/s, which we turn into km/h like every other source.
            parameter("units", "metric")
            parameter("appid", apiKey)
        }.requireOk("OpenWeatherMap").bodyAsText()

    companion object {
        const val OPEN_WEATHER_MAP = "https://api.openweathermap.org"
    }
}

/**
 * OpenWeatherMap's condition codes in Home Assistant's vocabulary, the same one Open-Meteo's
 * codes are folded into (see [condition]).
 *
 * `771` squall and `781` tornado have no milder equivalent and become `exceptional`: whatever the
 * wall draws for it, it must not read as ordinary weather.
 */
internal fun owmCondition(code: Int?, isDay: Boolean = true): String? = when (code) {
    null -> null
    in 200..232 -> "lightning-rainy"
    in 300..321 -> "rainy"
    502, 503, 504, 511, 522 -> "pouring"
    in 500..531 -> "rainy"
    in 600..622 -> "snowy"
    771, 781 -> "exceptional"
    in 701..762 -> "fog"
    800 -> if (isDay) "sunny" else "clear-night"
    801, 802 -> "partlycloudy"
    803, 804 -> "cloudy"
    else -> null
}

// --- OpenWeatherMap's responses, only the parts we read ----------------------------------------

@Serializable
private class CurrentResponse(
    /** Seconds since the epoch, UTC, whatever the city's own zone is. */
    val dt: Long,
    val weather: List<Condition> = emptyList(),
    val main: Readings? = null,
    val wind: Wind? = null,
)

@Serializable
private class ForecastResponse(val list: List<Slot> = emptyList())

@Serializable
private class Slot(
    val dt: Long,
    val weather: List<Condition> = emptyList(),
    val main: Readings? = null,
    /** Chance of precipitation for the three hours, 0–1. */
    val pop: Double? = null,
)

@Serializable
private class Condition(
    val id: Int? = null,
    /** Ends in `d` or `n`: the only thing in the answer that says whether the sun was up. */
    val icon: String? = null,
)

@Serializable
private class Readings(
    val temp: Double? = null,
    @SerialName("feels_like") val feelsLike: Double? = null,
    @SerialName("temp_min") val tempMin: Double? = null,
    @SerialName("temp_max") val tempMax: Double? = null,
    val humidity: Int? = null,
)

@Serializable
private class Wind(val speed: Double? = null)

private val WALL_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

/** m/s, as OpenWeatherMap reports it with `units=metric`, into the km/h the snapshot promises. */
private fun Double?.toKmh(): Double? = this?.let { (it * 3.6 * 10).roundToInt() / 10.0 }

private fun CurrentResponse.toCurrent(zone: ZoneId) = CurrentWeather(
    time = Instant.ofEpochSecond(dt).atZone(zone).format(WALL_CLOCK),
    condition = owmCondition(weather.firstOrNull()?.id, isDay = weather.firstOrNull()?.icon?.endsWith("n") != true),
    temperature = main?.temp,
    feelsLike = main?.feelsLike,
    humidity = main?.humidity,
    windSpeed = wind?.speed.toKmh(),
)

/**
 * Folds the three-hourly slots into household days.
 *
 * The day's condition is taken from the slot nearest midday rather than from the worst of them:
 * a shower at four in the morning should not make the whole day rainy on the wall. Its first day
 * is only the part of today that is still ahead — which is why "today" on screen comes from
 * [CurrentWeather], not from here.
 */
private fun ForecastResponse.toDays(zone: ZoneId): List<DayWeather> =
    list.groupBy { Instant.ofEpochSecond(it.dt).atZone(zone).toLocalDate() }
        .toSortedMap()
        .map { (date, slots) -> slots.toDay(date, zone) }

private fun List<Slot>.toDay(date: LocalDate, zone: ZoneId): DayWeather {
    val midday = minByOrNull { slot ->
        val at = Instant.ofEpochSecond(slot.dt).atZone(zone).toLocalDateTime()
        abs(Duration.between(LocalDateTime.of(date, LocalTime.NOON), at).toMinutes())
    }
    val humidity = mapNotNull { it.main?.humidity }

    return DayWeather(
        date = date.toString(),
        condition = owmCondition(midday?.weather?.firstOrNull()?.id, isDay = true),
        temperatureMax = mapNotNull { it.main?.tempMax ?: it.main?.temp }.maxOrNull(),
        temperatureMin = mapNotNull { it.main?.tempMin ?: it.main?.temp }.minOrNull(),
        humidity = humidity.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        precipitationProbability = mapNotNull { it.pop }.maxOrNull()?.let { (it * 100).roundToInt() },
    )
}
