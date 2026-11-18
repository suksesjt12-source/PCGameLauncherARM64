package com.suksesjt12.pcgamelauncher.x11

import com.suksesjt12.pcgamelauncher.PcglWindowFrame
import java.nio.ByteOrder

/**
 * Small software X11 drawable store for the PCGL core protocol path.
 *
 * Supports 24-bit TrueColor ZPixmap data carried as 32 bits per pixel. It is a
 * compatibility bridge for core X11 drawing, not an XRender/GLX/DRI3 server.
 */
internal class PcglX11Framebuffer(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val frameSink: (PcglWindowFrame) -> Unit
) {
    data class Geometry(val x: Int, val y: Int, val width: Int, val height: Int, val depth: Int)

    private data class Drawable(
        val id: Int,
        val parentId: Int,
        var x: Int,
        var y: Int,
        var width: Int,
        var height: Int,
        val depth: Int,
        val isWindow: Boolean,
        var mapped: Boolean,
        var pixels: ByteArray
    )

    private val drawables = LinkedHashMap<Int, Drawable>()
    private val graphicsContexts = HashSet<Int>()

    init {
        require(screenWidth in 1..4096 && screenHeight in 1..4096)
        require(screenWidth.toLong() * screenHeight * 4L <= MAX_FRAME_BYTES)
    }

    @Synchronized
    fun hasWindow(id: Int): Boolean = id == PcglX11SetupCodec.ROOT_WINDOW_ID ||
        drawables[id]?.isWindow == true

    @Synchronized
    fun hasDrawable(id: Int): Boolean =
        id == PcglX11SetupCodec.ROOT_WINDOW_ID || drawables.containsKey(id)

    @Synchronized
    fun isMapped(id: Int): Boolean =
        id == PcglX11SetupCodec.ROOT_WINDOW_ID || drawables[id]?.mapped == true

    @Synchronized
    fun parentOf(id: Int): Int? = drawables[id]?.takeIf { it.isWindow }?.parentId

    @Synchronized
    fun geometry(id: Int): Geometry? {
        if (id == PcglX11SetupCodec.ROOT_WINDOW_ID) {
            return Geometry(0, 0, screenWidth, screenHeight, 24)
        }
        val drawable = drawables[id] ?: return null
        return Geometry(drawable.x, drawable.y, drawable.width, drawable.height, drawable.depth)
    }

    @Synchronized
    fun children(parentId: Int): IntArray =
        drawables.values.filter { it.isWindow && it.parentId == parentId }.map { it.id }.toIntArray()

    @Synchronized
    fun createWindow(
        id: Int,
        parentId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        depth: Int
    ): Boolean {
        if (id == 0 || id == PcglX11SetupCodec.ROOT_WINDOW_ID || drawables.containsKey(id)) return false
        if (!hasWindow(parentId) || width !in 1..4096 || height !in 1..4096) return false
        val actualDepth = if (depth == 0) 24 else depth
        if (actualDepth != 24 || !validAllocation(width, height)) return false
        drawables[id] = Drawable(
            id, parentId, x, y, width, height, actualDepth, true, false,
            ByteArray(width * height * 4)
        )
        return true
    }

    @Synchronized
    fun createPixmap(id: Int, width: Int, height: Int, depth: Int): Boolean {
        if (id == 0 || id == PcglX11SetupCodec.ROOT_WINDOW_ID || drawables.containsKey(id)) return false
        if (depth != 24 || width !in 1..4096 || height !in 1..4096 || !validAllocation(width, height)) return false
        drawables[id] = Drawable(
            id, PcglX11SetupCodec.ROOT_WINDOW_ID, 0, 0, width, height, depth, false, false,
            ByteArray(width * height * 4)
        )
        return true
    }

    @Synchronized
    fun createGraphicsContext(id: Int): Boolean =
        id != 0 && graphicsContexts.add(id)

    @Synchronized
    fun freeGraphicsContext(id: Int): Boolean = graphicsContexts.remove(id)

    @Synchronized
    fun freePixmap(id: Int): Boolean {
        val drawable = drawables[id] ?: return false
        if (drawable.isWindow) return false
        drawables.remove(id)
        return true
    }

    @Synchronized
    fun mapWindow(id: Int): Boolean {
        val drawable = drawables[id]?.takeIf { it.isWindow } ?: return false
        drawable.mapped = true
        publishFrame()
        return true
    }

    @Synchronized
    fun unmapWindow(id: Int): Boolean {
        val drawable = drawables[id]?.takeIf { it.isWindow } ?: return false
        drawable.mapped = false
        publishFrame()
        return true
    }

    @Synchronized
    fun destroyWindow(id: Int): Boolean {
        val drawable = drawables[id]?.takeIf { it.isWindow } ?: return false
        destroyChildren(id)
        drawables.remove(id)
        publishFrame()
        return true
    }

    private fun destroyChildren(parentId: Int) {
        val children = drawables.values.filter { it.isWindow && it.parentId == parentId }.map { it.id }
        children.forEach { child ->
            destroyChildren(child)
            drawables.remove(child)
        }
    }

    @Synchronized
    fun configureWindow(
        id: Int,
        x: Int?,
        y: Int?,
        width: Int?,
        height: Int?
    ): Boolean {
        val drawable = drawables[id]?.takeIf { it.isWindow } ?: return false
        val nextWidth = width ?: drawable.width
        val nextHeight = height ?: drawable.height
        if (nextWidth !in 1..4096 || nextHeight !in 1..4096 || !validAllocation(nextWidth, nextHeight)) return false
        drawable.x = x ?: drawable.x
        drawable.y = y ?: drawable.y
        if (nextWidth != drawable.width || nextHeight != drawable.height) {
            val next = ByteArray(nextWidth * nextHeight * 4)
            val copyWidth = minOf(drawable.width, nextWidth)
            val copyHeight = minOf(drawable.height, nextHeight)
            for (row in 0 until copyHeight) {
                System.arraycopy(
                    drawable.pixels, row * drawable.width * 4,
                    next, row * nextWidth * 4,
                    copyWidth * 4
                )
            }
            drawable.width = nextWidth
            drawable.height = nextHeight
            drawable.pixels = next
        }
        publishFrame()
        return true
    }

    @Synchronized
    fun putImage(
        drawableId: Int,
        gcId: Int,
        format: Int,
        width: Int,
        height: Int,
        destinationX: Int,
        destinationY: Int,
        depth: Int,
        data: ByteArray,
        byteOrder: ByteOrder
    ): Boolean {
        val drawable = drawables[drawableId] ?: return false
        if (!graphicsContexts.contains(gcId) || format != Z_PIXMAP || depth != 24) return false
        if (width !in 1..4096 || height !in 1..4096) return false
        val stride = align4(width * 4)
        val required = stride.toLong() * height
        if (required > MAX_FRAME_BYTES || data.size < required) return false

        for (sourceY in 0 until height) {
            val targetY = destinationY + sourceY
            if (targetY !in 0 until drawable.height) continue
            for (sourceX in 0 until width) {
                val targetX = destinationX + sourceX
                if (targetX !in 0 until drawable.width) continue
                val source = sourceY * stride + sourceX * 4
                val target = (targetY * drawable.width + targetX) * 4
                if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
                    drawable.pixels[target] = data[source + 2]
                    drawable.pixels[target + 1] = data[source + 1]
                    drawable.pixels[target + 2] = data[source]
                } else {
                    drawable.pixels[target] = data[source + 1]
                    drawable.pixels[target + 1] = data[source + 2]
                    drawable.pixels[target + 2] = data[source + 3]
                }
                drawable.pixels[target + 3] = 0xff.toByte()
            }
        }
        if (drawable.isWindow) publishFrame()
        return true
    }

    @Synchronized
    fun copyArea(
        sourceId: Int,
        destinationId: Int,
        gcId: Int,
        sourceX: Int,
        sourceY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int
    ): Boolean {
        val source = drawables[sourceId] ?: return false
        val destination = drawables[destinationId] ?: return false
        if (!graphicsContexts.contains(gcId) || width !in 1..4096 || height !in 1..4096) return false
        val snapshot = source.pixels.copyOf()
        for (row in 0 until height) {
            val sy = sourceY + row
            val dy = destinationY + row
            if (sy !in 0 until source.height || dy !in 0 until destination.height) continue
            for (column in 0 until width) {
                val sx = sourceX + column
                val dx = destinationX + column
                if (sx !in 0 until source.width || dx !in 0 until destination.width) continue
                val from = (sy * source.width + sx) * 4
                val to = (dy * destination.width + dx) * 4
                System.arraycopy(snapshot, from, destination.pixels, to, 4)
            }
        }
        if (destination.isWindow) publishFrame()
        return true
    }

    @Synchronized
    fun clearArea(id: Int, x: Int, y: Int, width: Int, height: Int): Boolean {
        val drawable = drawables[id]?.takeIf { it.isWindow } ?: return false
        val right = if (width == 0) drawable.width else (x + width).coerceAtMost(drawable.width)
        val bottom = if (height == 0) drawable.height else (y + height).coerceAtMost(drawable.height)
        for (row in y.coerceAtLeast(0) until bottom.coerceAtLeast(y.coerceAtLeast(0))) {
            for (column in x.coerceAtLeast(0) until right.coerceAtLeast(x.coerceAtLeast(0))) {
                val offset = (row * drawable.width + column) * 4
                drawable.pixels[offset] = 0
                drawable.pixels[offset + 1] = 0
                drawable.pixels[offset + 2] = 0
                drawable.pixels[offset + 3] = 0
            }
        }
        publishFrame()
        return true
    }

    private fun publishFrame() {
        val output = ByteArray(screenWidth * screenHeight * 4)
        for (pixel in 0 until screenWidth * screenHeight) output[pixel * 4 + 3] = 0xff.toByte()
        drawChildren(PcglX11SetupCodec.ROOT_WINDOW_ID, 0, 0, output)
        runCatching { frameSink(PcglWindowFrame(screenWidth, screenHeight, output)) }
    }

    private fun drawChildren(parentId: Int, parentX: Int, parentY: Int, output: ByteArray) {
        drawables.values.filter { it.isWindow && it.parentId == parentId && it.mapped }.forEach { child ->
            val originX = parentX + child.x
            val originY = parentY + child.y
            for (row in 0 until child.height) {
                val screenY = originY + row
                if (screenY !in 0 until screenHeight) continue
                for (column in 0 until child.width) {
                    val screenX = originX + column
                    if (screenX !in 0 until screenWidth) continue
                    val source = (row * child.width + column) * 4
                    if (child.pixels[source + 3].toInt() == 0) continue
                    val target = (screenY * screenWidth + screenX) * 4
                    System.arraycopy(child.pixels, source, output, target, 4)
                }
            }
            drawChildren(child.id, originX, originY, output)
        }
    }

    private fun validAllocation(width: Int, height: Int): Boolean =
        width.toLong() * height * 4L <= MAX_FRAME_BYTES &&
            drawables.values.sumOf { it.pixels.size.toLong() } + width.toLong() * height * 4L <= MAX_TOTAL_DRAWABLE_BYTES

    private fun align4(value: Int): Int = (value + 3) and -4

    private companion object {
        const val Z_PIXMAP = 2
        const val MAX_FRAME_BYTES = 64L * 1024L * 1024L
        const val MAX_TOTAL_DRAWABLE_BYTES = 128L * 1024L * 1024L
    }
}
