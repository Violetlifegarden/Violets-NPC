package top.fifthlight.blazerod.model.util

import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.math.roundToLong

fun ByteBuffer.getUByteNormalized() = get().toUByte().toFloat() / 255f
fun ByteBuffer.getSByteNormalized() = (get().toFloat() / 127f).coerceAtLeast(-1f)
fun ByteBuffer.getUShortNormalized() = getShort().toUShort().toFloat() / 65535f
fun ByteBuffer.getSShortNormalized() = (getShort().toFloat() / 32767f).coerceAtLeast(-1f)

fun ByteBuffer.getUByteNormalized(index: Int) = get(index).toUByte().toFloat() / 255f
fun ByteBuffer.getSByteNormalized(index: Int) = (get(index).toFloat() / 127f).coerceAtLeast(-1f)
fun ByteBuffer.getUShortNormalized(index: Int) = getShort(index).toUShort().toFloat() / 65535f
fun ByteBuffer.getSShortNormalized(index: Int) = (getShort(index).toFloat() / 32767f).coerceAtLeast(-1f)

@Suppress("FloatingPointLiteralPrecision")
fun ByteBuffer.getUIntNormalized() = (getInt().toFloat() / 4294967295f).coerceAtLeast(-1f)

fun Float.normalize() = if (!isFinite()) {
    0f
} else {
    this
}

fun Float.toNormalizedUByte(): Byte = (normalize() * 255.0f).roundToInt().toByte()
fun Float.toNormalizedSByte(): Byte = (normalize() * 127.0f).roundToInt().toByte()
fun Float.toNormalizedUShort(): Short = (normalize() * 65535.0f).roundToInt().toShort()
fun Float.toNormalizedSShort(): Short = (normalize() * 32767.0f).roundToInt().toShort()
fun Float.toNormalizedUInt(): Int = (normalize() * 4294967295.0).roundToLong().toInt()
