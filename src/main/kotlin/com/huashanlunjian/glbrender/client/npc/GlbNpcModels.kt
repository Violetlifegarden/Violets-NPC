package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.client.npc.anim.GlbNpcAnimator
import com.huashanlunjian.glbrender.client.npc.anim.NpcAnimRuntime
import com.huashanlunjian.glbrender.npc.GlbModels
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
 * and pulls the model (descriptor from the common [GlbModels], loaded scene + shared renderer from
 * [GlbModelRegistry]). Draw itself goes through the glb-core public [drawScene] API, exactly like the demoted player
 * shim — no NPC-specific render path.
 *
 * The instance is rebuilt when an entity's modelId changes (right-click cycle), and released once the entity leaves
 * the client level via [pruneRemoved], so the per-entity map does not leak.
 */
object GlbNpcModels {
    private val LOGGER = LogUtils.getLogger()

    /** Holds the entity alongside its instance so [pruneRemoved] can test [net.minecraft.world.entity.Entity.isRemoved]
     *  without a client-side UUID->entity lookup (ClientLevel only indexes by network id). */
    private class Entry(val entity: GlbNpc, var instance: ModelInstance) {
        val anim = NpcAnimRuntime()
    }

    private val instances = HashMap<UUID, Entry>()

    fun draw(entity: GlbNpc, yaw: Float, partialTick: Float, packedLight: Int) {
        val modelId = entity.modelId.ifEmpty { GlbModels.defaultId } ?: return
        val descriptor = GlbModels.descriptor(modelId) ?: return
        val scene = GlbModelRegistry.sceneFor(modelId) ?: return
        val renderer = GlbModelRegistry.renderer() ?: return

        // Reuse the cached instance only while it still belongs to the current scene; a modelId switch hands us a
        // different scene, so drop the old reference (its refcount) and build a fresh instance for the new one.
        val cached = instances[entity.uuid]
        val entry = if (cached != null && cached.instance.scene === scene) {
            cached
        } else {
            cached?.instance?.decreaseReferenceCount()
            Entry(entity, ModelInstance.Factory.of(scene).also { it.increaseReferenceCount() })
                .also { instances[entity.uuid] = it }
        }
        val instance = entry.instance

        try {
            // Drive this frame's clip (priority table over the model's glTF clips) into the instance's bones BEFORE
            // updateRenderData recomputes world transforms from them — same ordering as the player clip path.
            val clips = GlbModelRegistry.clipsFor(modelId)
            if (!clips.isNullOrEmpty()) {
                val gameTick = Minecraft.getInstance().level?.gameTime ?: entity.tickCount.toLong()
                GlbNpcAnimator.animate(
                    entry.anim, entity, instance, clips,
                    GlbModelRegistry.animationTable, gameTick, partialTick,
                )
            }
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

    /**
     * Releases the [ModelInstance] of every NPC that has been removed (despawned, unloaded, or the world closed).
     * Called once per frame from the AFTER_SKY hook; without it the per-entity map grows for the lifetime of the
     * client and leaks a refcounted instance per removed entity.
     */
    fun pruneRemoved() {
        if (instances.isEmpty()) return
        if (Minecraft.getInstance().level == null) {
            instances.values.forEach { it.instance.decreaseReferenceCount() }
            instances.clear()
            return
        }
        val iterator = instances.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.entity.isRemoved) {
                entry.instance.decreaseReferenceCount()
                iterator.remove()
            }
        }
    }
}
