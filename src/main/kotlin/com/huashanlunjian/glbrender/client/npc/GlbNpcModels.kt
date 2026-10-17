package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.npc.GlbNpc
import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import top.fifthlight.blazerod.gl.Gl
import top.fifthlight.blazerod.gl.GpuTexture
import top.fifthlight.blazerod.gl.TextureFormat
import top.fifthlight.blazerod.render.api.render.GlbRenderTarget
import top.fifthlight.blazerod.render.api.render.drawScene
import top.fifthlight.blazerod.render.api.resource.ModelInstance
import java.util.HashMap
import java.util.UUID

/**
 * Renderer-side glue for the NPC draw: it owns the per-entity [ModelInstance] and the camera-relative body matrix,
 * and pulls the model (descriptor + loaded scene + shared renderer) from [GlbModelRegistry]. Draw itself goes
 * through the glb-core public [drawScene] API, exactly like the demoted player shim — no NPC-specific render path.
 *
 * Step 3a: still selects the single registered default model; 3b will key the instance off a synced per-entity
 * modelId and release instances when an entity is removed.
 */
object GlbNpcModels {
    private val LOGGER = LogUtils.getLogger()

    private val instances = HashMap<UUID, ModelInstance>()

    fun draw(entity: GlbNpc, yaw: Float, partialTick: Float, packedLight: Int) {
        val modelId = GlbModelRegistry.defaultId ?: return
        val descriptor = GlbModelRegistry.descriptor(modelId) ?: return
        val scene = GlbModelRegistry.sceneFor(modelId) ?: return
        val renderer = GlbModelRegistry.renderer() ?: return
        val instance = instances.getOrPut(entity.uuid) {
            ModelInstance.Factory.of(scene).also { it.increaseReferenceCount() }
        }
        try {
            instance.updateRenderData(partialTick)
            val minecraft = Minecraft.getInstance()
            val target = minecraft.mainRenderTarget
            val device = Gl.device
            val color = GpuTexture.adopt(target.colorTextureId, TextureFormat.RGBA8)
            val depth = GpuTexture.adopt(target.depthTextureId, TextureFormat.DEPTH24)
            val colorView = device.createTextureView(color)
            val depthView = device.createTextureView(depth)

            // RenderSystem's modelview during entity rendering is the camera rotation only (vanilla folds the
            // -camera translation into entity geometry), so place the model camera-relative like the player shim.
            val camera = minecraft.gameRenderer.mainCamera.position
            val pos = entity.getPosition(partialTick)
            val yawRadians = Math.toRadians((180.0 - yaw + descriptor.yawOffset)).toFloat()
            val bodyMatrix = Matrix4f()
                .translation(
                    (pos.x - camera.x).toFloat(),
                    (pos.y - camera.y).toFloat(),
                    (pos.z - camera.z).toFloat(),
                )
                .rotateY(yawRadians)
                .scale(descriptor.scale)
                .translate(0f, descriptor.groundOffsetY, 0f)
            renderer.drawScene(instance, GlbRenderTarget(colorView, depthView), bodyMatrix, packedLight)
        } catch (error: RuntimeException) {
            LOGGER.error("glbrender: npc draw failed", error)
        }
    }
}
