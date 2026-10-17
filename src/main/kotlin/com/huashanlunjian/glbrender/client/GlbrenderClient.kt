package com.huashanlunjian.glbrender.client

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.logging.LogUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.minecraft.client.CameraType
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.util.Mth
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.RenderArmEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.event.RenderPlayerEvent
import net.neoforged.neoforge.client.settings.KeyConflictContext
import net.neoforged.neoforge.common.NeoForge
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import top.fifthlight.blazerod.gl.Gl
import top.fifthlight.blazerod.gl.GlFramebuffers
import com.huashanlunjian.glbrender.render.MinecraftSamplers
import com.huashanlunjian.glbrender.render.RenderSystemFrameContext
import top.fifthlight.blazerod.gl.GpuTexture
import top.fifthlight.blazerod.gl.GpuTextureView
import top.fifthlight.blazerod.gl.TextureFormat
import top.fifthlight.blazerod.model.HumanoidTag
import top.fifthlight.blazerod.model.animation.Animation
import top.fifthlight.blazerod.model.loader.LoadContext
import top.fifthlight.blazerod.model.loader.LoadResult
import top.fifthlight.blazerod.model.loader.ModelFileLoader
import top.fifthlight.blazerod.render.api.animation.AnimationItem
import top.fifthlight.blazerod.render.api.loader.ModelLoader
import top.fifthlight.blazerod.render.api.render.Renderer
import top.fifthlight.blazerod.render.api.render.GlbRenderTarget
import top.fifthlight.blazerod.render.api.render.RendererTypeHolder
import top.fifthlight.blazerod.render.api.render.drawScene
import top.fifthlight.blazerod.render.api.resource.ModelInstance
import top.fifthlight.blazerod.render.api.resource.RenderScene
import top.fifthlight.blazerod.render.common.BlazeRod
import top.fifthlight.blazerod.render.common.runtime.uniform.UniformBuffer
import top.fifthlight.blazerod.render.runtime.animation.AnimationItemImpl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.math.sqrt

object GlbrenderClient {
    private val LOGGER = LogUtils.getLogger()

    private const val FULL_BRIGHT = 15728880
    private const val WORLD_NAME = "glbrender-test"
    private const val CAPTURE_AFTER_FRAMES = 40
    private const val WATCHDOG_TICKS = 3600
    private const val SIDE_OFFSET = 1.0
    private const val FRONT_OFFSET = 2.0

    private val TOGGLE_MODEL = KeyMapping(
        "key.glbrender.toggleModel",
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_U,
        "key.categories.misc",
    )

    private var renderer: Renderer<*, *>? = null
    private var scene: RenderScene? = null
    private var instance: ModelInstance? = null
    private var animationItems: List<AnimationItem> = emptyList()
    private var clipMachine: ClipStateMachine? = null

    private var loadStarted = false
    private var joinStarted = false
    private var cameraForced = false
    private var loggedTarget = false
    private var broken = false
    private var clientTicks = 0
    private var origin: Vector3d? = null
    private var drawnFrames = 0
    private var awaitingBaseline = false
    private var drawnPixels: ByteBuffer? = null
    private var frameInitialized = false
    private val pendingPlayers = mutableListOf<PendingPlayer>()
    private var lastColorId = -1
    private var lastDepthId = -1
    private var lastWidth = -1
    private var lastHeight = -1

    private class PendingPlayer(
        val player: Player,
        val x: Double,
        val y: Double,
        val z: Double,
        val partialTick: Float,
        val packedLight: Int,
        val bodyYaw: Float,
    )

    private val captureEnabled = System.getProperty("glbrender.capture", "false").toBoolean()
    private val autoJoin = System.getProperty("glbrender.autoJoin", "false").toBoolean()
    private val ignoreDepth = System.getProperty("glbrender.ignoreDepth", "false").toBoolean()
    private val replacePlayer = System.getProperty("glbrender.replacePlayer", "true").toBoolean()
    private val yawOffset = System.getProperty("glbrender.yawOffset", "180").toFloat()
    // Periodic draw-status heartbeat used to diagnose the "renders then goes invisible" class of bug.
    // Off by default now that the root cause (dimension-blind framebuffer cache key) is fixed.
    private val diagHeartbeat = System.getProperty("glbrender.diag.heartbeat", "false").toBoolean()
    // Clip playback. When the model carries glTF clips this drives the state machine instead of the procedural
    // VanillaPlayerPoseDriver; for a clip-less model (deepseek-chan) it is inert, so the verified procedural path
    // is unchanged. Read once at load, so restart to toggle.
    private val clipsEnabled = System.getProperty("glbrender.clips", "true").toBoolean()
    // Test hook: force third-person-back on world load so a headless run draws the whole body (in first-person
    // only the arm draws, and a local player standing still never moves, so a body capture would be empty).
    private val forceThirdPerson = System.getProperty("glbrender.thirdPerson", "false").toBoolean()

