package com.huashanlunjian.glbrender.client.npc.preview

import com.huashanlunjian.glbrender.client.npc.GlbModelRegistry
import com.huashanlunjian.glbrender.npc.GlbModelDescriptor
import com.huashanlunjian.glbrender.npc.GlbModels
import com.huashanlunjian.glbrender.npc.GlbNpc
import com.huashanlunjian.glbrender.npc.GlbNpcConfig
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.Util
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.network.chat.Component
import org.joml.Matrix4f
import org.lwjgl.opengl.GL13
import top.fifthlight.blazerod.model.animation.AnimationContext
import top.fifthlight.blazerod.model.animation.AnimationState
import top.fifthlight.blazerod.model.rgbaFromArgb
import top.fifthlight.blazerod.model.util.MutableFloat
import top.fifthlight.blazerod.model.util.MutableLong
import top.fifthlight.blazerod.render.api.animation.AnimationItemInstance
import top.fifthlight.blazerod.render.api.resource.MaterialMode
import top.fifthlight.blazerod.render.api.resource.ModelInstance
import top.fifthlight.blazerod.render.api.resource.RenderScene
import top.fifthlight.blazerod.render.runtime.animation.AnimationItemImpl
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The standalone model/animation preview: a 180x180 offscreen viewport on the left (left drag to orbit, right
 * drag to pan, wheel to zoom, Reset to restore the default framing) and every clip the model carries as a
 * clickable list on the right, with play/pause and restart.
 *
 * A dedicated screen rather than an editor tab because the editor is already at CNPC's 420x200 with six tabs;
 * this one is where the render-tuning work will grow. It is opened from the Display tab and reconstructed back
 * into the editor on close: the editor's menu is left open on both sides while the preview is up (see
 * [GlbNpcEditorScreen.keepMenuOnClose]) so the editing session survives and later render edits can still be sent.
 *
 * The model is drawn through the exact same `drawScene` path as the world NPC: same body matrix composition,
 * same tint/shading/light inputs. Playback is driven by a wall-clock [AnimationContext] rather than game ticks,
 * because a singleplayer game pauses its level while a screen is open and the clip would freeze.
 *
 * The bind pose heads the list and is the natural "nothing forced" choice; every glTF clip the model carries
 * follows, including the ones the locomotion table does not reference yet (the point of this screen).
 */
