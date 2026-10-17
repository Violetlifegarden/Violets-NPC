package top.fifthlight.blazerod.model.loader.util

import java.io.IOException
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel

fun ReadableByteChannel.readAll(buffer: ByteBuffer): Int {
    var length = 0
    while (buffer.hasRemaining()) {
        when (val len = read(buffer)) {
            -1 -> if (buffer.hasRemaining()) {
                throw IOException("Channel reached EOF")
            } else {
                break
            }

            0 -> throw IllegalStateException("Buffer is not blocking")
            else -> {
                length += len
            }
        }
    }
    return length
}

fun ReadableByteChannel.readRemaining(buffer: ByteBuffer): Int {
    var length = 0
    while (buffer.hasRemaining()) {
        when (val len = read(buffer)) {
            -1 -> break

            0 -> throw IllegalStateException("Buffer is not blocking")
            else -> {
                length += len
            }
        }
    }
    return length
}

fun FileChannel.readToBuffer(
    offset: Long = 0,
    length: Long = size(),
    readSizeLimit: Int,
): ByteBuffer {
    // First, let's try mapping into memory
    try {
        return map(FileChannel.MapMode.READ_ONLY, offset, length)
    } catch (_: IOException) {
    } catch (_: UnsupportedOperationException) { // For ZipFileSystem
    }

    // If mapping failed, just read it to memory
    if (length > readSizeLimit) {
        throw IOException("Data size exceeds limit: maximum is $readSizeLimit, but got $length")
    }
    // length can't be longer than readSizeLimit (which is Int), so it is safe to convert length to Int
    val chunkLength = length.toInt()
    val prevPosition = position()
    try {
        val binaryBuffer = ByteBuffer.allocateDirect(chunkLength)
        position(offset)
        readAll(binaryBuffer)
        binaryBuffer.flip()
        return binaryBuffer
    } finally {
        position(prevPosition)
    }
}

inline fun ByteBuffer.withRelativeLimit(relativeLimit: Int, crossinline func: () -> Unit) {
    val oldLimit = limit()
    val newLimit = position() + relativeLimit
    if (newLimit > oldLimit) {
        throw BufferOverflowException()
    }
    limit(newLimit)
    try {
        func()
    } finally {
        limit(oldLimit)
    }
}