    // First-person arm. "off" leaves vanilla's arm alone, "hide" cancels it and draws nothing,
    // "arm" cancels it and draws the GLB in view space with the transform below. The transform is a
    // starting guess and is meant to be tuned per model; it is read once at load, so restart to change.
    private val fpMode = System.getProperty("glbrender.fp.mode", "arm")
    private val fpX = System.getProperty("glbrender.fp.x", "0.35").toFloat()
    private val fpY = System.getProperty("glbrender.fp.y", "-0.45").toFloat()
    private val fpZ = System.getProperty("glbrender.fp.z", "-0.55").toFloat()
    private val fpScale = System.getProperty("glbrender.fp.scale", "1.0").toFloat()
    private val fpYaw = Math.toRadians(System.getProperty("glbrender.fp.yaw", "0").toDouble()).toFloat()
    private val fpPitch = Math.toRadians(System.getProperty("glbrender.fp.pitch", "0").toDouble()).toFloat()
    private val fpRoll = Math.toRadians(System.getProperty("glbrender.fp.roll", "0").toDouble()).toFloat()
    private var lastPartialTick = 0f

    // Third-person held items. Cancelling RenderPlayerEvent.Pre also kills vanilla's ItemInHandLayer, so the
    // ItemStack has to be re-drawn by hand. Ported from ArmorStand's renderHeldItem: the item is placed at the
    // GLB hand bone's animated world transform and rendered through vanilla's ItemRenderer. Defaults match
    // ArmorStand's GlobalConfig (rotX -90, rotY 180, scale 0.9) and are meant to be tuned per model.
    private val heldItems = System.getProperty("glbrender.heldItems", "true").toBoolean()
    private val heldItemScale = System.getProperty("glbrender.heldItem.scale", "0.9").toFloat()
    private val heldItemOffsetX = System.getProperty("glbrender.heldItem.offsetX", "0").toFloat()
    private val heldItemOffsetY = System.getProperty("glbrender.heldItem.offsetY", "0").toFloat()
    private val heldItemOffsetZ = System.getProperty("glbrender.heldItem.offsetZ", "0").toFloat()
    private val heldItemRotX = System.getProperty("glbrender.heldItem.rotX", "-90").toFloat()
    private val heldItemRotY = System.getProperty("glbrender.heldItem.rotY", "180").toFloat()
    private val heldItemRotZ = System.getProperty("glbrender.heldItem.rotZ", "0").toFloat()

    private var drawEnabled = true

    fun init(modEventBus: IEventBus) {
        BlazeRod.mainDispatcher = RenderThreadDispatcher
        Gl.frameContext = RenderSystemFrameContext
        Gl.samplers = MinecraftSamplers
        modEventBus.addListener(
            EventPriority.NORMAL,
            RegisterKeyMappingsEvent::class.java,
            Consumer { it.register(TOGGLE_MODEL) },
        )
        NeoForge.EVENT_BUS.addListener(
            EventPriority.NORMAL,
            RenderLevelStageEvent::class.java,
            Consumer { onRenderLevelStage(it) },
        )
        NeoForge.EVENT_BUS.addListener(
            EventPriority.NORMAL,
            ClientTickEvent.Post::class.java,
            Consumer { onClientTick() },
        )
        if (replacePlayer) {
            NeoForge.EVENT_BUS.addListener(
                EventPriority.NORMAL,
                RenderPlayerEvent.Pre::class.java,
                Consumer { onRenderPlayerPre(it) },
            )
            if (fpMode != "off") {
                NeoForge.EVENT_BUS.addListener(
                    EventPriority.NORMAL,
                    RenderArmEvent::class.java,
                    Consumer { onRenderArm(it) },
                )
            }
        }
    }

