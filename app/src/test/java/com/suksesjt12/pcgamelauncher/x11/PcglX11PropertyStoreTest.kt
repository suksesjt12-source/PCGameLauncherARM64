package com.suksesjt12.pcgamelauncher.x11

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcglX11PropertyStoreTest {
    @Test
    fun propertyWrittenByOneClientIsVisibleToAnotherClientThroughServerStore() {
        val serverStore = PcglX11PropertyStore()
        val value = byteArrayOf(10, 20, 30, 40)
        assertEquals(
            PcglX11PropertyStore.ChangeResult.SUCCESS,
            serverStore.change(100, 39, 31, 8, 0, value, 1024)
        )
        val secondClientRead = serverStore.get(100, 39)
        assertEquals(31, secondClientRead?.type)
        assertEquals(8, secondClientRead?.format)
        assertArrayEquals(value, secondClientRead?.value)
    }

    @Test
    fun replaceMayChangePropertyTypeAndFormat() {
        val store = PcglX11PropertyStore()
        assertEquals(
            PcglX11PropertyStore.ChangeResult.SUCCESS,
            store.change(100, 39, 31, 8, 0, byteArrayOf(1, 2), 1024)
        )
        assertEquals(
            PcglX11PropertyStore.ChangeResult.SUCCESS,
            store.change(100, 39, 19, 32, 0, byteArrayOf(3, 4, 5, 6), 1024)
        )
        val property = store.get(100, 39)!!
        assertEquals(19, property.type)
        assertEquals(32, property.format)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), property.value)
    }

    @Test
    fun appendAndPrependRequireMatchingTypeAndFormat() {
        val store = PcglX11PropertyStore()
        assertEquals(
            PcglX11PropertyStore.ChangeResult.SUCCESS,
            store.change(100, 39, 31, 8, 0, byteArrayOf(2, 3), 1024)
        )
        assertEquals(
            PcglX11PropertyStore.ChangeResult.SUCCESS,
            store.change(100, 39, 31, 8, 2, byteArrayOf(4), 1024)
        )
        assertEquals(
            PcglX11PropertyStore.ChangeResult.SUCCESS,
            store.change(100, 39, 31, 8, 1, byteArrayOf(1), 1024)
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), store.get(100, 39)!!.value)
        assertEquals(
            PcglX11PropertyStore.ChangeResult.TYPE_MISMATCH,
            store.change(100, 39, 19, 8, 2, byteArrayOf(5), 1024)
        )
        assertEquals(
            PcglX11PropertyStore.ChangeResult.FORMAT_MISMATCH,
            store.change(100, 39, 31, 16, 2, byteArrayOf(5, 6), 1024)
        )
    }

    @Test
    fun propertyStorageIsSeparatedByWindowAndCanBeListedOrDeleted() {
        val store = PcglX11PropertyStore()
        store.change(100, 39, 31, 8, 0, byteArrayOf(1), 1024)
        store.change(101, 39, 31, 8, 0, byteArrayOf(2), 1024)
        store.change(100, 40, 31, 8, 0, byteArrayOf(3), 1024)
        assertEquals(setOf(39, 40), store.atomIds(100).toSet())
        assertArrayEquals(byteArrayOf(2), store.get(101, 39)!!.value)
        assertTrue(store.remove(100, 39))
        assertFalse(store.remove(100, 39))
        assertNull(store.get(100, 39))
        store.removeWindow(100)
        assertTrue(store.atomIds(100).isEmpty())
        assertTrue(store.atomIds(101).contains(39))
    }

    @Test
    fun propertyGrowthIsBounded() {
        val store = PcglX11PropertyStore()
        assertEquals(
            PcglX11PropertyStore.ChangeResult.SUCCESS,
            store.change(100, 39, 31, 8, 0, byteArrayOf(1, 2), 4)
        )
        assertEquals(
            PcglX11PropertyStore.ChangeResult.TOO_LARGE,
            store.change(100, 39, 31, 8, 2, byteArrayOf(3, 4, 5), 4)
        )
        assertArrayEquals(byteArrayOf(1, 2), store.get(100, 39)!!.value)
    }

    @Test
    fun readsReturnDefensiveCopies() {
        val store = PcglX11PropertyStore()
        store.change(100, 39, 31, 8, 0, byteArrayOf(7, 8), 1024)
        val firstRead = store.get(100, 39)!!
        firstRead.value[0] = 99
        assertArrayEquals(byteArrayOf(7, 8), store.get(100, 39)!!.value)
    }
    @Test
    fun propertyValuesAreNormalizedForBigEndianStorageAndPerClientReads() {
        val store = PcglX11PropertyStore()
        val littleEndian = byteArrayOf(0x34, 0x12, 0x78, 0x56)
        assertEquals(
            PcglX11PropertyStore.ChangeResult.SUCCESS,
            store.change(100, 39, 4, 16, 0, littleEndian, 1024, java.nio.ByteOrder.LITTLE_ENDIAN)
        )
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0x56, 0x78),
            store.get(100, 39, java.nio.ByteOrder.BIG_ENDIAN)!!.value
        )
        assertArrayEquals(
            littleEndian,
            store.get(100, 39, java.nio.ByteOrder.LITTLE_ENDIAN)!!.value
        )
    }

    @Test
    fun format32ValuesAreSwappedAsCompleteWords() {
        val store = PcglX11PropertyStore()
        val littleEndian = byteArrayOf(4, 3, 2, 1, 8, 7, 6, 5)
        store.change(
            100, 39, 6, 32, 0, littleEndian, 1024,
            java.nio.ByteOrder.LITTLE_ENDIAN
        )
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            store.get(100, 39, java.nio.ByteOrder.BIG_ENDIAN)!!.value
        )
        assertArrayEquals(
            littleEndian,
            store.get(100, 39, java.nio.ByteOrder.LITTLE_ENDIAN)!!.value
        )
    }

    @Test
    fun malformedWordPayloadIsRejected() {
        val store = PcglX11PropertyStore()
        assertEquals(
            PcglX11PropertyStore.ChangeResult.FORMAT_MISMATCH,
            store.change(100, 39, 6, 16, 0, byteArrayOf(1), 1024)
        )
    }
}
