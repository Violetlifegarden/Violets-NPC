package top.fifthlight.blazerod.render.common.runtime.load

import top.fifthlight.blazerod.render.format.GlbElementType
import top.fifthlight.blazerod.render.format.GlbVertexFormatElement
import top.fifthlight.blazerod.model.Accessor
import top.fifthlight.blazerod.model.elementLength
import top.fifthlight.blazerod.model.read
import top.fifthlight.blazerod.model.readNormalized
import top.fifthlight.blazerod.model.util.*
import java.nio.ByteBuffer

object VertexLoadUtil {
    fun copyRawData(
        vertices: Int,
        stride: Int,
        srcAttribute: Accessor,
        dstBuffer: ByteBuffer,
        dstOffset: Int,
        dstLength: Int,
    ) {
        require(srcAttribute.elementLength == dstLength) { "Raw copy failed: Source element length (${srcAttribute.elementLength}) does not match destination element length ($dstLength)" }
        val srcByteBufferView = srcAttribute.bufferView ?: return
        val srcByteBuffer = srcByteBufferView.buffer.buffer.duplicate()
        var currentSrcOffset = srcAttribute.byteOffset + srcByteBufferView.byteOffset
        var currentDstOffset = dstOffset
        val srcStride = srcByteBufferView.byteStride.takeIf { it != 0 } ?: srcAttribute.elementLength

        repeat(vertices) {
            srcByteBuffer.position(currentSrcOffset)
            srcByteBuffer.limit(currentSrcOffset + dstLength)
            dstBuffer.position(currentDstOffset)
            dstBuffer.put(srcByteBuffer)
            srcByteBuffer.clear()
            dstBuffer.clear()
            currentSrcOffset += srcStride
            currentDstOffset += stride
        }
    }

