package top.fifthlight.blazerod.render.common.runtime.load

import top.fifthlight.blazerod.gl.GpuBuffer
import top.fifthlight.blazerod.gl.Gl
import top.fifthlight.blazerod.gl.GpuTexture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import top.fifthlight.blazerod.render.extension.writeToTexture
import top.fifthlight.blazerod.render.render.GpuIndexBuffer
import top.fifthlight.blazerod.render.render.RefCountedGpuBuffer
import top.fifthlight.blazerod.render.runtime.resource.RenderPrimitive
import top.fifthlight.blazerod.render.runtime.resource.RenderTexture
import top.fifthlight.blazerod.render.util.blaze3d.blaze3d
import top.fifthlight.blazerod.render.util.blaze3d.useMipmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

object ModelResourceLoader {
    private fun <T, R> Deferred<T>.map(
        scope: CoroutineScope,
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend (T) -> R,
    ): Deferred<R> = scope.async(context) {
        block(await())
    }

    private fun <T, R> Iterable<Deferred<T>>.mapAll(
        scope: CoroutineScope,
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend (T) -> R,
    ): List<Deferred<R>> = map {
        it.map(scope, context, block)
    }

    fun load(
        scope: CoroutineScope,
        gpuDispatcher: CoroutineDispatcher,
        info: PreProcessModelLoadInfo,
    ): GpuLoadModelLoadInfo {
        val textures = info.textures.mapAll(scope, gpuDispatcher) { info ->
            val info = info ?: return@mapAll null
            info.use { info ->
                val (name, pixelSource, sampler) = info
                val device = Gl.device
                val commandEncoder = device.createCommandEncoder()
                val gpuTexture: GpuTexture
                pixelSource.use {
                    gpuTexture = device.createTexture(
                        labelGetter = { name ?: "unnamed texture" },
                        GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST,
                        it.format,
                        it.width,
                        it.height,
                        1,
                        1
                    )
                    gpuTexture.setAddressMode(sampler.wrapS.blaze3d, sampler.wrapT.blaze3d)
                    gpuTexture.setTextureFilter(
                        sampler.minFilter.blaze3d,
                        sampler.magFilter.blaze3d,
                        sampler.minFilter.useMipmap,
                    )
                    commandEncoder.writeToTexture(gpuTexture, pixelSource)
                }
                val textureView = device.createTextureView(gpuTexture)
                RenderTexture(gpuTexture, textureView)
            }
        }
        val indexBuffers = info.indexBuffers.mapAll(scope, gpuDispatcher) { indexData ->
            val device = Gl.device
            val buffer = RefCountedGpuBuffer(
                device.createBuffer(
                    null,
                    GpuBuffer.USAGE_INDEX,
                    indexData.buffer,
                )
            )
            GpuIndexBuffer(
                type = indexData.type,
                length = indexData.length,
                buffer = buffer,
            )
        }
        val vertexBuffers = info.vertexBuffers.mapAll(scope, gpuDispatcher) {
            val device = Gl.device
            val buffer = RefCountedGpuBuffer(
                device.createBuffer(
                    labelGetter = null,
                    usage = GpuBuffer.USAGE_VERTEX,
                    extraUsage = GpuBuffer.EXTRA_USAGE_STORAGE_BUFFER,
                    data = it,
                )
            )
            GpuLoadVertexData(
                gpuBuffer = buffer,
                cpuBuffer = it,
            )
        }
        val morphTargetInfos = info.morphTargetInfos.mapAll(scope, gpuDispatcher) {
            fun loadTarget(target: MorphTargetsLoadData.TargetInfo): RenderPrimitive.Target {
                val targetBuffer = if (target.targetsCount == 0) {
                    // No targets, but we can't create an empty buffer, so let's create a dummy one
                    ByteBuffer.allocateDirect(target.itemStride).order(ByteOrder.nativeOrder())
                } else {
                    target.buffer
                }
                val device = Gl.device
                val gpuBuffer = device.createBuffer(
                    labelGetter = { "Morph target buffer" },
                    usage = GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER,
                    extraUsage = GpuBuffer.EXTRA_USAGE_STORAGE_BUFFER,
                    data = targetBuffer,
                )
                return RenderPrimitive.Target(
                    gpuBuffer = gpuBuffer,
                    cpuBuffer = targetBuffer,
                    targetsCount = target.targetsCount,
                )
            }
            MorphTargetsLoadData(
                targetGroups = it.targetGroups,
                position = loadTarget(it.position),
                color = loadTarget(it.color),
                texCoord = loadTarget(it.texCoord),
            )
        }
        return GpuLoadModelLoadInfo(
            textures = textures,
            indexBuffers = indexBuffers,
            vertexBuffers = vertexBuffers,
            morphTargetInfos = morphTargetInfos,
            primitiveInfos = info.primitiveInfos,
            rootNodeIndex = info.rootNodeIndex,
            nodes = info.nodes,
            skins = info.skins,
            expressions = info.expressions,
            expressionGroups = info.expressionGroups,
            renderTransform = info.renderTransform,
        )
    }
}
