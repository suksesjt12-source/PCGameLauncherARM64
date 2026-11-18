package com.suksesjt12.pcgamelauncher.x11

import java.nio.ByteOrder

/** Shared X11 window-property storage. A single server store is visible to all clients. */
internal class PcglX11PropertyStore {
    data class Property(val type: Int, val format: Int, val value: ByteArray)

    enum class ChangeResult {
        SUCCESS,
        TYPE_MISMATCH,
        FORMAT_MISMATCH,
        TOO_LARGE
    }

    private val windows = HashMap<Int, MutableMap<Int, Property>>()

    @Synchronized
    fun change(
        window: Int,
        propertyAtom: Int,
        typeAtom: Int,
        format: Int,
        mode: Int,
        newValue: ByteArray,
        maxBytes: Int,
        clientByteOrder: ByteOrder = ByteOrder.BIG_ENDIAN
    ): ChangeResult {
        if (format !in setOf(8, 16, 32) || mode !in 0..2) return ChangeResult.FORMAT_MISMATCH
        val bytesPerItem = format / 8
        if (newValue.size % bytesPerItem != 0) return ChangeResult.FORMAT_MISMATCH
        val properties = windows[window]
        val previous = properties?.get(propertyAtom)
        if (mode != 0 && previous != null) {
            if (previous.format != format) return ChangeResult.FORMAT_MISMATCH
            if (previous.type != typeAtom) return ChangeResult.TYPE_MISMATCH
        }

        val previousValue = previous?.value ?: ByteArray(0)
        val canonicalNewValue = convertByteOrder(newValue, format, clientByteOrder, ByteOrder.BIG_ENDIAN)
        val combinedSize = previousValue.size.toLong() + canonicalNewValue.size.toLong()
        val outputSize = if (mode == 0) canonicalNewValue.size.toLong() else combinedSize
        if (outputSize > maxBytes.toLong()) return ChangeResult.TOO_LARGE

        val combined = when (mode) {
            0 -> canonicalNewValue
            1 -> canonicalNewValue + previousValue
            2 -> previousValue + canonicalNewValue
            else -> return ChangeResult.FORMAT_MISMATCH
        }
        windows.getOrPut(window) { HashMap() }[propertyAtom] =
            Property(typeAtom, format, combined)
        return ChangeResult.SUCCESS
    }

    @Synchronized
    fun get(
        window: Int,
        propertyAtom: Int,
        clientByteOrder: ByteOrder = ByteOrder.BIG_ENDIAN
    ): Property? {
        val property = windows[window]?.get(propertyAtom) ?: return null
        val clientValue = convertByteOrder(property.value, property.format, ByteOrder.BIG_ENDIAN, clientByteOrder)
        return property.copy(value = clientValue)
    }

    /** Properties are stored in canonical big-endian order and converted for each client. */
    private fun convertByteOrder(
        value: ByteArray,
        format: Int,
        sourceOrder: ByteOrder,
        destinationOrder: ByteOrder
    ): ByteArray {
        if (format == 8 || sourceOrder == destinationOrder) return value.copyOf()
        val itemSize = format / 8
        if (value.size % itemSize != 0) return value.copyOf()
        return ByteArray(value.size).also { converted ->
            var offset = 0
            while (offset < value.size) {
                for (index in 0 until itemSize) {
                    converted[offset + index] = value[offset + itemSize - index - 1]
                }
                offset += itemSize
            }
        }
    }

    @Synchronized
    fun remove(window: Int, propertyAtom: Int): Boolean {
        val properties = windows[window] ?: return false
        val removed = properties.remove(propertyAtom) != null
        if (properties.isEmpty()) windows.remove(window)
        return removed
    }

    @Synchronized
    fun atomIds(window: Int): List<Int> = windows[window]?.keys?.toList().orEmpty()

    @Synchronized
    fun removeWindow(window: Int) {
        windows.remove(window)
    }
}
