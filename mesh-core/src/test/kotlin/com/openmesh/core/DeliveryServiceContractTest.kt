package com.openmesh.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Modifier

class DeliveryServiceContractTest {

    @Test
    fun `application can replace delivery implementation with a platform-free fake`() = runBlocking {
        val destination = EndpointId("dtn://example.test/inbox/alice")
        val payload = "hello".encodeToByteArray()
        val policy = DeliveryPolicy(
            lifetimeMs = 60_000,
            priority = DeliveryPolicy.Priority.HIGH,
            contentType = "text/plain",
        )
        var captured: Triple<EndpointId, ByteArray, DeliveryPolicy>? = null
        val fake = DeliveryService { requestedDestination, requestedPayload, requestedPolicy ->
            captured = Triple(requestedDestination, requestedPayload.copyOf(), requestedPolicy)
            DeliveryHandle(
                requestId = DeliveryHandle.RequestId("fake-request-1"),
                observations = flowOf(DeliveryHandle.Observation.DurablyStoredLocally),
            )
        }

        val handle = fake.deliver(destination, payload, policy)

        assertEquals(destination, captured?.first)
        assertArrayEquals(payload, captured?.second)
        assertEquals(policy, captured?.third)
        assertEquals("fake-request-1", handle.requestId.value)
        assertEquals(
            DeliveryHandle.Observation.DurablyStoredLocally,
            handle.observations.first(),
        )
    }

    @Test
    fun `delivery policy contains intent and no transport selector`() {
        val instanceFields = DeliveryPolicy::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapTo(mutableSetOf()) { it.name }

        assertEquals(
            setOf("lifetimeMs", "priority", "desiredEvidence", "contentType"),
            instanceFields,
        )
    }

    @Test
    fun `endpoint and cryptographic node identifiers are different domains`() {
        val node = NodeId("om1-11111111111111111111111111111111")
        val endpoint = EndpointId("dtn://example.test/inbox/device-1")

        assertNotEquals(EndpointId::class.java, NodeId::class.java)
        assertNotEquals(endpoint.value, node.value)
        assertThrows(IllegalArgumentException::class.java) {
            EndpointId(node.value)
        }
    }

    @Test
    fun `policy rejects invalid lifetime and content type`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeliveryPolicy(lifetimeMs = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeliveryPolicy(contentType = "   ")
        }
    }
}
