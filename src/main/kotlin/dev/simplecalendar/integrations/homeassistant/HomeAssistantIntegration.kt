package dev.simplecalendar.integrations.homeassistant

import dev.simplecalendar.integrations.Integration
import dev.simplecalendar.integrations.IntegrationContext
import dev.simplecalendar.integrations.integrationJson
import dev.simplecalendar.integrations.normaliseBaseUrl
import dev.simplecalendar.integrations.requireOk
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

@Serializable
data class HomeAssistantStates(
    /** In the order they were configured. */
    val entities: List<EntityState>,
    /** Configured ids Home Assistant does not know — a typo, or a renamed entity. */
    val missing: List<String> = emptyList(),
    /** The places of `SC_HA_SENSORS`, in the order they were listed: the strip in the toolbar. */
    val sensors: List<SensorReading> = emptyList(),
    /**
     * The places of `SC_HA_CLIMATE`, in the order they were listed: the climate window, which has
     * room for every sensor in the house. The strip's places while that is not set.
     */
    val climate: List<SensorReading> = emptyList(),
    /**
     * The air pressure, hPa: the mean of every place in [climate] whose pressure reads. One number
     * for the house — it is the weather's pressure, and each sensor is only a little off it.
     */
    val pressure: Double? = null,
)

@Serializable
data class EntityState(
    val entityId: String,
    /** Home Assistant's friendly name; the id when it has none. */
    val name: String,
    /** Always text, as Home Assistant keeps it: `"23.4"`, `"on"`, `"home"`, `"unavailable"`. */
    val state: String,
    val unit: String?,
    val deviceClass: String?,
    val lastChanged: String?,
    /** Everything else Home Assistant reports, untouched, so the UI can grow without the server. */
    val attributes: JsonObject,
)

/**
 * One place in the house, as the wall names it, with its readings.
 *
 * A Home Assistant sensor entity carries a single number, so "the bedroom" is up to three
 * entities — usually of one device. Which ones only the household can say — `SC_HA_SENSORS` and
 * `SC_HA_CLIMATE` are where it is written down — and so is the name, because «Спальня» reads
 * better on a wall than «Bedroom Temperature Sensor».
 */
@Serializable
data class SensorReading(
    val name: String,
    /** `null` when the entity is unavailable, unknown, or simply not a number. */
    val temperature: Double? = null,
    val temperatureUnit: String? = null,
    val humidity: Double? = null,
    val humidityUnit: String? = null,
    /** In whatever unit Home Assistant shows it; [HomeAssistantStates.pressure] is the one in hPa. */
    val pressure: Double? = null,
    val pressureUnit: String? = null,
)

/**
 * The state of chosen Home Assistant entities — the temperature indoors, who is home, whether the
 * washing machine is done.
 *
 * Plain REST polling, once a minute: the wall is not a control panel, and a minute-old reading is
 * as good as a live one there. The websocket API would add a connection to babysit for nothing
 * anybody would notice.
 */
