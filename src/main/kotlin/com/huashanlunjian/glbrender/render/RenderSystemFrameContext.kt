package com.huashanlunjian.glbrender.render

import com.mojang.blaze3d.systems.RenderSystem
import org.joml.Matrix4fc
import org.joml.Vector3f
import top.fifthlight.blazerod.gl.GlbFrameContext

/**
 * The frame context backed by Minecraft's render-thread statics. The game updates `RenderSystem`'s
 * projection, model-view and fog before each draw, so reading them lazily here hands the ported
 * renderer exactly the state the game would have given blaze3d.
 */
object RenderSystemFrameContext : GlbFrameContext {
    override fun projectionMatrix(): Matrix4fc = RenderSystem.getProjectionMatrix()
    override fun modelViewMatrix(): Matrix4fc = RenderSystem.getModelViewStack()

    override fun fogColor(dest: Vector3f) {
        val color = RenderSystem.getShaderFogColor()
        dest.set(color[0], color[1], color[2])
    }

    override fun fogShape(): Float = RenderSystem.getShaderFogShape().index.toFloat()
    override fun fogStart(): Float = RenderSystem.getShaderFogStart()
    override fun fogEnd(): Float = RenderSystem.getShaderFogEnd()
}