class GlbPreviewScreen(
    private val npc: GlbNpc?,
    private val modelId: String,
    private val initialClip: String,
    modelHeight: Float,
    private val returnTo: () -> Screen?,
) : Screen(Component.literal("GLB Preview")) {

    private val bodyHeight = if (modelHeight > 0.05f) modelHeight else 1.8f

    private var left = 0
    private var top = 0

    private var instance: ModelInstance? = null
    private var clipItem: AnimationItemInstance? = null
    private var clipState: AnimationState? = null

    private var rowsSource: List<AnimationItemImpl>? = null
    private var rows: List<Row> = listOf(Row(BIND_POSE, null))
    private var selected = 0
    private var scroll = 0
    private var pendingInitialClip: String = initialClip

    private var playing = true
    private var lastMillis = 0L
    private var elapsedSeconds = 0f
    private val clock = PreviewClock()

    private var orbitYaw = DEFAULT_YAW
    private var orbitPitch = DEFAULT_PITCH
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var dragButton = -1
    // Fed from drawModel so the pan rate matches this frame's zoom/scale instead of guessing at it.
    private var cameraDistance = 1f

    private lateinit var playButton: Button

    private data class Row(val label: String, val clip: AnimationItemImpl?)

    override fun init() {
        super.init()
        left = (width - PANEL_W) / 2
        top = (height - PANEL_H) / 2
        playButton = addRenderableWidget(
            Button.builder(Component.literal("Pause")) { togglePlay() }
                .bounds(left + LIST_X, top + BUTTON_Y, BUTTON_W, BUTTON_H)
                .build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Restart")) { restart() }
                .bounds(left + LIST_X + (BUTTON_W + BUTTON_GAP), top + BUTTON_Y, BUTTON_W, BUTTON_H)
                .build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Reset")) { resetView() }
                .bounds(left + LIST_X + 2 * (BUTTON_W + BUTTON_GAP), top + BUTTON_Y, BUTTON_W, BUTTON_H)
                .build(),
        )
        syncRows()
    }

    // --- model + animation ---

    /** Builds/rebuilds the clip rows once the scene has finished loading (the clips list appears then). */
    private fun syncRows() {
        val clips = GlbModelRegistry.clipsFor(modelId)
        if (clips == null || clips === rowsSource) return
        rowsSource = clips
        rows = buildList {
            add(Row(BIND_POSE, null))
            clips.forEachIndexed { index, clip ->
                add(Row(clip.name ?: "Clip ${index + 1}", clip))
            }
        }
        val wanted = if (pendingInitialClip.isEmpty()) {
            -1
        } else {
            rows.indexOfFirst { it.clip?.name?.equals(pendingInitialClip, ignoreCase = true) == true }
        }
        selected = when {
            wanted >= 0 -> wanted
            pendingInitialClip.isEmpty() && rows.size > 1 -> 1
            else -> 0
        }
        pendingInitialClip = ""
        scroll = 0
        selectInstancePose()
        ensureVisible()
    }

    private fun ensureInstance(scene: RenderScene): ModelInstance {
        val current = instance
        if (current != null && current.scene === scene) return current
        current?.decreaseReferenceCount()
        val created = ModelInstance.Factory.of(scene).also { it.increaseReferenceCount() }
        instance = created
        // The instance starts at the bind pose, so re-seed whatever was selected before it existed.
        selectInstancePose()
        return created
    }

    /** Re-applies the selected row's pose to the current instance (bind pose clears every animation channel). */
    private fun selectInstancePose() {
        instance?.clearTransform()
        val clip = rows.getOrNull(selected)?.clip
        clipItem = clip?.let { AnimationItemInstance.Factory.of(it) }
        clipState = clipItem?.createState(clock)
    }

    private fun select(index: Int) {
        val target = index.coerceIn(0, rows.lastIndex)
        if (target == selected && clipState != null) return
        selected = target
        selectInstancePose()
        ensureVisible()
    }

    private fun ensureVisible() {
        if (selected < scroll) scroll = selected
        if (selected >= scroll + VISIBLE_ROWS) scroll = selected - VISIBLE_ROWS + 1
        scroll = scroll.coerceIn(0, (rows.size - VISIBLE_ROWS).coerceAtLeast(0))
    }

    private fun togglePlay() {
        playing = !playing
        playButton.setMessage(Component.literal(if (playing) "Pause" else "Play"))
    }

    private fun restart() {
        // A fresh state starts at time zero on the next update; the clock itself keeps running.
        selectInstancePose()
    }

    /** Puts the camera back at the default framing; the pose is left alone. */
    private fun resetView() {
        orbitYaw = DEFAULT_YAW
        orbitPitch = DEFAULT_PITCH
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    private fun advanceClock() {
        val now = Util.getMillis()
        if (lastMillis == 0L) {
            lastMillis = now
            return
        }
        val delta = ((now - lastMillis).coerceAtLeast(0L) / 1000f).coerceAtMost(0.1f)
        lastMillis = now
        if (!playing) return
        elapsedSeconds += delta
        val ticks = elapsedSeconds / SECONDS_PER_TICK
        clock.tick = ticks.toLong()
        clock.delta = ticks - clock.tick
    }

    /** Renders the model into the offscreen target; false while the scene is still loading or unavailable. */
    private fun drawModel(): Boolean {
        val scene = GlbModelRegistry.sceneFor(modelId) ?: return false
        val renderer = GlbModelRegistry.renderer() ?: return false
        val descriptor = GlbModels.descriptor(modelId) ?: return false
        val inst = ensureInstance(scene)

        advanceClock()
        val item = clipItem
        val state = clipState
        if (item != null && state != null) {
            state.updateTime(clock)
            item.apply(inst, item.update(clock, state))
        }
        inst.updateRenderData(clock.delta)

        val config = npc?.config
        val scale = config?.effectiveScale(descriptor) ?: descriptor.scale
        val yawOffset = config?.effectiveYawOffset(descriptor) ?: descriptor.yawOffset
        val groundOffsetY = config?.effectiveGroundOffsetY(descriptor) ?: descriptor.groundOffsetY
        // Same composition as GlbNpcModels.draw (minus the camera-relative translation): center yaw at the model's
        // own origin, then scale, then the ground offset in model units.
        val modelMatrix = Matrix4f()
            .rotateY(Math.toRadians(yawOffset.toDouble()).toFloat())
            .scale(scale)
            .translate(0f, groundOffsetY, 0f)

        val centerY = (bodyHeight * 0.5f + groundOffsetY) * scale
        val distance = bodyHeight * scale * CAMERA_DISTANCE_FACTOR * zoom
        cameraDistance = distance
        val yaw = Math.toRadians(orbitYaw.toDouble()).toFloat()
        val pitch = Math.toRadians(orbitPitch.toDouble()).toFloat()
        // The orbit target is pan-offset along the camera's own right axis (yaw-only) and world up, so the
        // grab-pan follows the cursor no matter how far the model has been rotated.
        val targetX = panX * cos(yaw)
        val targetY = centerY + panY
        val targetZ = -panX * sin(yaw)
        val view = Matrix4f().lookAt(
            (targetX + sin(yaw) * cos(pitch) * distance).toFloat(),
            (targetY + sin(pitch) * distance).toFloat(),
            (targetZ + cos(yaw) * cos(pitch) * distance).toFloat(),
            targetX, targetY, targetZ,
            0f, 1f, 0f,
        )

        GlbPreviewTarget.draw(
            inst,
            renderer,
            modelMatrix,
            FULL_BRIGHT,
            rgbaFromArgb(config?.tint ?: GlbNpcConfig.NO_TINT),
            when (config?.materialMode ?: GlbNpcConfig.MATERIAL_PBR) {
                GlbNpcConfig.MATERIAL_VANILLA -> MaterialMode.VANILLA
                GlbNpcConfig.MATERIAL_UNLIT -> MaterialMode.UNLIT
                else -> MaterialMode.DEFAULT
            },
            PROJECTION,
            view,
        )
        return true
    }

    // --- drawing ---

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(g, mouseX, mouseY, partialTick)
        syncRows()
        drawPanel(g)

        if (drawModel()) {
            blitPreview(g, GlbPreviewTarget.textureId(), left + VIEW_X, top + VIEW_Y)
        } else {
            g.drawCenteredString(
                font, LOADING_HINT,
                left + VIEW_X + VIEW_SIZE / 2, top + VIEW_Y + VIEW_SIZE / 2 - 4,
                COLOR_DIM,
            )
        }

        drawClipList(g, mouseX.toDouble(), mouseY.toDouble())
        // The two buttons are regular widgets; Screen.render would draw the background again, so run the list it
        // would have run instead.
        renderables.forEach { it.render(g, mouseX, mouseY, partialTick) }
        drawTime(g)
    }

    private fun drawPanel(g: GuiGraphics) {
        val right = left + PANEL_W
        val bottom = top + PANEL_H
        g.fill(left - 1, top - 1, right + 1, bottom + 1, COLOR_BORDER)
        g.fill(left, top, right, bottom, PANEL_BG)
        g.drawString(font, title.string, left + 8, top + 7, COLOR_TITLE, false)
        val hint = HEADER_HINT
        g.drawString(font, hint, right - font.width(hint) - 8, top + 7, COLOR_DIM, false)
        // Viewport frame (the rendered frame is blitted inside it afterwards).
        g.fill(left + VIEW_X - 1, top + VIEW_Y - 1, left + VIEW_X + VIEW_SIZE + 1, top + VIEW_Y + VIEW_SIZE + 1, COLOR_BORDER)
        g.fill(left + VIEW_X, top + VIEW_Y, left + VIEW_X + VIEW_SIZE, top + VIEW_Y + VIEW_SIZE, VIEWPORT_BG)
        // Clip-list frame.
        g.fill(left + LIST_X - 1, top + LIST_Y - 1, left + LIST_X + LIST_W + 1, top + LIST_Y + LIST_H + 1, COLOR_BORDER)
        g.fill(left + LIST_X, top + LIST_Y, left + LIST_X + LIST_W, top + LIST_Y + LIST_H, LIST_BG)
    }

    private fun drawClipList(g: GuiGraphics, mouseX: Double, mouseY: Double) {
        val x = left + LIST_X
        val y = top + LIST_Y
        val hoveredRow = if (insideList(mouseX, mouseY)) {
            scroll + ((mouseY - y) / ROW_H).toInt()
        } else {
            -1
        }
        g.enableScissor(x, y, x + LIST_W, y + LIST_H)
        val last = minOf(rows.lastIndex, scroll + VISIBLE_ROWS - 1)
        for (index in scroll..last) {
            val rowY = y + (index - scroll) * ROW_H
            when {
                index == selected -> g.fill(x, rowY, x + LIST_W, rowY + ROW_H - 1, COLOR_SELECTED)
                index == hoveredRow -> g.fill(x, rowY, x + LIST_W, rowY + ROW_H - 1, COLOR_HOVER)
            }
            val color = if (index == selected) COLOR_ROW_SELECTED else COLOR_ROW
            g.drawString(font, font.plainSubstrByWidth(rows[index].label, LIST_W - 8), x + 4, rowY + 1, color, false)
        }
        g.disableScissor()

        if (rows.size > VISIBLE_ROWS) {
            val trackX = x + LIST_W - 3
            val thumbHeight = maxOf(16, LIST_H * VISIBLE_ROWS / rows.size)
            val travel = LIST_H - thumbHeight
            val thumbY = y + travel * scroll / (rows.size - VISIBLE_ROWS).coerceAtLeast(1)
            g.fill(trackX, y, trackX + 2, y + LIST_H, COLOR_BORDER)
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, COLOR_ROW)
        }
        if (rowsSource != null && rows.size == 1) {
            g.drawString(font, NO_CLIPS_HINT, x + 4, y + LIST_H - 12, COLOR_DIM, false)
        }
    }

    private fun drawTime(g: GuiGraphics) {
        val row = rows.getOrNull(selected)
        val time = clipState?.getTime()
        val duration = row?.clip?.duration
        val text = if (time != null && duration != null) {
            String.format(Locale.ROOT, "%.1f/%.1fs", time, duration)
        } else {
            ""
        }
        g.drawString(font, text, left + VIEW_X + 2, top + BUTTON_Y + 5, COLOR_DIM, false)
    }

    /**
     * Draws the offscreen color texture as a quad. 1.21.1 keeps GUI projections/modelview in RenderSystem and the
     * texture id in its own shader-texture slot, so this is the plain `position_tex` path with the FBO's
     * bottom-left origin flipped into GUI coordinates.
     */
    private fun blitPreview(g: GuiGraphics, textureId: Int, x: Int, y: Int) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader)
        RenderSystem.setShaderTexture(0, textureId)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        // The GLB pass binds its samplers with raw GL calls, so GlStateManager's cached unit-0 binding can disagree
        // with the driver. Normalizing the cache to 0 guarantees the texId bind below actually reaches GL (and the
        // sampler bind inside drawWithShader then finds the cache already correct).
        GlStateManager._activeTexture(GL13.GL_TEXTURE0)
        GlStateManager._bindTexture(0)
        GlStateManager._bindTexture(textureId)

        val matrix = g.pose().last().pose()
        val builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX)
        // v=0 is the FBO's bottom row, so the quad's top edge samples v=1.
        builder.addVertex(matrix, x.toFloat(), (y + VIEW_SIZE).toFloat(), 0f).setUv(0f, 0f)
        builder.addVertex(matrix, (x + VIEW_SIZE).toFloat(), (y + VIEW_SIZE).toFloat(), 0f).setUv(1f, 0f)
        builder.addVertex(matrix, (x + VIEW_SIZE).toFloat(), y.toFloat(), 0f).setUv(1f, 1f)
        builder.addVertex(matrix, x.toFloat(), y.toFloat(), 0f).setUv(0f, 1f)
        BufferUploader.drawWithShader(builder.buildOrThrow())
    }

    // --- input ---

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == LEFT_BUTTON) {
            if (insideViewport(mouseX, mouseY)) {
                dragButton = LEFT_BUTTON
                return true
            }
            if (insideList(mouseX, mouseY)) {
                val index = scroll + ((mouseY - (top + LIST_Y)) / ROW_H).toInt()
                if (index in rows.indices) {
                    select(index)
                    return true
                }
            }
        } else if (button == RIGHT_BUTTON && insideViewport(mouseX, mouseY)) {
            dragButton = RIGHT_BUTTON
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == dragButton) dragButton = -1
        return super.mouseReleased(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (button != dragButton || !insideViewport(mouseX, mouseY)) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
        }
        if (button == LEFT_BUTTON) {
            orbitYaw -= dragX.toFloat() * DRAG_SENSITIVITY
            orbitPitch = (orbitPitch + dragY.toFloat() * DRAG_SENSITIVITY).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
        } else if (button == RIGHT_BUTTON) {
            val units = unitsPerPixel()
            panX = (panX - dragX.toFloat() * units).coerceIn(-PAN_LIMIT, PAN_LIMIT)
            panY = (panY + dragY.toFloat() * units).coerceIn(-PAN_LIMIT, PAN_LIMIT)
        } else {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
        }
        return true
    }

    /** Model-space units covered by one GUI pixel at the current distance, so a grab tracks the cursor. */
    private fun unitsPerPixel(): Float =
        (2f * FOV_TAN_HALF * cameraDistance) / VIEW_SIZE

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (insideViewport(mouseX, mouseY)) {
            zoom = (zoom * if (scrollY > 0) ZOOM_STEP else 1f / ZOOM_STEP).coerceIn(ZOOM_MIN, ZOOM_MAX)
            return true
        }
        if (insideList(mouseX, mouseY) && rows.size > VISIBLE_ROWS) {
            scroll = (scroll + if (scrollY > 0) -1 else 1).coerceIn(0, rows.size - VISIBLE_ROWS)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        return when (keyCode) {
            KEY_UP -> {
                select(selected - 1)
                true
            }
            KEY_DOWN -> {
                select(selected + 1)
                true
            }
            KEY_SPACE -> {
                togglePlay()
                true
            }
            else -> super.keyPressed(keyCode, scanCode, modifiers)
        }
    }

    private fun insideViewport(mouseX: Double, mouseY: Double): Boolean =
        mouseX >= left + VIEW_X && mouseX < left + VIEW_X + VIEW_SIZE &&
            mouseY >= top + VIEW_Y && mouseY < top + VIEW_Y + VIEW_SIZE

    private fun insideList(mouseX: Double, mouseY: Double): Boolean =
        mouseX >= left + LIST_X && mouseX < left + LIST_X + LIST_W &&
            mouseY >= top + LIST_Y && mouseY < top + LIST_Y + LIST_H

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        // Back to the editor that opened us; null (the NPC vanished) closes to the game.
        minecraft?.setScreen(returnTo())
    }

    override fun removed() {
        super.removed()
        instance?.decreaseReferenceCount()
        instance = null
        clipItem = null
        clipState = null
    }

    /** Wall-clock animation source; unset properties read as null, which glTF clips do not query. */
    private class PreviewClock : AnimationContext {
        var tick = 0L
        var delta = 0f

        override fun getGameTick(): Long = tick
        override fun getDeltaTick(): Float = delta

        @Suppress("UNCHECKED_CAST")
        override fun <T> getProperty(type: AnimationContext.Property<T>): T? = when (type) {
            AnimationContext.Property.GameTick -> MutableLong(tick)
            AnimationContext.Property.DeltaTick -> MutableFloat(delta)
            else -> null
        } as T

        override fun getPropertyTypes(): Set<AnimationContext.Property<*>> = setOf(
            AnimationContext.Property.GameTick,
            AnimationContext.Property.DeltaTick,
        )
    }

    private companion object {
        const val PANEL_W = 420
        const val PANEL_H = 230

        const val VIEW_X = 8
        const val VIEW_Y = 22
        const val VIEW_SIZE = 180

        const val LIST_X = 196
        const val LIST_Y = 22
        const val LIST_W = 216
        const val LIST_H = 180
        const val ROW_H = 11
        const val VISIBLE_ROWS = 16

        const val BUTTON_Y = 206
        const val BUTTON_H = 18
        const val BUTTON_W = 64
        const val BUTTON_GAP = 8

        const val BIND_POSE = "(Bind pose)"
        const val NO_CLIPS_HINT = "This model has no animation clips."
        val LOADING_HINT: Component = Component.literal("Loading model...")
        const val HEADER_HINT = "Left drag rotate · Right drag pan · Wheel zoom"

        const val SECONDS_PER_TICK = 0.05f
        const val FULL_BRIGHT = 15728880
        const val CAMERA_DISTANCE_FACTOR = 1.9f
        const val DRAG_SENSITIVITY = 1.0f
        const val PITCH_LIMIT = 80f
        const val ZOOM_STEP = 0.9f
        const val ZOOM_MIN = 0.35f
        const val ZOOM_MAX = 3f

        const val DEFAULT_YAW = 180f
        const val DEFAULT_PITCH = 8f
        // Generous in model units: an arms rig at 3x scale is still reachable, and Reset undoes any drift.
        const val PAN_LIMIT = 10f

        const val LEFT_BUTTON = 0
        const val RIGHT_BUTTON = 1
        const val KEY_UP = 265
        const val KEY_DOWN = 264
        const val KEY_SPACE = 32

        const val COLOR_TITLE = 0xFFFFFF
        const val COLOR_ROW = 0xD0D0D0
        const val COLOR_ROW_SELECTED = 0xFFFFFF
        const val COLOR_DIM = 0xA0A0A0
        const val COLOR_BORDER = 0xFF5A5A5A.toInt()
        const val COLOR_SELECTED = 0xFF2F4F6F.toInt()
        const val COLOR_HOVER = 0x40FFFFFF
        val PANEL_BG = 0xF01E1E1E.toInt()
        val VIEWPORT_BG = 0xFF141418.toInt()
        val LIST_BG = 0xFF101010.toInt()

        const val FOV_DEGREES = 35.0
        val FOV_TAN_HALF = tan(Math.toRadians(FOV_DEGREES * 0.5)).toFloat()
        val PROJECTION = Matrix4f().perspective(Math.toRadians(FOV_DEGREES).toFloat(), 1f, 0.05f, 100f)
    }
}