class HomeAssistantIntegration(
    private val http: HttpClient,
    private val baseUrl: String,
    private val token: String,
    private val entityIds: List<String>,
    private val sensors: List<SensorPlace> = emptyList(),
    private val climate: List<SensorPlace> = emptyList(),
) : Integration<HomeAssistantStates> {

    private val log = LoggerFactory.getLogger(javaClass)

    override val id = "homeassistant"
    override val endpoint get() = baseUrl
    override val refreshEvery = 1.minutes
    override val snapshotSerializer = HomeAssistantStates.serializer()

    /** Everything worth asking for: what was listed, plus whatever the places need. */
    private val wanted: List<String> = (entityIds + (sensors + climate).flatMap { it.entityIds }).distinct()

    override suspend fun fetch(): HomeAssistantStates {
        val fetched = mutableMapOf<String, EntityState>()
        val missing = mutableListOf<String>()

        // One request per entity rather than all of `/api/states`: a real installation has
        // thousands of entities and we want a handful.
        for (entityId in wanted) {
            val response = http.get("$baseUrl/api/states/$entityId") { bearerAuth(token) }
            if (response.status == HttpStatusCode.NotFound) {
                missing += entityId
                continue
            }
            response.requireOk("Home Assistant")
            fetched[entityId] = integrationJson.decodeFromString<StateResponse>(response.bodyAsText()).toEntity()
        }

        if (missing.isNotEmpty()) log.debug("Home Assistant does not know {}", missing)
        // A place whose entities are all missing still gets its line, empty: a place that has
        // disappeared from the wall is harder to notice than one showing dashes.
        val everyPlace = climate.map { it.read(fetched) }
        return HomeAssistantStates(
            entities = entityIds.mapNotNull(fetched::get),
            missing = missing,
            sensors = sensors.map { it.read(fetched) },
            climate = everyPlace,
            pressure = meanPressure(everyPlace),
        )
    }

    companion object {
        /** `domain.object_id`, as Home Assistant validates them; also keeps the id safe inside a URL path. */
        private val ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")

        fun fromEnv(context: IntegrationContext): HomeAssistantIntegration? {
            val env = context.env
            val url = env.string("SC_HA_URL") ?: return null
            val entityIds = env.list("SC_HA_ENTITIES")
            val sensors = parseSensors("SC_HA_SENSORS", env.string("SC_HA_SENSORS"))
            val climate = parseSensors("SC_HA_CLIMATE", env.string("SC_HA_CLIMATE"))
            require(entityIds.isNotEmpty() || sensors.isNotEmpty() || climate.isNotEmpty()) {
                "SC_HA_URL is set, but none of SC_HA_SENSORS, SC_HA_CLIMATE and SC_HA_ENTITIES is: say what " +
                    "to show, e.g. SC_HA_SENSORS=Спальня=sensor.bedroom_temperature+sensor.bedroom_humidity"
            }
            val invalid = entityIds.filterNot(ENTITY_ID::matches)
            require(invalid.isEmpty()) { "SC_HA_ENTITIES: not entity ids: $invalid" }

            return HomeAssistantIntegration(
                http = context.http,
                baseUrl = normaliseBaseUrl(url),
                token = env.required("SC_HA_TOKEN", because = "SC_HA_URL is set"),
                entityIds = entityIds.distinct(),
                sensors = sensors,
                // Until the window has a list of its own, the strip's places are the ones there are.
                climate = climate.ifEmpty { sensors },
            )
        }

        /**
         * Reads a list of places, `SC_HA_SENSORS` or `SC_HA_CLIMATE`:
         * `Спальня=sensor.bedroom_temperature+sensor.bedroom_humidity+sensor.bedroom_pressure`,
         * comma-separated. Temperature, humidity, pressure — in that order, the last two optional.
         *
         * Places are spelled out rather than guessed from device classes or from friendly names.
         * Guessing works right up until somebody renames a sensor in Home Assistant, and then the
         * wall quietly shows the wrong room — which is exactly the kind of error nobody spots.
         */
        internal fun parseSensors(variable: String, raw: String?): List<SensorPlace> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(',').map(String::trim).filter(String::isNotEmpty).map { item ->
                val separator = item.indexOf('=')
                require(separator > 0) { "$variable: «$item» is not name=temperature[+humidity[+pressure]]" }

                val name = item.take(separator).trim()
                val ids = item.substring(separator + 1).split('+').map(String::trim).filter(String::isNotEmpty)
                require(name.isNotEmpty()) { "$variable: «$item» has no name" }
                require(ids.size in 1..3) { "$variable: «$item» must name one to three entities" }

                val invalid = ids.filterNot(ENTITY_ID::matches)
                require(invalid.isEmpty()) { "$variable: not entity ids: $invalid" }
                SensorPlace(name, ids[0], ids.getOrNull(1), ids.getOrNull(2))
            }
        }
    }
}

/**
 * A place on the wall and the entities behind its numbers, as `SC_HA_SENSORS` and `SC_HA_CLIMATE`
 * spell them out.
 */
data class SensorPlace(
    val name: String,
    val temperatureId: String,
    val humidityId: String? = null,
    val pressureId: String? = null,
) {
    val entityIds: List<String> get() = listOfNotNull(temperatureId, humidityId, pressureId)

    /** How the place reads now; an entity that is missing or not a number reads as null. */
    fun read(states: Map<String, EntityState>): SensorReading {
        val temperature = states[temperatureId]
        val humidity = humidityId?.let(states::get)
        val pressure = pressureId?.let(states::get)
        return SensorReading(
            name = name,
            temperature = temperature?.number(),
            temperatureUnit = temperature?.unit,
            humidity = humidity?.number(),
            humidityUnit = humidity?.unit,
            pressure = pressure?.number(),
            pressureUnit = pressure?.unit,
        )
    }

    /** Home Assistant keeps every state as text, and `unavailable` is one of the values it sends. */
    private fun EntityState.number(): Double? = state.trim().toDoubleOrNull()
}

/**
 * The mean of the pressures that read, in hPa; null when none does.
 *
 * Each is converted first: Home Assistant reports a sensor in whatever unit it was set to show,
 * and the mean of 1008 hPa and 756 mmHg is neither. A unit it does not offer is left out rather
 * than guessed at.
 */
internal fun meanPressure(places: List<SensorReading>): Double? =
    places.mapNotNull { place ->
        val factor = place.pressureUnit?.let(HECTOPASCALS::get)
        if (place.pressure == null || factor == null) null else place.pressure * factor
    }.takeIf { it.isNotEmpty() }?.average()

/** Every pressure unit Home Assistant offers, in hectopascals — the millibars the wall shows. */
private val HECTOPASCALS = mapOf(
    "Pa" to 0.01,
    "hPa" to 1.0,
    "mbar" to 1.0,
    "kPa" to 10.0,
    "cbar" to 10.0,
    "bar" to 1000.0,
    "mmHg" to 1.333_223_874,
    "inHg" to 33.863_886_67,
    "psi" to 68.947_572_93,
)

@Serializable
private class StateResponse(
    @SerialName("entity_id") val entityId: String,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
    @SerialName("last_changed") val lastChanged: String?,
)

private fun StateResponse.toEntity(): EntityState {
    fun attribute(name: String) = runCatching { attributes[name]?.jsonPrimitive?.contentOrNull }.getOrNull()
    return EntityState(
        entityId = entityId,
        name = attribute("friendly_name") ?: entityId,
        state = state,
        unit = attribute("unit_of_measurement"),
        deviceClass = attribute("device_class"),
        lastChanged = lastChanged,
        attributes = attributes,
    )
}