    private fun onClientTick() {
        frameInitialized = false
        pendingPlayers.clear()
        val minecraft = Minecraft.getInstance()
        if (TOGGLE_MODEL.consumeClick()) {
            drawEnabled = !drawEnabled
            LOGGER.info("glbrender: model {} (fps {})", if (drawEnabled) "ON" else "OFF", minecraft.fps)
        }
        if (minecraft.level != null && ++clientTicks % 100 == 0) {
            LOGGER.info("glbrender: fps={} model={} drawnFrames={}", minecraft.fps, drawEnabled, drawnFrames)
        }
        if (forceThirdPerson && minecraft.level != null && !cameraForced) {
            cameraForced = true
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK)
            LOGGER.info("glbrender: forced third-person camera")
        }
        if (captureEnabled && clientTicks > WATCHDOG_TICKS) {
            LOGGER.error("glbrender: no model drawn after {} ticks, quitting", WATCHDOG_TICKS)
            minecraft.stop()
            return
        }
        if (!autoJoin || joinStarted || minecraft.level != null || minecraft.gui == null) {
            return
        }
        joinStarted = true
        minecraft.execute { createAndJoinWorld(minecraft) }
    }

    private fun createAndJoinWorld(minecraft: Minecraft) {
        try {
            LOGGER.info("glbrender: creating world {}", WORLD_NAME)
            WorldOpenFlows(minecraft, minecraft.levelSource).createFreshLevel(
                WORLD_NAME,
                LevelSettings(
                    WORLD_NAME,
                    GameType.CREATIVE,
                    true,
                    Difficulty.PEACEFUL,
                    false,
                    GameRules(),
                    WorldDataConfiguration.DEFAULT,
                ),
                WorldOptions(20261016L, true, false),
                { WorldPresets.createNormalWorldDimensions(it) },
                null,
            )
        } catch (error: Throwable) {
            LOGGER.error("glbrender: could not create the test world", error)
        }
    }

    private fun ensureFrameInitialized() {
        if (frameInitialized) return
        frameInitialized = true
        UniformBuffer.clear()
        renderer?.rotate()
    }

    /**
     * Only suppresses vanilla's player draw and remembers where the player was. The GLB itself is drawn
     * later at AFTER_ENTITIES, where the frame's own camera-rotation matrix is handed to us directly.
     * Drawing inside this event instead would inherit whatever pose vanilla already put on the stack
     * for the current view - third-person front mirrors it - which sends the model off screen.
     */
    private fun onRenderPlayerPre(event: RenderPlayerEvent.Pre) {
        if (broken || !drawEnabled) return
        if (instance == null || scene == null || renderer == null) return
        event.isCanceled = true
        val pos = event.entity.getPosition(event.partialTick)
        val bodyYaw = Mth.rotLerp(event.partialTick, event.entity.yBodyRotO, event.entity.yBodyRot)
        pendingPlayers.add(
            PendingPlayer(event.entity, pos.x, pos.y, pos.z, event.partialTick, event.packedLight, bodyYaw),
        )
    }

    private fun drawPendingPlayers(event: RenderLevelStageEvent) {
        if (pendingPlayers.isEmpty()) {
            return
        }
        val instance = instance ?: return
        val scene = scene ?: return
        val renderer = renderer ?: return
        val cameraPosition = event.camera.position
        try {
            for (pending in pendingPlayers) {
                // Clip playback wins when the model carries glTF clips; the procedural driver is skipped so its
                // RELATIVE_ANIMATION writes don't stack on top of the clip's ABSOLUTE writes. No clips -> the
                // verified procedural path runs byte-for-byte unchanged.
                val clipMachine = clipMachine
                val clipDrove = clipMachine != null &&
                    clipMachine.apply(instance, pending.player, pending.partialTick)
                if (!clipDrove) {
                    VanillaPlayerPoseDriver.apply(instance, pending.player, pending.partialTick)
                }
                instance.updateRenderData(pending.partialTick)
                val target = Minecraft.getInstance().mainRenderTarget
                val device = Gl.device
                val color = GpuTexture.adopt(target.colorTextureId, TextureFormat.RGBA8)
                val depth = GpuTexture.adopt(target.depthTextureId, TextureFormat.DEPTH24)
                val colorView = device.createTextureView(color)
                val depthView = device.createTextureView(depth)
                diagnoseTarget(color, depth, colorView, depthView)
                // Vanilla orients entities with Axis.YP.rotationDegrees(180 - yBodyRot); mirror that so
                // the model turns with the player. yawOffset covers rigs whose forward axis is not -Z.
                val yawRadians = Math.toRadians((180f - pending.bodyYaw + yawOffset).toDouble()).toFloat()
                val bodyMatrix = Matrix4f()
                    .translation(
                        (pending.x - cameraPosition.x).toFloat(),
                        (pending.y - cameraPosition.y).toFloat(),
                        (pending.z - cameraPosition.z).toFloat(),
                    )
                    .rotateY(yawRadians)
                val stack = RenderSystem.getModelViewStack()
                stack.pushMatrix()
                stack.set(event.modelViewMatrix)
                RenderSystem.applyModelViewMatrix()
                try {
                    if (ignoreDepth) GL11.glDisable(GL11.GL_DEPTH_TEST)
                    renderer.drawScene(instance, GlbRenderTarget(colorView, depthView), bodyMatrix, pending.packedLight)
                    drawnFrames++
                } finally {
                    if (ignoreDepth) GL11.glEnable(GL11.GL_DEPTH_TEST)
                    stack.popMatrix()
                    RenderSystem.applyModelViewMatrix()
                }
                if (heldItems) {
                    renderHeldItems(instance, scene, pending, bodyMatrix, event.modelViewMatrix)
                }
            }
        } catch (error: RuntimeException) {
            LOGGER.error("glbrender: player draw failed", error)
            broken = true
        } finally {
            pendingPlayers.clear()
        }
    }

    // Scratch matrices for the held-item pass; reused every frame to avoid per-draw allocation.
    private val heldItemPose = PoseStack()
    private val handWorldMatrix = Matrix4f()
    private val handWorldPos = Vector3f()
    private val handWorldRot = Quaternionf()
    private val itemLocalMatrix = Matrix4f()
    private val itemNormalMatrix = Matrix3f()

    /**
     * Re-draws the player's held items, which vanished when [onRenderPlayerPre] cancelled the whole vanilla
     * player render (ItemInHandLayer included). Ported from ArmorStand's `PlayerRenderer.renderHeldItem`: each
     * item is placed at its hand bone's animated world transform and rendered through vanilla's own
     * [net.minecraft.client.renderer.entity.ItemRenderer], so blocks, tools, enchant glint and animated
     * textures all behave exactly as in vanilla. The GLB body itself is drawn by the shim; only the ItemStack
     * goes through this path.
     */
    private fun renderHeldItems(
        instance: ModelInstance,
        scene: RenderScene,
        pending: PendingPlayer,
        bodyMatrix: Matrix4f,
        modelView: Matrix4f,
    ) {
        val player = pending.player
        val mainHand = player.mainHandItem
        val offHand = player.offhandItem
        val mainIsRight = player.mainArm == HumanoidArm.RIGHT
        val bufferSource = Minecraft.getInstance().renderBuffers().bufferSource()
        val poseStack = heldItemPose
        // The item lives in the same camera space the body was drawn in: start from the level modelview
        // (camera rotation), then apply the body matrix so the hand transform lands where the GLB hand is.
        val mvStack = RenderSystem.getModelViewStack()
        mvStack.pushMatrix()
        mvStack.set(modelView)
        RenderSystem.applyModelViewMatrix()
        try {
            renderHeldItem(
                instance, scene, player,
                if (mainIsRight) mainHand else offHand,
                ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, HumanoidTag.RIGHT_HAND,
                bodyMatrix, poseStack, bufferSource, pending.packedLight, false,
            )
            renderHeldItem(
                instance, scene, player,
                if (mainIsRight) offHand else mainHand,
                ItemDisplayContext.THIRD_PERSON_LEFT_HAND, HumanoidTag.LEFT_HAND,
                bodyMatrix, poseStack, bufferSource, pending.packedLight, true,
            )
            bufferSource.endBatch()
        } finally {
            mvStack.popMatrix()
            RenderSystem.applyModelViewMatrix()
        }
    }

    private fun renderHeldItem(
        instance: ModelInstance,
        scene: RenderScene,
        player: Player,
        itemStack: ItemStack,
        displayContext: ItemDisplayContext,
        tag: HumanoidTag,
        bodyMatrix: Matrix4f,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        light: Int,
        leftHanded: Boolean,
    ) {
        if (itemStack.isEmpty) return
        val nodeIndex = VanillaPlayerPoseDriver.nodeIndex(tag) ?: return
        // Hand bone world transform, in model space, with animation already applied by updateRenderData.
        instance.copyNodeWorldTransform(nodeIndex, handWorldMatrix)
        handWorldMatrix.getTranslation(handWorldPos)
        handWorldMatrix.getUnnormalizedRotation(handWorldRot)
        handWorldRot.normalize()

        // Compose: body placement -> scene render transform -> hand bone (rotation+translation only, so a
        // bone's baked scale does not stretch the item) -> tunable item rotation/offset -> uniform item scale.
        itemLocalMatrix.set(bodyMatrix)
        scene.renderTransform?.applyOnMatrix(itemLocalMatrix)
        itemLocalMatrix.translationRotate(
            handWorldPos.x, handWorldPos.y, handWorldPos.z,
            handWorldRot.x, handWorldRot.y, handWorldRot.z, handWorldRot.w,
        )
        itemLocalMatrix.rotateX(Math.toRadians(heldItemRotX.toDouble()).toFloat())
        itemLocalMatrix.rotateY(Math.toRadians(heldItemRotY.toDouble()).toFloat())
        itemLocalMatrix.rotateZ(Math.toRadians(heldItemRotZ.toDouble()).toFloat())
        val handSign = if (tag == HumanoidTag.LEFT_HAND) -1f else 1f
        itemLocalMatrix.translate(handSign * heldItemOffsetX, heldItemOffsetY, heldItemOffsetZ)
        itemLocalMatrix.scale(heldItemScale)

        poseStack.pushPose()
        val pose = poseStack.last().pose()
        pose.set(itemLocalMatrix)
        poseStack.last().normal().set(itemNormalMatrix.set(pose).invert().transpose())
        Minecraft.getInstance().itemRenderer.renderStatic(
            player, itemStack, displayContext, leftHanded,
            poseStack, bufferSource, player.level(), light, OverlayTexture.NO_OVERLAY, 0,
        )
        poseStack.popPose()
    }

    /**
     * Explains the "renders for a while then goes invisible" class of bug. A window/fullscreen/minimise
     * change reallocates Minecraft's main-target textures (and GL reuses the ids), which can leave a cached
     * framebuffer pointing at freed attachments; the draw still runs (drawnFrames climbs, no exception) but
     * writes into a dead target. Log every change, drop the whole framebuffer cache when it happens, and
     * emit a periodic heartbeat with the live framebuffer status and matrices so a failing run is diagnosable
     * from latest.log alone.
     */
    private fun diagnoseTarget(color: GpuTexture, depth: GpuTexture, colorView: GpuTextureView, depthView: GpuTextureView) {
        if (color.id != lastColorId || depth.id != lastDepthId || color.width != lastWidth || color.height != lastHeight) {
            LOGGER.info(
                "glbrender: render target changed to {}x{} color={} depth={} (was {}x{} color={} depth={}); dropped {} cached framebuffer(s)",
                color.width, color.height, color.id, depth.id,
                lastWidth, lastHeight, lastColorId, lastDepthId, GlFramebuffers.cacheSize,
            )
            GlFramebuffers.invalidateAll()
            lastColorId = color.id
            lastDepthId = depth.id
            lastWidth = color.width
            lastHeight = color.height
        }
        if (!diagHeartbeat || drawnFrames % 120 != 0) return
        val previousBinding = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
        val fbo = GlFramebuffers.framebufferFor(colorView, depthView)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousBinding)
        LOGGER.info(
            "glbrender: draw heartbeat target={}x{} fbo={} status=0x{} proj00={} mv00={} fboCache={}",
            color.width, color.height, fbo, status.toString(16),
            RenderSystem.getProjectionMatrix().m00(), RenderSystem.getModelViewMatrix().m00(),
            GlFramebuffers.cacheSize,
        )
    }

    /**
     * Replaces the first-person arm. `RenderPlayerEvent.Pre` never fires for the local player's arm - it is
     * drawn through `PlayerRenderer.renderRightHand/renderLeftHand`, which posts this event instead - so this
     * is the only hook. Cancelling drops vanilla's arm; "arm" mode then draws the GLB in its place. Held
     * items are rendered separately and are untouched.
     */
    private fun onRenderArm(event: RenderArmEvent) {
        if (broken || !drawEnabled) return
        if (instance == null || scene == null || renderer == null) return
        event.isCanceled = true
        if (fpMode == "arm") {
            drawFirstPersonArm(event)
        }
    }

    private fun drawFirstPersonArm(event: RenderArmEvent) {
        val instance = instance ?: return
        val scene = scene ?: return
        val renderer = renderer ?: return
        val partialTick = lastPartialTick
        try {
            val clipMachine = clipMachine
            val clipDrove = clipMachine != null &&
                clipMachine.apply(instance, event.player, partialTick)
            if (!clipDrove) {
                VanillaPlayerPoseDriver.apply(instance, event.player, partialTick)
            }
            instance.updateRenderData(partialTick)
            val target = Minecraft.getInstance().mainRenderTarget
            val device = Gl.device
            val color = GpuTexture.adopt(target.colorTextureId, TextureFormat.RGBA8)
            val depth = GpuTexture.adopt(target.depthTextureId, TextureFormat.DEPTH24)
            val colorView = device.createTextureView(color)
            val depthView = device.createTextureView(depth)
            diagnoseTarget(color, depth, colorView, depthView)

            // renderItemInHand already set the modelview to the camera rotation and the projection to the
            // hand FOV; vanilla keeps the arm screen-locked by pre-inverting the camera rotation into its
            // pose stack. Do the same: instance = modelView^-1 * armTransform, so the camera rotation cancels
            // and the arm stays fixed to the view instead of swinging as you look around.
            val armTransform = Matrix4f()
                .translation(fpX, fpY, fpZ)
                .rotateYXZ(fpYaw, fpPitch, fpRoll)
                .scale(fpScale)
            val matrix = Matrix4f(RenderSystem.getModelViewMatrix()).invert().mul(armTransform)
            if (ignoreDepth) GL11.glDisable(GL11.GL_DEPTH_TEST)
            try {
                renderer.drawScene(instance, GlbRenderTarget(colorView, depthView), matrix, event.packedLight)
                drawnFrames++
            } finally {
                if (ignoreDepth) GL11.glEnable(GL11.GL_DEPTH_TEST)
            }
        } catch (error: RuntimeException) {
            LOGGER.error("glbrender: first-person arm draw failed", error)
            broken = true
        }
    }

    private fun onRenderLevelStage(event: RenderLevelStageEvent) {
        lastPartialTick = event.partialTick.getGameTimeDeltaPartialTick(true)
        if (event.stage === RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            if (!broken && replacePlayer) {
                ensureFrameInitialized()
                drawPendingPlayers(event)
            }
            return
        }
        if (event.stage !== RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return
        }
        val minecraft = Minecraft.getInstance()
        if (broken) return
        startLoad()
        ensureFrameInitialized()

        // In replace-player mode the draw already happened at AFTER_ENTITIES; this stage only serves
        // the unattended capture verification there.
        if (replacePlayer) {
            if (captureEnabled && drawnFrames >= CAPTURE_AFTER_FRAMES && !awaitingBaseline && drawnPixels == null) {
                val target = minecraft.mainRenderTarget
                val device = Gl.device
                val color = GpuTexture.adopt(target.colorTextureId, TextureFormat.RGBA8)
                val depth = GpuTexture.adopt(target.depthTextureId, TextureFormat.DEPTH24)
                val colorView = device.createTextureView(color)
                val depthView = device.createTextureView(depth)
                drawnPixels = readTarget(colorView, depthView)
                awaitingBaseline = true
            }
            if (awaitingBaseline && drawnPixels != null) {
                val target = minecraft.mainRenderTarget
                val device = Gl.device
                val color = GpuTexture.adopt(target.colorTextureId, TextureFormat.RGBA8)
                val depth = GpuTexture.adopt(target.depthTextureId, TextureFormat.DEPTH24)
                val colorView = device.createTextureView(color)
                val depthView = device.createTextureView(depth)
                awaitingBaseline = false
                verifyAgainstBaseline(minecraft, colorView, depthView)
            }
            return
        }

        if (!drawEnabled) return
        val instance = instance ?: return
        val scene = scene ?: return
        val renderer = renderer ?: return

        val anchor = anchor(event)
        val cameraPosition = event.camera.position

        val target = minecraft.mainRenderTarget
        val device = Gl.device
        val color = GpuTexture.adopt(target.colorTextureId, TextureFormat.RGBA8)
        val depth = GpuTexture.adopt(target.depthTextureId, TextureFormat.DEPTH24)
        val colorView = device.createTextureView(color)
        val depthView = device.createTextureView(depth)
        if (!loggedTarget) {
            loggedTarget = true
            LOGGER.info(
                "glbrender: game target {}x{} color texture {} depth texture {}",
                color.width, color.height, target.colorTextureId, target.depthTextureId,
            )
        }

        instance.updateRenderData(event.renderTick.toFloat())
        if (awaitingBaseline) {
            awaitingBaseline = false
            verifyAgainstBaseline(minecraft, colorView, depthView)
            return
        }
        val task = instance.createRenderTask(
            Matrix4f().translation(
                (anchor.x - cameraPosition.x).toFloat(),
                (anchor.y - cameraPosition.y).toFloat(),
                (anchor.z - cameraPosition.z).toFloat(),
            ),
            light = FULL_BRIGHT,
        )
        val stack = RenderSystem.getModelViewStack()
        stack.pushMatrix()
        stack.set(event.modelViewMatrix)
        RenderSystem.applyModelViewMatrix()
        try {
            if (ignoreDepth) GL11.glDisable(GL11.GL_DEPTH_TEST)
            renderer.render(colorView, depthView, task, scene)
            drawnFrames++
        } catch (error: RuntimeException) {
            LOGGER.error("glbrender: draw failed", error)
            broken = true
        } finally {
            runCatching { task.release() }
            if (ignoreDepth) GL11.glEnable(GL11.GL_DEPTH_TEST)
            stack.popMatrix()
            RenderSystem.applyModelViewMatrix()
        }

        if (captureEnabled && drawnFrames == CAPTURE_AFTER_FRAMES) {
            drawnPixels = readTarget(colorView, depthView)
            awaitingBaseline = true
        }
    }

    private fun anchor(event: RenderLevelStageEvent): Vector3d {
        val camera = event.camera
        val player = camera.entity ?: Minecraft.getInstance().player
        val feet = player?.position() ?: camera.position
        val look = camera.lookVector
        var forwardX = look.x.toDouble()
        var forwardZ = look.z.toDouble()
        val length = sqrt(forwardX * forwardX + forwardZ * forwardZ)
        if (length < 1.0e-6) {
            forwardX = 0.0
            forwardZ = 1.0
        } else {
            forwardX /= length
            forwardZ /= length
        }
        val anchor = Vector3d(
            feet.x + forwardX * FRONT_OFFSET - forwardZ * SIDE_OFFSET,
            feet.y,
            feet.z + forwardZ * FRONT_OFFSET + forwardX * SIDE_OFFSET,
        )
        if (origin == null) {
            origin = anchor
            LOGGER.info("glbrender: drawing the test model beside the player at {}", anchor)
        }
        return anchor
    }

    private fun readTarget(color: GpuTextureView, depth: GpuTextureView): ByteBuffer {
        val width = color.texture.width
        val height = color.texture.height
        val framebuffer = GlFramebuffers.framebufferFor(color, depth)
        val savedRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer)
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, savedRead)
        Gl.checkError("capture ${width}x$height")
        pixels.rewind()
        return pixels
    }

    private fun verifyAgainstBaseline(minecraft: Minecraft, color: GpuTextureView, depth: GpuTextureView) {
        try {
            val withModel = drawnPixels ?: return
            val baseline = readTarget(color, depth)
            val width = color.texture.width
            val height = color.texture.height

            var changed = 0
            var minX = width
            var maxX = -1
            var minY = height
            var maxY = -1
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val o = (y * width + x) * 4
                    if ((0 until 4).any { withModel.get(o + it) != baseline.get(o + it) }) {
                        changed++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            LOGGER.info(
                "glbrender: {} of {} pixels changed by the draw, box={}..{}",
                changed, width * height,
                if (maxX < 0) "none" else "$minX,$minY",
                if (maxX < 0) "none" else "$maxX,$maxY",
            )
            writePng(withModel, width, height, "glbrender-capture.png")
            writePng(baseline, width, height, "glbrender-baseline.png")
            minecraft.stop()
        } catch (error: Throwable) {
            LOGGER.error("glbrender: capture failed", error)
        }
    }

    private fun writePng(pixels: ByteBuffer, width: Int, height: Int, name: String) {
        val image = NativeImage(NativeImage.Format.RGBA, width, height, false)
        for (y in 0 until height) {
            val row = (height - 1 - y) * width * 4
            for (x in 0 until width) {
                val o = row + x * 4
                image.setPixelRGBA(
                    x, y,
                    (pixels.get(o).toInt() and 0xFF) or
                            ((pixels.get(o + 1).toInt() and 0xFF) shl 8) or
                            ((pixels.get(o + 2).toInt() and 0xFF) shl 16) or
                            ((pixels.get(o + 3).toInt() and 0xFF) shl 24),
                )
            }
        }
        val output = FMLPaths.GAMEDIR.get().resolve(name).toFile()
        image.writeToFile(output)
        image.close()
        LOGGER.info("glbrender: wrote capture {}", output.absolutePath)
    }

    private fun startLoad() {
        if (loadStarted) return
        loadStarted = true
        val modelPath = modelPath() ?: run {
            LOGGER.warn("glbrender: no test model at {}", defaultModelPath())
            return
        }
        CoroutineScope(RenderThreadDispatcher).launch {
            try {
                val loaded = parse(modelPath)
                val model = requireNotNull(loaded.model) { "Loader returned no model" }
                val loadedScene = ModelLoader.Factory.create().loadModel(model)
                    ?: error("Model loaded into no scene")
                scene = loadedScene
                instance = ModelInstance.Factory.of(loadedScene).also { it.increaseReferenceCount() }
                renderer = RendererTypeHolder.vertexShaderTransform.create()
                VanillaPlayerPoseDriver.resolve(loadedScene)
                prepareClips(loadedScene, loaded.animations ?: emptyList())
                LOGGER.info("glbrender: ready to draw {} ({} nodes, humanoidTags={}, clips={})",
                    modelPath, loadedScene.nodes.size, loadedScene.humanoidTagMap.keys, animationItems.size)
            } catch (error: Throwable) {
                LOGGER.error("glbrender: could not load $modelPath", error)
            }
        }
    }

    /**
     * Builds an [AnimationItem] for every glTF clip the model carries and logs how many of its channels
     * resolved onto this rig. Playback is still driven procedurally today; this is the groundwork the clip
     * state machine reads once an animated character (idle/walk/run clips) is dropped in, and it is inert for
     * a clip-less model like deepseek-chan, so it cannot change the verified procedural draw path.
     */
    private fun prepareClips(loadedScene: RenderScene, animations: List<Animation>) {
        if (animations.isEmpty()) {
            LOGGER.info("glbrender: model has no glTF clips, using procedural animation only")
            animationItems = emptyList()
            clipMachine = null
            return
        }
        animationItems = animations.mapNotNull { animation ->
            val item = runCatching { AnimationItem.Factory.load(loadedScene, animation) }
                .onFailure { LOGGER.warn("glbrender: could not load clip ${animation.name}", it) }
                .getOrNull() ?: return@mapNotNull null
            LOGGER.info(
                "glbrender: clip \"{}\": {} channels -> {} resolved, duration={}s",
                animation.name, animation.channels.size,
                (item as AnimationItemImpl).channels.size, item.duration,
            )
            item
        }
        val impls = animationItems.filterIsInstance<AnimationItemImpl>()
        clipMachine = if (clipsEnabled && impls.isNotEmpty()) {
            ClipStateMachine(impls).also {
                LOGGER.info("glbrender: clip state machine active with {} clip(s)", impls.size)
            }
        } else {
            null
        }
    }

    private suspend fun parse(modelPath: Path): LoadResult =
        withContext(Dispatchers.Default) {
            val fileLoader = ServiceLoader
                .load(ModelFileLoader::class.java, ModelFileLoader::class.java.classLoader)
                .toList()
                .firstOrNull { modelPath.extension.lowercase() in it.extensions.keys }
                ?: error("No ModelFileLoader for ${modelPath.extension}")
            val header = ByteBuffer.wrap(modelPath.inputStream().use { it.readNBytes(fileLoader.probeLength ?: 4) })
                .order(ByteOrder.LITTLE_ENDIAN)
            check(fileLoader.probe(header)) { "${fileLoader.javaClass.simpleName} rejected $modelPath" }
            fileLoader.load(modelPath, LoadContext.File(modelPath.parent))
        }

    private fun defaultModelPath(): Path =
        FMLPaths.GAMEDIR.get().resolve("models").resolve("deepseek-chan.glb")

    /**
     * Resolves `-Dglbrender.model=`. Tries the value as given (absolute or CWD-relative) first, then against the
     * run game dir, so a bare `CesiumMan.glb` or `models/CesiumMan.glb` works no matter what directory the run's
     * working directory is (NeoForge dev runs use `run/`, which breaks a `run/models/...` CWD-relative path).
     * With no config it falls back to the default deepseek-chan model.
     */
    private fun modelPath(): Path? {
        val configured = System.getProperty("glbrender.model")?.takeIf { it.isNotBlank() }
        if (configured == null) {
            return defaultModelPath().takeIf { it.toFile().isFile }
        }
        val given = Path.of(configured)
        val gameDir = FMLPaths.GAMEDIR.get()
        return sequenceOf(given, gameDir.resolve(configured), gameDir.resolve("models").resolve(configured))
            .firstOrNull { it.toFile().isFile }
            ?: run {
                LOGGER.warn("glbrender: -Dglbrender.model={} not found as given or under {}", configured, gameDir)
                null
            }
    }

    private object RenderThreadDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            Minecraft.getInstance().execute(block)
        }
    }
}