    fun copyUnnormalizedData(
        stride: Int,
        srcAttribute: Accessor,
        dstBuffer: ByteBuffer,
        dstOffset: Int,
        targetElement: GlbVertexFormatElement,
    ) {
        require(srcAttribute.type.components == targetElement.count) { "Copy unnormalized data failed: Source element count (${srcAttribute.type.components}) does not match destination element count (${targetElement.count})" }
        if (srcAttribute.bufferView == null) {
            return
        }
        var currentDstOffset = dstOffset
        srcAttribute.read { elementBuffer ->
            repeat(srcAttribute.type.components) { component ->
                val dstComponentLength = targetElement.type.bytes
                val absoluteDstOffset = currentDstOffset + component * dstComponentLength
                when (srcAttribute.componentType) {
                    Accessor.ComponentType.BYTE -> {
                        val value = elementBuffer.get()
                        when (targetElement.type) {
                            GlbElementType.UBYTE, GlbElementType.BYTE -> dstBuffer.put(
                                absoluteDstOffset,
                                value
                            )

                            GlbElementType.USHORT, GlbElementType.SHORT -> dstBuffer.putShort(
                                absoluteDstOffset,
                                value.toShort()
                            )

                            GlbElementType.UINT, GlbElementType.INT -> dstBuffer.putInt(
                                absoluteDstOffset,
                                value.toInt()
                            )

                            GlbElementType.FLOAT -> dstBuffer.putFloat(
                                absoluteDstOffset,
                                elementBuffer.getFloat()
                            )
                        }
                    }

                    Accessor.ComponentType.UNSIGNED_BYTE -> {
                        val value = elementBuffer.get().toUByte()
                        when (targetElement.type) {
                            GlbElementType.UBYTE, GlbElementType.BYTE -> dstBuffer.put(
                                absoluteDstOffset,
                                value.toByte()
                            )

                            GlbElementType.USHORT, GlbElementType.SHORT -> dstBuffer.putShort(
                                absoluteDstOffset,
                                value.toShort()
                            )

                            GlbElementType.UINT, GlbElementType.INT -> dstBuffer.putInt(
                                absoluteDstOffset,
                                value.toInt()
                            )

                            GlbElementType.FLOAT -> dstBuffer.putFloat(absoluteDstOffset, value.toFloat())
                        }
                    }

                    Accessor.ComponentType.SHORT -> {
                        val value = elementBuffer.getShort()
                        when (targetElement.type) {
                            GlbElementType.UBYTE, GlbElementType.BYTE -> {
                                require(value in Byte.MIN_VALUE..Byte.MAX_VALUE) { "Short input cannot be copied to byte output" }
                                dstBuffer.put(absoluteDstOffset, value.toByte())
                            }

                            GlbElementType.USHORT, GlbElementType.SHORT -> dstBuffer.putShort(
                                absoluteDstOffset,
                                value
                            )

                            GlbElementType.UINT, GlbElementType.INT -> dstBuffer.putInt(
                                absoluteDstOffset,
                                value.toInt()
                            )

                            GlbElementType.FLOAT -> dstBuffer.putFloat(absoluteDstOffset, value.toFloat())
                        }
                    }

                    Accessor.ComponentType.UNSIGNED_SHORT -> {
                        val value = elementBuffer.getShort().toUShort()
                        when (targetElement.type) {
                            GlbElementType.UBYTE, GlbElementType.BYTE -> {
                                require(value in UByte.MIN_VALUE..UByte.MAX_VALUE) { "Short input cannot be copied to byte output" }
                                dstBuffer.put(absoluteDstOffset, value.toByte())
                            }

                            GlbElementType.USHORT, GlbElementType.SHORT -> dstBuffer.putShort(
                                absoluteDstOffset,
                                value.toShort()
                            )

                            GlbElementType.UINT, GlbElementType.INT -> dstBuffer.putInt(
                                absoluteDstOffset,
                                value.toInt()
                            )

                            GlbElementType.FLOAT -> dstBuffer.putFloat(absoluteDstOffset, value.toFloat())
                        }
                    }

                    Accessor.ComponentType.UNSIGNED_INT -> {
                        val value = elementBuffer.getInt().toUInt()
                        when (targetElement.type) {
                            GlbElementType.UBYTE, GlbElementType.BYTE -> {
                                require(value in UByte.MIN_VALUE..UByte.MAX_VALUE) { "Int input cannot be copied to byte output" }
                                dstBuffer.put(absoluteDstOffset, value.toByte())
                            }

                            GlbElementType.USHORT, GlbElementType.SHORT -> {
                                require(value in UShort.MIN_VALUE..UShort.MAX_VALUE) { "Int input cannot be copied to Short output" }
                                dstBuffer.putShort(absoluteDstOffset, value.toShort())
                            }

                            GlbElementType.UINT, GlbElementType.INT -> dstBuffer.putInt(
                                absoluteDstOffset,
                                value.toInt()
                            )

                            GlbElementType.FLOAT -> dstBuffer.putFloat(absoluteDstOffset, value.toFloat())
                        }
                    }

                    Accessor.ComponentType.FLOAT -> {
                        require(targetElement.type == GlbElementType.FLOAT) { "Float input can only be copied to float output" }
                        dstBuffer.putFloat(absoluteDstOffset, elementBuffer.getFloat())
                    }
                }
            }
            currentDstOffset += stride
        }
    }

