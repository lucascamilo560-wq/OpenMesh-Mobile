package com.openmesh.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class BleFrameCodecCompatibilityTest {

    @Test
    fun `v1 BLE framing remains byte exact`() {
        val golden = goldenFrames()
        val payload = byteArrayOf(0x4f, 0x4d, 0x53, 0x48)

        val encoded = BleFrameCodec.chunk(
            payload = payload,
            maxFrameBytes = 15,
            transferId = 0x0102030405060708L,
        )

        assertEquals(2, encoded.size)
        assertArrayEquals(golden[0], encoded[0])
        assertArrayEquals(golden[1], encoded[1])

        val assembler = BleFrameAssembler()
        assertNull(assembler.accept(golden[1], nowMs = 1_000))
        assertArrayEquals(payload, assembler.accept(golden[0], nowMs = 1_001))
    }

    @Test
    fun `v1 decoder rejects truncated frame`() {
        assertThrows(IllegalArgumentException::class.java) {
            BleFrameCodec.decode(goldenFrames()[0].copyOf(BleFrameCodec.HEADER_SIZE - 1))
        }
    }

    @Test
    fun `v1 decoder rejects unsupported version`() {
        val malformed = goldenFrames()[0].also { it[0] = 2 }

        assertThrows(IllegalArgumentException::class.java) {
            BleFrameCodec.decode(malformed)
        }
    }

    @Test
    fun `v1 decoder rejects zero total frames`() {
        val malformed = goldenFrames()[0].also {
            it[11] = 0
            it[12] = 0
        }

        assertThrows(IllegalArgumentException::class.java) {
            BleFrameCodec.decode(malformed)
        }
    }

    @Test
    fun `v1 chunker rejects payload requiring more than unsigned short frames`() {
        assertThrows(IllegalArgumentException::class.java) {
            BleFrameCodec.chunk(
                payload = ByteArray(UShort.MAX_VALUE.toInt() + 1),
                maxFrameBytes = BleFrameCodec.HEADER_SIZE + 1,
                transferId = 1,
            )
        }
    }

    @Test
    fun `assembler reconstructs one payload from concurrent out of order frames`() = runBlocking {
        val payload = ByteArray(4_096) { (it % 251).toByte() }
        val frames = BleFrameCodec.chunk(
            payload = payload,
            maxFrameBytes = 64,
            transferId = 42,
        ).shuffled(Random(20_260_826))
        val assembler = BleFrameAssembler()
        val start = CompletableDeferred<Unit>()
        val attempts = frames.map { frame ->
            async(Dispatchers.Default) {
                start.await()
                assembler.accept(frame, nowMs = 1_000)
            }
        }

        start.complete(Unit)
        val completed = attempts.awaitAll().filterNotNull()

        assertEquals(1, completed.size)
        assertArrayEquals(payload, completed.single())
    }

    private fun goldenFrames(): List<ByteArray> {
        val resource = checkNotNull(javaClass.getResource("/golden/ble-frames-v1.hex"))
        return resource.readText()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::decodeHex)
            .toList()
    }

    private fun decodeHex(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
