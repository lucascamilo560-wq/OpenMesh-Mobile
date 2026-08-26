package com.openmesh.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshTransportEventTest {

    @Test
    fun `successful GATT write produces only LinkWriteCompleted`() {
        val event = transportEventForSuccessfulGattWrite(
            result = BleGattSendResult.Success(frameCount = 4),
            packetId = "packet-1",
            peerNodeId = "om1-11111111111111111111111111111111",
            peerIsDestination = true,
        )

        assertEquals(
            MeshTransportEvent.LinkWriteCompleted(
                packetId = "packet-1",
                peerNodeId = "om1-11111111111111111111111111111111",
                peerIsDestination = true,
                frameCount = 4,
            ),
            event,
        )
    }

    @Test
    fun `transport runtime defines no durable acceptance or delivery evidence`() {
        val runtimeEventTypes = MeshTransportEvent::class.java.declaredClasses
            .filter { MeshTransportEvent::class.java.isAssignableFrom(it) }
            .mapTo(mutableSetOf()) { it.simpleName }

        assertEquals(setOf("LinkWriteCompleted", "SendFailed"), runtimeEventTypes)
        assertFalse("NextHopAcceptedDurably" in runtimeEventTypes)
        assertFalse("DestinationStored" in runtimeEventTypes)
        assertFalse("AppDelivered" in runtimeEventTypes)
        assertTrue("LinkWriteCompleted" in runtimeEventTypes)
    }
}
