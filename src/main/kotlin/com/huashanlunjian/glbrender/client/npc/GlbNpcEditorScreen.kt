package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.client.npc.editor.GlbStepper
import com.huashanlunjian.glbrender.client.npc.editor.GlbTabButton
import com.huashanlunjian.glbrender.client.npc.editor.GlbToggleYesNo
import com.huashanlunjian.glbrender.npc.GlbModelDescriptor
import com.huashanlunjian.glbrender.npc.GlbModels
import com.huashanlunjian.glbrender.npc.GlbNpcMenu
import com.huashanlunjian.glbrender.npc.net.SetConfigPacket
import com.huashanlunjian.glbrender.npc.net.SetModelPacket
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.neoforged.neoforge.network.PacketDistributor
import java.util.Locale

/**
 * The NPC editor, laid out as a CustomNPC-style tabbed shell: a wide panel with a horizontal row of flat text tabs on
 * top (active tab taller + yellow, idle shorter + gray, chained with no gap — see [GlbTabButton]), an "X" close at the
 * right end of that strip, and a reserved right-hand region for the in-panel entity preview (UI-3 fills it). Only the
 * chrome is CNPC-shaped; no CNPC feature/logic is copied — the tabs host OUR existing features and every edit still
 * goes through the Step5-3 C2S packets, whose synced values echo back so the world NPC updates live (the preview).
 *
 * It still extends [AbstractContainerScreen] over [GlbNpcMenu] so the server-side `stillValid` gate and the
 * editing-session close ([com.huashanlunjian.glbrender.npc.GlbNpcMenu.removed]) keep working — the container is only a
 * lifecycle host here, it draws no slots.
 */
