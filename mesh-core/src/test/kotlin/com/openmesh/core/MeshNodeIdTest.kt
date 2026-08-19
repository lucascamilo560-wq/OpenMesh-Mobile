package com.openmesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshNodeIdTest {

    @Test
    fun `advertisement bytes round trip exact routable node id`() {
        val identity = MeshCrypto.generateKeyPair()
        val nodeId = identity.nodeId
        val compact = MeshNodeId.toAdvertisementBytes(nodeId)

        assertEquals(MeshNodeId.DIGEST_BYTES, compact.size)
        assertEquals(nodeId, MeshNodeId.fromAdvertisementBytes(compact))
        assertTrue(MeshNodeId.isValid(nodeId))
    }

    @Test
    fun `legacy arbitrary ids are rejected`() {
        assertFalse(MeshNodeId.isValid("demo-random-id"))
    }
}
