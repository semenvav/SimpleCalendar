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
    /** The places of `SC_HA_SENSORS`, in the order they were listed. */
    val sensors: List<SensorReading> = emptyList(),
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
 * One place in the house, as the wall names it, with its two readings.
 *
 * A Home Assistant sensor entity carries a single number, so "the bedroom" is two entities. Which
 * two only the household can say — `SC_HA_SENSORS` is where it is written down — and so is the
 * name, because «Спальня» reads better on a wall than «Bedroom Temperature Sensor».
 */
@Serializable
data class SensorReading(
    val name: String,
    /** `null` when the entity is unavailable, unknown, or simply not a number. */
    val temperature: Double? = null,
    val temperatureUnit: String? = null,
    val humidity: Double? = null,
    val humidityUnit: String? = null,
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
    private val sensors: List<SensorPair> = emptyList(),
) : Integration<HomeAssistantStates> {

    private val log = LoggerFactory.getLogger(javaClass)

    override val id = "homeassistant"
    override val endpoint get() = baseUrl
    override val refreshEvery = 1.minutes
    override val snapshotSerializer = HomeAssistantStates.serializer()

    /** Everything worth asking for: what was listed, plus whatever the sensor pairs need. */
    private val wanted: List<String> =
        (entityIds + sensors.flatMap { listOfNotNull(it.temperatureId, it.humidityId) }).distinct()

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
        return HomeAssistantStates(
            entities = entityIds.mapNotNull(fetched::get),
            missing = missing,
            // A pair whose entities are all missing still gets its line, empty: a place that has
            // disappeared from the wall is harder to notice than one showing dashes.
            sensors = sensors.map { it.read(fetched) },
        )
    }

    companion object {
        /** `domain.object_id`, as Home Assistant validates them; also keeps the id safe inside a URL path. */
        private val ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")

        fun fromEnv(context: IntegrationContext): HomeAssistantIntegration? {
            val env = context.env
            val url = env.string("SC_HA_URL") ?: return null
            val entityIds = env.list("SC_HA_ENTITIES")
            val sensors = parseSensors(env.string("SC_HA_SENSORS"))
            require(entityIds.isNotEmpty() || sensors.isNotEmpty()) {
                "SC_HA_URL is set, but neither SC_HA_ENTITIES nor SC_HA_SENSORS is: say what to show, " +
                    "e.g. SC_HA_SENSORS=Спальня=sensor.bedroom_temperature+sensor.bedroom_humidity"
            }
            val invalid = entityIds.filterNot(ENTITY_ID::matches)
            require(invalid.isEmpty()) { "SC_HA_ENTITIES: not entity ids: $invalid" }

            return HomeAssistantIntegration(
                http = context.http,
                baseUrl = normaliseBaseUrl(url),
                token = env.required("SC_HA_TOKEN", because = "SC_HA_URL is set"),
                entityIds = entityIds.distinct(),
                sensors = sensors,
            )
        }

        /**
         * Reads `SC_HA_SENSORS`: `Спальня=sensor.bedroom_temperature+sensor.bedroom_humidity`,
         * comma-separated, the humidity half optional.
         *
         * Pairs are spelled out rather than guessed from device classes or from friendly names.
         * Guessing works right up until somebody renames a sensor in Home Assistant, and then the
         * wall quietly shows the wrong room — which is exactly the kind of error nobody spots.
         */
        internal fun parseSensors(raw: String?): List<SensorPair> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(',').map(String::trim).filter(String::isNotEmpty).map { item ->
                val separator = item.indexOf('=')
                require(separator > 0) { "SC_HA_SENSORS: «$item» is not name=entity[+entity]" }

                val name = item.take(separator).trim()
                val ids = item.substring(separator + 1).split('+').map(String::trim).filter(String::isNotEmpty)
                require(name.isNotEmpty()) { "SC_HA_SENSORS: «$item» has no name" }
                require(ids.size in 1..2) { "SC_HA_SENSORS: «$item» must name one or two entities" }

                val invalid = ids.filterNot(ENTITY_ID::matches)
                require(invalid.isEmpty()) { "SC_HA_SENSORS: not entity ids: $invalid" }
                SensorPair(name, ids[0], ids.getOrNull(1))
            }
        }
    }
}

/** A place on the wall and the entities behind its numbers, as `SC_HA_SENSORS` spells them out. */
data class SensorPair(val name: String, val temperatureId: String, val humidityId: String?) {

    /** How the pair reads now; an entity that is missing or not a number reads as null. */
    fun read(states: Map<String, EntityState>): SensorReading {
        val temperature = states[temperatureId]
        val humidity = humidityId?.let(states::get)
        return SensorReading(
            name = name,
            temperature = temperature?.number(),
            temperatureUnit = temperature?.unit,
            humidity = humidity?.number(),
            humidityUnit = humidity?.unit,
        )
    }

    /** Home Assistant keeps every state as text, and `unavailable` is one of the values it sends. */
    private fun EntityState.number(): Double? = state.trim().toDoubleOrNull()
}

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