    fun copyNormalizedData(
        stride: Int,
        srcAttribute: Accessor,
        dstBuffer: ByteBuffer,
        dstOffset: Int,
        targetElementType: GlbElementType,
        componentsToWrite: Int,
        fillAlphaValue: Float? = null,
    ) {
        if (fillAlphaValue != null) {
            require(srcAttribute.type == Accessor.AccessorType.VEC3 && componentsToWrite == 4) {
                "Fill alpha value is only supported for vec3 input attributes with 4 output components"
            }
        }
        if (srcAttribute.bufferView == null) {
            return
        }

        var currentDstOffset = dstOffset
        var dstComponentIndex = 0
        val byteSizePerComponent = targetElementType.bytes

        srcAttribute.readNormalized { value ->
            val absoluteDstOffset = currentDstOffset + dstComponentIndex * byteSizePerComponent
            when (targetElementType) {
                GlbElementType.FLOAT -> dstBuffer.putFloat(
                    absoluteDstOffset,
                    value
                )

                GlbElementType.UBYTE -> dstBuffer.put(
                    absoluteDstOffset,
                    value.toNormalizedUByte()
                )

                GlbElementType.BYTE -> dstBuffer.put(
                    absoluteDstOffset,
                    value.toNormalizedSByte()
                )

                GlbElementType.USHORT -> dstBuffer.putShort(
                    absoluteDstOffset,
                    value.toNormalizedUShort()
                )

                GlbElementType.SHORT -> dstBuffer.putShort(
                    absoluteDstOffset,
                    value.toNormalizedSShort()
                )

                GlbElementType.UINT -> dstBuffer.putInt(
                    absoluteDstOffset,
                    value.toNormalizedUInt()
                )

                GlbElementType.INT -> dstBuffer.putInt(
                    absoluteDstOffset,
                    value.toNormalizedUInt()
                )
            }
            dstComponentIndex++

            if (dstComponentIndex == 3 && fillAlphaValue != null) {
                val absoluteDstOffsetAlpha = currentDstOffset + 3 * byteSizePerComponent
                when (targetElementType) {
                    GlbElementType.FLOAT -> dstBuffer.putFloat(
                        absoluteDstOffsetAlpha,
                        fillAlphaValue
                    )

                    GlbElementType.UBYTE -> dstBuffer.put(
                        absoluteDstOffsetAlpha,
                        fillAlphaValue.toNormalizedUByte()
                    )

                    GlbElementType.BYTE -> dstBuffer.put(
                        absoluteDstOffsetAlpha,
                        fillAlphaValue.toNormalizedSByte()
                    )

                    GlbElementType.USHORT -> dstBuffer.putShort(
                        absoluteDstOffsetAlpha,
                        fillAlphaValue.toNormalizedUShort()
                    )

                    GlbElementType.SHORT -> dstBuffer.putShort(
                        absoluteDstOffsetAlpha,
                        fillAlphaValue.toNormalizedSShort()
                    )

                    GlbElementType.UINT -> dstBuffer.putInt(
                        absoluteDstOffsetAlpha,
                        fillAlphaValue.toNormalizedUInt()
                    )

                    GlbElementType.INT -> dstBuffer.putInt(
                        absoluteDstOffsetAlpha,
                        fillAlphaValue.toNormalizedUInt()
                    )
                }
                dstComponentIndex++
            }

            if (dstComponentIndex == componentsToWrite) {
                dstComponentIndex = 0
                currentDstOffset += stride
            }
        }
    }

    fun copyAttributeData(
        vertices: Int,
        stride: Int,
        element: GlbVertexFormatElement,
        normalized: Boolean,
        srcAttribute: Accessor,
        dstBuffer: ByteBuffer,
        dstOffset: Int,
    ) {
        val isFloat = srcAttribute.componentType == Accessor.ComponentType.FLOAT
        require(normalized == srcAttribute.normalized || isFloat) { "Source attribute's normalized ${srcAttribute.normalized} don't match target element $normalized" }
        require(srcAttribute.count == vertices) { "Source attribute's vertex count ${srcAttribute.count} don't match target vertex count $vertices" }
        val dstLength = element.byteSize
        if (srcAttribute.bufferView == null) {
            return
        }

        val canCopyRaw = srcAttribute.componentType == Accessor.ComponentType.FLOAT &&
                !srcAttribute.normalized &&
                srcAttribute.elementLength == dstLength &&
                element.type == GlbElementType.FLOAT

        if (canCopyRaw) {
            copyRawData(
                vertices = vertices,
                stride = stride,
                srcAttribute = srcAttribute,
                dstBuffer = dstBuffer,
                dstOffset = dstOffset,
                dstLength = dstLength,
            )
        } else if (normalized) {
            val fillAlpha =
                if (element.name == "Color" && srcAttribute.type.components == 3 && element.count == 4) {
                    1f
                } else {
                    null
                }

            copyNormalizedData(
                stride = stride,
                srcAttribute = srcAttribute,
                dstBuffer = dstBuffer,
                dstOffset = dstOffset,
                targetElementType = element.type,
                componentsToWrite = element.count,
                fillAlphaValue = fillAlpha,
            )
        } else {
            copyUnnormalizedData(
                stride = stride,
                srcAttribute = srcAttribute,
                dstBuffer = dstBuffer,
                dstOffset = dstOffset,
                targetElement = element,
            )
        }
    }
}
