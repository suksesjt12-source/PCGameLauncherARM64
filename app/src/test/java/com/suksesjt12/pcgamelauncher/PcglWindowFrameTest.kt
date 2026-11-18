package com.suksesjt12.pcgamelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcglWindowFrameTest {
    @Test
    fun acceptsTightlyPackedRgbaFrame() {
        val frame = PcglWindowFrame(2, 2, ByteArray(16))
        assertEquals(2, frame.width)
        assertEquals(2, frame.height)
    }

    @Test
    fun rejectsBufferWithWrongLength() {
        assertThrows(IllegalArgumentException::class.java) {
            PcglWindowFrame(2, 2, ByteArray(15))
        }
    }

    @Test
    fun rejectsDimensionsBeyondBackendLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            PcglWindowFrame(4097, 1, ByteArray(4))
        }
    }
}
