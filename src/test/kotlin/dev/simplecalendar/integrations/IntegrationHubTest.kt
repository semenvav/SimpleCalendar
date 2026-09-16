package dev.simplecalendar.integrations

import dev.simplecalendar.plugins.UpstreamException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class IntegrationHubTest {

    @Serializable
    data class Counter(val value: Int)

    /** Succeeds or fails on command. */
    private class Scripted(override val id: String = "scripted") : Integration<Counter> {
        var next: Result<Int> = Result.success(1)
        override val endpoint = "http://scripted.test"
        override val refreshEvery = 5.minutes
        override val snapshotSerializer = Counter.serializer()
        override suspend fun fetch() = Counter(next.getOrThrow())
    }

    @Test
    fun `a failed fetch keeps the last good snapshot and says why`() = runBlocking {
        val integration = Scripted()
        val hub = IntegrationHub(listOf(integration))

        assertEquals(true, hub.refresh(integration))
        val first = assertNotNull(hub.status(integration).snapshot)
        assertEquals(1, first.data.jsonObject["value"]?.jsonPrimitive?.int)

        integration.next = Result.failure(UpstreamException("Open-Meteo answered 503"))
        assertEquals(false, hub.refresh(integration))
        val status = hub.status(integration)
        assertEquals(first, status.snapshot, "stale weather beats no weather")
        assertEquals("Open-Meteo answered 503", status.lastError)

        integration.next = Result.success(2)
        hub.refresh(integration)
        assertNull(hub.status(integration).lastError, "a success clears the error")
        assertEquals(2, hub.status(integration).snapshot?.data?.jsonObject?.get("value")?.jsonPrimitive?.int)
    }

    @Test
    fun `nothing is shown before the first success`() = runBlocking {
        val integration = Scripted().apply { next = Result.failure(IllegalStateException("no route to host")) }
        val hub = IntegrationHub(listOf(integration))
        hub.refresh(integration)
        assertNull(hub.status(integration).snapshot)
        assertEquals("http://scripted.test: no route to host", hub.status(integration).lastError, "the address it could not reach")
    }

    @Test
    fun `two integrations cannot share an id`() {
        assertFailsWith<IllegalArgumentException> { IntegrationHub(listOf(Scripted("same"), Scripted("same"))) }
    }

    @Test
    fun `nothing configured means no integrations`() {
        assertEquals(emptyList(), configuredIntegrations(contextWith()))
    }

    @Test
    fun `base urls are taken as people paste them`() {
        assertEquals("http://immich:2283", normaliseBaseUrl("http://immich:2283/"))
        assertEquals("http://immich:2283", normaliseBaseUrl("http://immich:2283/api/"))
        assertEquals("https://ha.example.com", normaliseBaseUrl("https://ha.example.com/api"))
    }
}
