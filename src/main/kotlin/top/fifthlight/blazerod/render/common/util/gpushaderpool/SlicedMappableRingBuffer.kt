package top.fifthlight.blazerod.render.common.util.gpushaderpool

import top.fifthlight.blazerod.gl.Gl
import top.fifthlight.blazerod.gl.GpuBuffer
import top.fifthlight.blazerod.gl.GpuBufferSlice
import top.fifthlight.blazerod.gl.GpuFence
import top.fifthlight.blazerod.render.common.util.math.roundUpToMultiple
import java.lang.AutoCloseable
import java.util.function.Supplier

class SlicedMappableRingBuffer(
    nameSupplier: Supplier<String>? = null,
    usage: Int,
    extraUsage: Int = 0,
    size: Int,
    alignment: Int,
) : AutoCloseable {
    companion object {
        private const val BUFFER_COUNT = 3
    }

    private val buffer: GpuBuffer
    private val slices: Array<GpuBufferSlice>
    private val fences = arrayOfNulls<GpuFence>(BUFFER_COUNT)
    private val sliceSize: Int
    private var current = 0

    init {
        require(alignment >= 0) { "Alignment must be non-negative" }
        sliceSize = if (alignment == 0) {
            size
        } else {
            size roundUpToMultiple alignment
        }
        require((usage and GpuBuffer.USAGE_MAP_READ) != 0 || (usage and GpuBuffer.USAGE_MAP_WRITE) != 0) { "MappableRingBuffer requires at least one of USAGE_MAP_READ or USAGE_MAP_WRITE" }
        val device = Gl.device
        buffer = device.createBuffer(
            labelGetter = nameSupplier,
            usage = usage,
            extraUsage = extraUsage,
            size = sliceSize * BUFFER_COUNT,
        )
        slices = Array(BUFFER_COUNT) { i ->
            buffer.slice(i * sliceSize, sliceSize)
        }
    }

    fun getBlocking(): GpuBufferSlice {
        val fence = fences[current]
        if (fence != null) {
            fence.awaitCompletion(Long.MAX_VALUE)
            fence.close()
            fences[current] = null
        }
        return slices[current]
    }

    fun rotate() {
        val fence = fences[current]
        fence?.close()
        fences[current] = Gl.device.createCommandEncoder().createFence()
        current = (current + 1) % BUFFER_COUNT
    }

    override fun close() {
        buffer.close()
        fences.forEach { it?.close() }
    }
}
