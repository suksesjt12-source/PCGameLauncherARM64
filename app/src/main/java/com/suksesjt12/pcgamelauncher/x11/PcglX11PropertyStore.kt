package com.suksesjt12.pcgamelauncher.x11

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
        maxBytes: Int
    ): ChangeResult {
        if (format !in setOf(8, 16, 32) || mode !in 0..2) return ChangeResult.FORMAT_MISMATCH
        val properties = windows[window]
        val previous = properties?.get(propertyAtom)
        if (mode != 0 && previous != null) {
            if (previous.format != format) return ChangeResult.FORMAT_MISMATCH
            if (previous.type != typeAtom) return ChangeResult.TYPE_MISMATCH
        }

        val previousValue = previous?.value ?: ByteArray(0)
        val combinedSize = previousValue.size.toLong() + newValue.size.toLong()
        val outputSize = if (mode == 0) newValue.size.toLong() else combinedSize
        if (outputSize > maxBytes.toLong()) return ChangeResult.TOO_LARGE

        val combined = when (mode) {
            0 -> newValue.copyOf()
            1 -> newValue + previousValue
            2 -> previousValue + newValue
            else -> return ChangeResult.FORMAT_MISMATCH
        }
        windows.getOrPut(window) { HashMap() }[propertyAtom] =
            Property(typeAtom, format, combined)
        return ChangeResult.SUCCESS
    }

    @Synchronized
    fun get(window: Int, propertyAtom: Int): Property? {
        val property = windows[window]?.get(propertyAtom) ?: return null
        return property.copy(value = property.value.copyOf())
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
