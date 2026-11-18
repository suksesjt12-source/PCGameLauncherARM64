package com.suksesjt12.pcgamelauncher.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcglX11AtomRegistryTest {
    @Test
    fun predefinedAtomsUseTheirFixedX11Ids() {
        val registry = PcglX11AtomRegistry()
        assertEquals(1, registry.intern("PRIMARY", onlyIfExists = false))
        assertEquals(4, registry.intern("ATOM", onlyIfExists = false))
        assertEquals(33, registry.intern("WINDOW", onlyIfExists = false))
        assertEquals(39, registry.intern("WM_NAME", onlyIfExists = false))
        assertEquals(67, registry.intern("WM_CLASS", onlyIfExists = false))
        assertEquals(68, registry.intern("WM_TRANSIENT_FOR", onlyIfExists = false))
    }

    @Test
    fun dynamicAtomsStartAfterThePredefinedRangeAndRoundTrip() {
        val registry = PcglX11AtomRegistry()
        val atom = registry.intern("_NET_WM_NAME", onlyIfExists = false)
        assertEquals(69, atom)
        assertEquals("_NET_WM_NAME", registry.name(atom))
    }

    @Test
    fun unknownAtomIsNotCreatedWhenOnlyIfExistsIsSet() {
        val registry = PcglX11AtomRegistry()
        assertEquals(0, registry.intern("_PCGL_UNKNOWN", onlyIfExists = true))
        assertNull(registry.name(69))
        assertEquals(69, registry.intern("_PCGL_UNKNOWN", onlyIfExists = false))
    }

    @Test
    fun atomIdsAreStableAcrossAllClientsUsingTheServerRegistry() {
        val serverRegistry = PcglX11AtomRegistry()
        val firstClientId = serverRegistry.intern("WM_PROTOCOLS", onlyIfExists = false)
        val secondClientId = serverRegistry.intern("WM_PROTOCOLS", onlyIfExists = false)
        assertEquals(firstClientId, secondClientId)
        assertEquals(69, secondClientId)
        assertTrue(serverRegistry.name(secondClientId) == "WM_PROTOCOLS")
    }

    @Test
    fun unknownAtomIdHasNoName() {
        assertNull(PcglX11AtomRegistry().name(0x12345678))
    }
}
