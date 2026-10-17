package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.npc.GlbModelDescriptor
import com.huashanlunjian.glbrender.npc.GlbModels
import com.huashanlunjian.glbrender.npc.GlbNpcMenu
import com.huashanlunjian.glbrender.npc.net.SetConfigPacket
import com.huashanlunjian.glbrender.npc.net.SetModelPacket
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.neoforged.neoforge.network.PacketDistributor
import java.util.Locale

/**
 * The NPC editor's client screen — the CustomNPC-style tabbed panel opened on right-click (see
 * [com.huashanlunjian.glbrender.npc.GlbNpc.mobInteract]). It holds no item slots: the container exists purely to bind a
 * registered [net.minecraft.client.gui.screens.Screen] to a [net.minecraft.world.inventory.MenuType]; every edit travels
 * over the Step5-3 C2S packets. Because the edited fields are backed by `SynchedEntityData`, applying them server-side
 * echoes back to this client, so the world NPC (and the labels here) update live — that is the "preview".
 *
 * Three tabs:
 *  - Model: a grid of every id in the common [GlbModels] table; a click sends [SetModelPacket].
 *  - Transform: scale / yaw / ground-offset steppers; each click sends the full [SetConfigPacket].
 *  - Behavior: wander / follow-owner toggles; each flip sends the full [SetConfigPacket].
 *
 * Transform fields start from the NPC's effective value (its override, else the descriptor default) and, once touched,
 * send an explicit override — the [Float.NaN] "inherit" sentinel is only the untouched starting point, never sent.
 * A detached TLM-style [net.minecraft.client.gui.screens.inventory.InventoryScreen] 3D picker was deferred (opening a
 * second [Screen] over a live container churns the slot-listener/widget lifecycle, and GUI-space GLB rendering is a
 * separate effort from the in-world path).
 */
