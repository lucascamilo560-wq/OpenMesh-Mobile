package com.openmesh.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WifiDirectUpgradeOfferCodecTest {

    @Test
    fun `upgrade offer round trips without losing credentials`() {
        val offer = WifiDirectUpgradeOffer(
            sessionId = UUID.randomUUID().toString(),
            credentials = WifiDirectCredentials(
                networkName = "DIRECT-OM-test1234",
                passphrase = "temporary-passphrase-123",
                port = 38651,
            ),
            expiresAtMs = 123_456_789L,
        )

        val decoded = WifiDirectUpgradeOfferCodec.decode(
            WifiDirectUpgradeOfferCodec.encode(offer)
        )

        assertEquals(offer, decoded)
    }

    @Test
    fun `generated credentials satisfy Wi-Fi Direct constraints`() {
        repeat(32) {
            val credentials = WifiDirectCredentials.generate()
            assertTrue(credentials.networkName.matches(Regex("DIRECT-[A-Za-z0-9]{2}.*")))
            assertTrue(credentials.passphrase.length in 8..63)
            assertTrue(credentials.port in 1..65535)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid network name is rejected`() {
        WifiDirectCredentials(
            networkName = "OPENMESH-invalid",
            passphrase = "12345678",
        )
    }
}
