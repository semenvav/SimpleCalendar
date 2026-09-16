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
) : Integration<HomeAssistantStates> {

    private val log = LoggerFactory.getLogger(javaClass)

    override val id = "homeassistant"
    override val endpoint get() = baseUrl
    override val refreshEvery = 1.minutes
    override val snapshotSerializer = HomeAssistantStates.serializer()

    override suspend fun fetch(): HomeAssistantStates {
        val entities = mutableListOf<EntityState>()
        val missing = mutableListOf<String>()

        // One request per entity rather than all of `/api/states`: a real installation has
        // thousands of entities and we want a handful.
        for (entityId in entityIds) {
            val response = http.get("$baseUrl/api/states/$entityId") { bearerAuth(token) }
            if (response.status == HttpStatusCode.NotFound) {
                missing += entityId
                continue
            }
            response.requireOk("Home Assistant")
            entities += integrationJson.decodeFromString<StateResponse>(response.bodyAsText()).toEntity()
        }

        if (missing.isNotEmpty()) log.debug("Home Assistant does not know {}", missing)
        return HomeAssistantStates(entities, missing)
    }

    companion object {
        /** `domain.object_id`, as Home Assistant validates them; also keeps the id safe inside a URL path. */
        private val ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")

        fun fromEnv(context: IntegrationContext): HomeAssistantIntegration? {
            val env = context.env
            val url = env.string("SC_HA_URL") ?: return null
            val entityIds = env.list("SC_HA_ENTITIES")
            require(entityIds.isNotEmpty()) {
                "SC_HA_URL is set, but SC_HA_ENTITIES is missing: list the entities to show, e.g. sensor.living_room_temperature"
            }
            val invalid = entityIds.filterNot(ENTITY_ID::matches)
            require(invalid.isEmpty()) { "SC_HA_ENTITIES: not entity ids: $invalid" }

            return HomeAssistantIntegration(
                http = context.http,
                baseUrl = normaliseBaseUrl(url),
                token = env.required("SC_HA_TOKEN", because = "SC_HA_URL is set"),
                entityIds = entityIds.distinct(),
            )
        }
    }
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