class GlbNpcEditorScreen(menu: GlbNpcMenu, inv: Inventory, title: Component) :
    AbstractContainerScreen<GlbNpcMenu>(menu, inv, title) {

    private val npcId: Int? = menu.npc?.id
    private val modelIds: List<String> = GlbModels.ids()

    /** The descriptor of the model open when the screen was created, used only for transform starting values. */
    private val startDescriptor: GlbModelDescriptor? = run {
        val id = menu.npc?.modelId?.takeIf { it.isNotEmpty() } ?: GlbModels.defaultId
        id?.let { GlbModels.descriptor(it) }
    }

    // Live UI state, seeded from the NPC's effective config so steppers start where the model currently renders.
    private var scaleVal: Float = 1.0f
    private var yawVal: Float = 0.0f
    private var groundVal: Float = 0.0f
    private var wanderVal: Boolean = true
    private var followVal: Boolean = false

    private var currentTab = TAB_MODEL

    private val tabButtons = ArrayList<Button>()
    private val modelButtons = ArrayList<Button>()
    private val transformButtons = ArrayList<Button>()
    private val behaviorButtons = ArrayList<Button>()
    private var wanderButton: Button? = null
    private var followButton: Button? = null

    init {
        imageWidth = 200
        imageHeight = 160
        seedTransformState()
    }

    private fun seedTransformState() {
        val config = menu.npc?.config
        val d = startDescriptor
        scaleVal = config?.scale?.takeUnless { it.isNaN() } ?: d?.scale ?: 1.0f
        yawVal = config?.yawOffset?.takeUnless { it.isNaN() } ?: d?.yawOffset ?: 0.0f
        groundVal = config?.groundOffsetY?.takeUnless { it.isNaN() } ?: d?.groundOffsetY ?: 0.0f
        wanderVal = config?.wander ?: true
        followVal = config?.followOwner ?: false
    }

    override fun init() {
        super.init()
        buildTabs()
        buildModelTab()
        buildTransformTab()
        buildBehaviorTab()
        addRenderableWidget(
            Button.builder(Component.literal("Close")) { onClose() }
                .pos(leftPos + imageWidth / 2 - 50, topPos + imageHeight - 26)
                .size(100, 20)
                .build(),
        )
        applyTabVisibility()
    }

    private fun buildTabs() {
        val labels = listOf("Model", "Transform", "Behavior")
        labels.forEachIndexed { index, label ->
            val button = addRenderableWidget(
                Button.builder(Component.literal(label)) { selectTab(index) }
                    .pos(leftPos + 8 + index * 62, topPos + 18)
                    .size(58, 16)
                    .build(),
            )
            tabButtons.add(button)
        }
    }

    private fun buildModelTab() {
        modelIds.forEachIndexed { index, id ->
            val col = index % GRID_COLUMNS
            val row = index / GRID_COLUMNS
            val button = addRenderableWidget(
                Button.builder(Component.literal(id)) {
                    if (npcId != null) PacketDistributor.sendToServer(SetModelPacket(npcId, id))
                }
                    .pos(leftPos + 10 + col * 92, topPos + CONTENT_TOP + row * 24)
                    .size(88, 20)
                    .build(),
            )
            modelButtons.add(button)
        }
    }

    private fun buildTransformTab() {
        // One -/+ button pair per row; the value text and label are drawn in render(), and each click commits the
        // full config so the world NPC updates live.
        addStepperRow(0, SCALE_STEP) { delta -> scaleVal = (scaleVal + delta).coerceIn(SCALE_MIN, SCALE_MAX) }
        addStepperRow(1, YAW_STEP) { delta -> yawVal = (yawVal + delta).coerceIn(YAW_MIN, YAW_MAX) }
        addStepperRow(2, GROUND_STEP) { delta -> groundVal = (groundVal + delta).coerceIn(GROUND_MIN, GROUND_MAX) }
    }

    private fun addStepperRow(row: Int, step: Float, apply: (Float) -> Unit) {
        val y = topPos + CONTENT_TOP + row * 26
        val minus = addRenderableWidget(
            Button.builder(Component.literal("-")) { apply(-step); sendConfig() }
                .pos(leftPos + 128, y).size(28, 18).build(),
        )
        val plus = addRenderableWidget(
            Button.builder(Component.literal("+")) { apply(step); sendConfig() }
                .pos(leftPos + 160, y).size(28, 18).build(),
        )
        transformButtons.add(minus)
        transformButtons.add(plus)
    }

    private fun buildBehaviorTab() {
        val wander = addRenderableWidget(
            Button.builder(Component.literal("Wander: ${onOff(wanderVal)}")) {
                wanderVal = !wanderVal
                wanderButton?.setMessage(Component.literal("Wander: ${onOff(wanderVal)}"))
                sendConfig()
            }
                .pos(leftPos + 10, topPos + CONTENT_TOP).size(180, 20).build(),
        )
        val follow = addRenderableWidget(
            Button.builder(Component.literal("Follow owner: ${onOff(followVal)}")) {
                followVal = !followVal
                followButton?.setMessage(Component.literal("Follow owner: ${onOff(followVal)}"))
                sendConfig()
            }
                .pos(leftPos + 10, topPos + CONTENT_TOP + 26).size(180, 20).build(),
        )
        wanderButton = wander
        followButton = follow
        behaviorButtons.add(wander)
        behaviorButtons.add(follow)
    }

    private fun selectTab(tab: Int) {
        currentTab = tab
        applyTabVisibility()
    }

    private fun applyTabVisibility() {
        modelButtons.forEach { it.visible = currentTab == TAB_MODEL }
        transformButtons.forEach { it.visible = currentTab == TAB_TRANSFORM }
        behaviorButtons.forEach { it.visible = currentTab == TAB_BEHAVIOR }
    }

    private fun sendConfig() {
        val id = npcId ?: return
        PacketDistributor.sendToServer(SetConfigPacket(id, scaleVal, yawVal, groundVal, wanderVal, followVal))
    }

    override fun renderBg(g: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xF0202020.toInt())
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)
        g.drawCenteredString(font, Component.literal("GLB NPC Editor"), leftPos + imageWidth / 2, topPos + 6, 0xFFFFFF)
        when (currentTab) {
            TAB_MODEL -> {
                val modelId = menu.npc?.modelId
                val label = if (modelId.isNullOrEmpty()) "<default>" else modelId
                g.drawString(font, "Current: $label", leftPos + 10, topPos + CONTENT_TOP - 10, 0xA0A0A0, false)
            }
            TAB_TRANSFORM -> {
                drawTransformRow(g, 0, "Scale", fmt(scaleVal, 2))
                drawTransformRow(g, 1, "Yaw", fmt(yawVal, 1))
                drawTransformRow(g, 2, "Ground Y", fmt(groundVal, 2))
            }
        }
    }

    private fun drawTransformRow(g: GuiGraphics, row: Int, label: String, value: String) {
        val y = topPos + CONTENT_TOP + row * 26 + 5
        g.drawString(font, label, leftPos + 10, y, 0xFFFFFF, false)
        g.drawString(font, value, leftPos + 76, y, 0xFFFF80, false)
    }

    private fun onOff(b: Boolean): String = if (b) "ON" else "OFF"

    private fun fmt(v: Float, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", v)

    private companion object {
        const val TAB_MODEL = 0
        const val TAB_TRANSFORM = 1
        const val TAB_BEHAVIOR = 2

        const val CONTENT_TOP = 46
        const val GRID_COLUMNS = 2

        const val SCALE_STEP = 0.05f
        const val SCALE_MIN = 0.01f
        const val SCALE_MAX = 16.0f
        const val YAW_STEP = 5.0f
        const val YAW_MIN = -360.0f
        const val YAW_MAX = 360.0f
        const val GROUND_STEP = 0.05f
        const val GROUND_MIN = -16.0f
        const val GROUND_MAX = 16.0f
    }
}