class GlbNpcEditorScreen(menu: GlbNpcMenu, inv: Inventory, title: Component) :
    AbstractContainerScreen<GlbNpcMenu>(menu, inv, title) {

    private val npcId: Int? = menu.npc?.id
    private val modelIds: List<String> = GlbModels.ids()

    private val startDescriptor: GlbModelDescriptor? = run {
        val id = menu.npc?.modelId?.takeIf { it.isNotEmpty() } ?: GlbModels.defaultId
        id?.let { GlbModels.descriptor(it) }
    }

    private var scaleVal: Float = 1.0f
    private var yawVal: Float = 0.0f
    private var groundVal: Float = 0.0f
    private var wanderVal: Boolean = true
    private var followVal: Boolean = false

    private var currentTab = TAB_MODEL

    private val contentTabs = ArrayList<GlbTabButton>()
    private val modelButtons = ArrayList<Button>()
    private val transformWidgets = ArrayList<AbstractWidget>()
    private val behaviorWidgets = ArrayList<AbstractWidget>()

    init {
        imageWidth = PANEL_W
        imageHeight = PANEL_H
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
        buildTabStrip()
        buildModelTab()
        buildTransformTab()
        buildBehaviorTab()
        applyTabVisibility()
    }

    private fun buildTabStrip() {
        val tabY = topPos - GlbTabButton.HEIGHT - 3
        var x = leftPos + 4
        TAB_LABELS.forEachIndexed { index, label ->
            val width = font.width(label) + 12
            val button = GlbTabButton(x, tabY, width, Component.literal(label)) { selectTab(index) }
            button.selected = index == currentTab
            contentTabs.add(addRenderableWidget(button))
            x += width
        }
        // "X" close pinned to the right end of the strip (CNPC places close at guiLeft+width-22).
        addRenderableWidget(
            GlbTabButton(leftPos + PANEL_W - 22, tabY, 20, Component.literal("X")) { onClose() },
        )
    }

    private fun buildModelTab() {
        modelIds.forEachIndexed { index, id ->
            val button = addRenderableWidget(
                Button.builder(Component.literal(id)) {
                    if (npcId != null) PacketDistributor.sendToServer(SetModelPacket(npcId, id))
                }
                    .pos(leftPos + CONTENT_X, topPos + CONTENT_TOP + index * 22)
                    .size(CONTENT_W, 20)
                    .build(),
            )
            modelButtons.add(button)
        }
    }

    private fun buildTransformTab() {
        transformWidgets.add(
            addRenderableWidget(
                GlbStepper(
                    leftPos + STEPPER_X, topPos + CONTENT_TOP, STEPPER_W, scaleVal,
                    SCALE_STEP, SCALE_MIN, SCALE_MAX, { fmt(it, 2) },
                ) { value -> scaleVal = value; sendConfig() },
            ),
        )
        transformWidgets.add(
            addRenderableWidget(
                GlbStepper(
                    leftPos + STEPPER_X, topPos + CONTENT_TOP + 26, STEPPER_W, yawVal,
                    YAW_STEP, YAW_MIN, YAW_MAX, { fmt(it, 1) },
                ) { value -> yawVal = value; sendConfig() },
            ),
        )
        transformWidgets.add(
            addRenderableWidget(
                GlbStepper(
                    leftPos + STEPPER_X, topPos + CONTENT_TOP + 52, STEPPER_W, groundVal,
                    GROUND_STEP, GROUND_MIN, GROUND_MAX, { fmt(it, 2) },
                ) { value -> groundVal = value; sendConfig() },
            ),
        )
    }

    private fun buildBehaviorTab() {
        behaviorWidgets.add(
            addRenderableWidget(
                GlbToggleYesNo(leftPos + STEPPER_X, topPos + CONTENT_TOP, TOGGLE_W, wanderVal) {
                    value -> wanderVal = value; sendConfig()
                },
            ),
        )
        behaviorWidgets.add(
            addRenderableWidget(
                GlbToggleYesNo(leftPos + STEPPER_X, topPos + CONTENT_TOP + 26, TOGGLE_W, followVal) {
                    value -> followVal = value; sendConfig()
                },
            ),
        )
    }

    private fun selectTab(tab: Int) {
        currentTab = tab
        contentTabs.forEachIndexed { index, button -> button.selected = index == tab }
        applyTabVisibility()
    }

    private fun applyTabVisibility() {
        modelButtons.forEach { it.visible = currentTab == TAB_MODEL }
        transformWidgets.forEach { it.visible = currentTab == TAB_TRANSFORM }
        behaviorWidgets.forEach { it.visible = currentTab == TAB_BEHAVIOR }
    }

    private fun sendConfig() {
        val id = npcId ?: return
        PacketDistributor.sendToServer(SetConfigPacket(id, scaleVal, yawVal, groundVal, wanderVal, followVal))
    }

    override fun renderBg(g: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        val right = leftPos + PANEL_W
        val bottom = topPos + PANEL_H
        // Flat dark panel + 1px border (CNPC uses a textured menubg; we draw our own equivalent geometry).
        g.fill(leftPos, topPos, right, bottom, PANEL_BG)
        g.fill(leftPos, topPos, right, topPos + 1, PANEL_BORDER)
        g.fill(leftPos, bottom - 1, right, bottom, PANEL_BORDER)
        g.fill(leftPos, topPos, leftPos + 1, bottom, PANEL_BORDER)
        g.fill(right - 1, topPos, right, bottom, PANEL_BORDER)
        // Reserved preview region (UI-3 draws the NPC here); framed placeholder for now.
        val px = leftPos + PREVIEW_X
        g.fill(px, topPos + 14, px + PREVIEW_W, topPos + 14 + PREVIEW_H, PREVIEW_BG)
        g.fill(px, topPos + 14, px + PREVIEW_W, topPos + 15, PANEL_BORDER)
        g.fill(px, topPos + 13 + PREVIEW_H, px + PREVIEW_W, topPos + 14 + PREVIEW_H, PANEL_BORDER)
        g.fill(px, topPos + 14, px + 1, topPos + 14 + PREVIEW_H, PANEL_BORDER)
        g.fill(px + PREVIEW_W - 1, topPos + 14, px + PREVIEW_W, topPos + 14 + PREVIEW_H, PANEL_BORDER)
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)
        g.drawCenteredString(font, Component.literal("GLB NPC Editor"), leftPos + PANEL_W / 2, topPos + 6, 0xFFFFFF)
        when (currentTab) {
            TAB_MODEL -> {
                val modelId = menu.npc?.modelId
                val label = if (modelId.isNullOrEmpty()) "<default>" else modelId
                g.drawString(font, "Current: $label", leftPos + CONTENT_X, topPos + CONTENT_TOP - 11, 0xA0A0A0, false)
            }
            TAB_TRANSFORM -> {
                drawFieldLabel(g, 0, "Scale")
                drawFieldLabel(g, 1, "Yaw")
                drawFieldLabel(g, 2, "Ground Y")
            }
            TAB_BEHAVIOR -> {
                drawFieldLabel(g, 0, "Wander")
                drawFieldLabel(g, 1, "Follow owner")
            }
        }
    }

    // The stepper / toggle widgets draw their own value text; this only renders the leading label at the row's left.
    private fun drawFieldLabel(g: GuiGraphics, row: Int, label: String) {
        val y = topPos + CONTENT_TOP + row * 26 + 6
        g.drawString(font, label, leftPos + CONTENT_X, y, 0xFFFFFF, false)
    }

    private fun fmt(v: Float, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", v)

    private companion object {
        const val PANEL_W = 360
        const val PANEL_H = 210

        const val TAB_MODEL = 0
        const val TAB_TRANSFORM = 1
        const val TAB_BEHAVIOR = 2
        val TAB_LABELS = listOf("Model", "Transform", "Behavior")

        const val CONTENT_X = 10
        const val CONTENT_TOP = 26
        const val CONTENT_W = 196
        const val STEPPER_X = 74
        const val STEPPER_W = 120
        const val TOGGLE_W = 50

        const val PREVIEW_X = 216
        const val PREVIEW_W = 136
        const val PREVIEW_H = 180

        val PANEL_BG = 0xF01E1E1E.toInt()
        val PANEL_BORDER = 0xFF5A5A5A.toInt()
        val PREVIEW_BG = 0xFF141414.toInt()

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
