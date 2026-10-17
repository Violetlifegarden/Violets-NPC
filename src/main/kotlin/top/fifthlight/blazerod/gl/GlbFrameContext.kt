package top.fifthlight.blazerod.gl

import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3f

/**
 * The per-frame graphics state the ported renderer reads: the current camera matrices and fog.
 * 1.21.1 keeps these as render-thread statics in `com.mojang.blaze3d.systems.RenderSystem`, which
 * glb-core must not import, so glb-mc supplies the values through this seam. Implementations return
 * fresh snapshots on every call; the caller copies them straight into the uniform buffers.
 */
interface GlbFrameContext {
    fun projectionMatrix(): Matrix4fc
    fun modelViewMatrix(): Matrix4fc

    /** Fill [dest] with the fog color in [0,1] rgb. */
    fun fogColor(dest: Vector3f)
    fun fogShape(): Float
    fun fogStart(): Float
    fun fogEnd(): Float
}

/**
 * A frame context with an identity camera and fog pushed out to infinity, so nothing is fogged.
 * Only a fallback for contexts that never render for real (a pure glb-core unit test); the game and
 * the offscreen harness both install a real one.
 */
object IdentityFrameContext : GlbFrameContext {
    private val identity = Matrix4f()
    override fun projectionMatrix(): Matrix4fc = identity
    override fun modelViewMatrix(): Matrix4fc = identity
    override fun fogColor(dest: Vector3f) {
        dest.set(0f, 0f, 0f)
    }
    override fun fogShape(): Float = 0f
    override fun fogStart(): Float = Float.MAX_VALUE
    override fun fogEnd(): Float = Float.MAX_VALUE
}

/**
 * Light-level constants the renderer masks packed block light out of. Mirrors
 * `net.minecraft.client.renderer.LightTexture.FULL_BLOCK`, redefined here so glb-core carries no
 * client dependency.
 */
object GlbLightTexture {
    const val FULL_BLOCK = 240
}
