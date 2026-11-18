package com.suksesjt12.pcgamelauncher

/**
 * CPU frame supplied by a future X11 drawable/window bridge.
 * Pixels are tightly packed RGBA8888, row-major, with the first row at the top.
 * The JNI call copies the bytes before returning.
 */
data class PcglWindowFrame(
    val width: Int,
    val height: Int,
    val rgbaPixels: ByteArray
) {
    init {
        require(width in 1..MAX_DIMENSION) { "Frame width must be between 1 and 4096 pixels." }
        require(height in 1..MAX_DIMENSION) { "Frame height must be between 1 and 4096 pixels." }
        val expectedBytes = width.toLong() * height.toLong() * BYTES_PER_PIXEL
        require(expectedBytes <= MAX_FRAME_BYTES) { "Frame exceeds the 64 MiB upload limit." }
        require(rgbaPixels.size.toLong() == expectedBytes) {
            "RGBA buffer length does not match frame dimensions."
        }
    }

    companion object {
        const val BYTES_PER_PIXEL = 4L
        const val MAX_DIMENSION = 4096
        const val MAX_FRAME_BYTES = 64L * 1024L * 1024L
    }
}
