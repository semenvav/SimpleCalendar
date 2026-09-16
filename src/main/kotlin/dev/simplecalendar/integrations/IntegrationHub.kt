package dev.simplecalendar.integrations

import dev.simplecalendar.net.OutgoingProxy
import dev.simplecalendar.plugins.UpstreamException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The last good result of an integration, already in the shape the API sends. */
data class Snapshot(val data: JsonElement, val fetchedAt: Instant)

data class IntegrationStatus(
    val id: String,
    val refreshEvery: Duration,
    val snapshot: Snapshot?,
    /** Why the latest fetch failed; null once one succeeds again. */
    val lastError: String?,
)

/**
 * Runs every configured integration on its own schedule and keeps what each fetched last.
 *
 * A failed fetch never clears the snapshot: yesterday's forecast on the wall is better than an
 * empty corner, and the snapshot carries its time for anyone who wants to show its age. After a
 * failure the next try comes sooner than the normal interval, so a restarted service or proxy is
 * picked up within a minute.
 *
 * Snapshots live in memory only. A restart costs one fetch per integration, done straight away.
 */
class IntegrationHub(val integrations: List<Integration<*>>) : AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("integrations"))

    private class State {
        @Volatile var snapshot: Snapshot? = null
        @Volatile var lastError: String? = null
    }

    private val states: Map<String, State> = integrations.associate { it.id to State() }

    init {
        val duplicate = integrations.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicate.isEmpty()) { "Integration ids must be unique: $duplicate" }
    }

    fun start() {
        if (integrations.isEmpty()) {
            log.info("No integrations configured")
            return
        }
        for (integration in integrations) {
            log.info(
                "Integration '{}': {} ({}), every {}",
                integration.id, integration.endpoint, OutgoingProxy.describeRoute(integration.endpoint),
                integration.refreshEvery,
            )
            scope.launch(CoroutineName("integration-${integration.id}")) {
                while (isActive) {
                    val ok = refresh(integration)
                    delay(if (ok) integration.refreshEvery else minOf(integration.refreshEvery, RETRY_AFTER))
                }
            }
        }
    }

    /** Runs one fetch now. Returns whether it succeeded. */
    suspend fun refresh(integration: Integration<*>): Boolean {
        val state = states.getValue(integration.id)
        return try {
            state.snapshot = Snapshot(fetchEncoded(integration), Instant.now())
            if (state.lastError != null) log.info("Integration '{}' recovered", integration.id)
            state.lastError = null
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = describe(integration, e)
            // Once per distinct problem: a service down all night would otherwise log every minute.
            if (message != state.lastError) log.warn("Integration '{}' failed: {}", integration.id, message)
            state.lastError = message
            false
        }
    }

    fun status(): List<IntegrationStatus> = integrations.map { status(it) }

    fun status(integration: Integration<*>): IntegrationStatus {
        val state = states.getValue(integration.id)
        return IntegrationStatus(integration.id, integration.refreshEvery, state.snapshot, state.lastError)
    }

    override fun close() {
        // The HTTP client belongs to the owner, who closes it after this.
        scope.cancel()
    }

    /**
     * A service that answered already names itself in the message. Anything else — no route, a
     * name that does not resolve, a proxy refusing the tunnel, a body we could not read — often
     * says only `UnresolvedAddressException`, so it gets the address it was about.
     */
    private fun describe(integration: Integration<*>, error: Exception): String {
        val detail = error.message ?: error::class.simpleName ?: "failed"
        return if (error is UpstreamException) detail else "${integration.endpoint}: $detail"
    }

    private suspend fun <T : Any> fetchEncoded(integration: Integration<T>): JsonElement =
        integrationJson.encodeToJsonElement(integration.snapshotSerializer, integration.fetch())

    private companion object {
        val RETRY_AFTER = 1.minutes
    }
}
