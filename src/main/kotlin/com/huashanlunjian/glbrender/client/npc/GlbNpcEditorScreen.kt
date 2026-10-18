package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.client.npc.editor.GlbAdvancedTab
import com.huashanlunjian.glbrender.client.npc.editor.GlbAiTab
import com.huashanlunjian.glbrender.client.npc.editor.GlbDisplayTab
import com.huashanlunjian.glbrender.client.npc.editor.GlbEditorContext
import com.huashanlunjian.glbrender.client.npc.editor.GlbEditorTab
import com.huashanlunjian.glbrender.client.npc.editor.GlbGlobalTab
import com.huashanlunjian.glbrender.client.npc.editor.GlbInventoryTab
import com.huashanlunjian.glbrender.client.npc.editor.GlbStatsTab
import com.huashanlunjian.glbrender.client.npc.editor.GlbTabButton
import com.huashanlunjian.glbrender.npc.GlbModelDescriptor
import com.huashanlunjian.glbrender.npc.GlbModels
import com.huashanlunjian.glbrender.npc.GlbNpcMenu
import com.huashanlunjian.glbrender.npc.net.SetConfigPacket
import com.huashanlunjian.glbrender.npc.net.SetModelPacket
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.neoforged.neoforge.network.PacketDistributor
import java.util.Locale

/**
 * The NPC editor as a full CustomNPC tab shell: a 420x200 panel with the six CNPC tabs (Display/Stats/AI/Inventory/
 * Advanced/Global) chained across the top strip, plus a Delete button and an "X" close anchored at the right end of
 * that strip. Only Display and AI host functional controls — the model picker, the scale/yaw/ground steppers and the
 * wander/follow toggles, all still travelling over the Step5-3 C2S packets whose synced values echo back to the world
 * renderer. Every other control is an empty placeholder replicated for layout (UI-4); no CNPC behavior is copied and
 * the in-panel entity preview (UI-3) stays deferred.
 *
 * Each tab is a [GlbEditorTab] that builds its labels/widgets relative to `guiLeft`/`guiTop` supplied by [init]; the
 * screen is the [GlbEditorContext] those tabs read their real-feature state from. It still extends
 * [AbstractContainerScreen] over [GlbNpcMenu] so the server-side `stillValid` gate and the editing-session close
 * keep working — the container draws no slots here.
 */
class GlbNpcEditorScreen(menu: GlbNpcMenu, inv: Inventory, title: Component) :
    AbstractContainerScreen<GlbNpcMenu>(menu, inv, title), GlbEditorContext {

    override val npcId: Int? = menu.npc?.id
    override val modelIds: List<String> = GlbModels.ids()

    private val startDescriptor: GlbModelDescriptor? = run {
        val id = menu.npc?.modelId?.takeIf { it.isNotEmpty() } ?: GlbModels.defaultId
        id?.let { GlbModels.descriptor(it) }
    }

    override var scale: Float = 1.0f
    override var yawOffset: Float = 0.0f
    override var groundOffsetY: Float = 0.0f
    override var wander: Boolean = true
    override var followOwner: Boolean = false
    override var maxHealth: Float = 20.0f
    override var glowing: Boolean = false
    override var displayName: String = menu.npc?.customName?.string ?: ""

    private var currentTab = TAB_DISPLAY

    private val tabs: List<GlbEditorTab> = listOf(
        GlbDisplayTab(this), GlbStatsTab(this), GlbAiTab(this),
        GlbInventoryTab(this), GlbAdvancedTab(this), GlbGlobalTab(this),
    )
    private val stripButtons = ArrayList<GlbTabButton>()

    init {
        imageWidth = PANEL_W
        imageHeight = PANEL_H
        seedFromConfig()
    }

    private fun seedFromConfig() {
        val config = menu.npc?.config
        val d = startDescriptor
        scale = config?.scale?.takeUnless { it.isNaN() } ?: d?.scale ?: 1.0f
        yawOffset = config?.yawOffset?.takeUnless { it.isNaN() } ?: d?.yawOffset ?: 0.0f
        groundOffsetY = config?.groundOffsetY?.takeUnless { it.isNaN() } ?: d?.groundOffsetY ?: 0.0f
        wander = config?.wander ?: true
        followOwner = config?.followOwner ?: false
        maxHealth = config?.effectiveMaxHealth() ?: 20.0f
        glowing = config?.glowing ?: false
    }

    override fun init() {
        super.init()
        // Rebuild every tab at the now-known panel origin, then register all of their widgets with the screen. The
        // inactive tabs keep their widgets but are hidden by applyTabVisibility().
        tabs.forEach { tab ->
            tab.rebuild(leftPos, topPos)
            tab.widgets.forEach { addRenderableWidget(it) }
        }
        buildTabStrip()
        applyTabVisibility()
    }

    private fun buildTabStrip() {
        stripButtons.clear()
        val tabY = topPos - GlbTabButton.HEIGHT - 3
        var x = leftPos + 4
        tabs.forEachIndexed { index, tab ->
            val width = font.width(tab.title) + 12
            val button = GlbTabButton(x, tabY, width, Component.literal(tab.title)) { selectTab(index) }
            button.selected = index == currentTab
            stripButtons.add(addRenderableWidget(button))
            x += width
        }
        // Close "X" at the far right (guiLeft+width-22), Delete button just left of it (CNPC GuiNpcMenu chrome).
        val closeWidth = 20
        val closeX = leftPos + PANEL_W - closeWidth - 2
        addRenderableWidget(GlbTabButton(closeX, tabY, closeWidth, Component.literal("X")) { onClose() })
        val deleteWidth = font.width("Delete") + 12
        // Placeholder — CNPC's Delete opens a ConfirmScreen -> delete packet; we replicate the button only.
        addRenderableWidget(
            Button.builder(Component.literal("Delete")) {}.bounds(closeX - deleteWidth, tabY, deleteWidth, 20).build(),
        )
    }

    private fun selectTab(tab: Int) {
        currentTab = tab
        stripButtons.forEachIndexed { index, button -> button.selected = index == tab }
        applyTabVisibility()
    }

    private fun applyTabVisibility() {
        tabs.forEachIndexed { index, tab -> tab.setVisible(index == currentTab) }
    }

    // --- GlbEditorContext real-feature bindings ---

    override fun font(): Font = font

    override fun currentModelId(): String = menu.npc?.modelId ?: ""

    override fun applyModel(modelId: String) {
        val id = npcId ?: return
        PacketDistributor.sendToServer(SetModelPacket(id, modelId))
    }

    override fun applyConfig() {
        val id = npcId ?: return
        PacketDistributor.sendToServer(
            SetConfigPacket(id, scale, yawOffset, groundOffsetY, wander, followOwner, displayName, maxHealth, glowing),
        )
    }

    override fun fmt(value: Float, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)

    override fun renderBg(g: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        val right = leftPos + PANEL_W
        val bottom = topPos + PANEL_H
        // Flat dark panel + 1px border (CNPC blits a textured menubg; we draw equivalent geometry).
        g.fill(leftPos, topPos, right, bottom, PANEL_BG)
        g.fill(leftPos, topPos, right, topPos + 1, PANEL_BORDER)
        g.fill(leftPos, bottom - 1, right, bottom, PANEL_BORDER)
        g.fill(leftPos, topPos, leftPos + 1, bottom, PANEL_BORDER)
        g.fill(right - 1, topPos, right, bottom, PANEL_BORDER)
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)
        g.drawCenteredString(font, Component.literal("GLB NPC Editor"), leftPos + PANEL_W / 2, topPos + 6, 0xFFFFFF)
        // Static labels are not widgets, so the active tab's labels are painted here (behind/on-top of its controls).
        tabs[currentTab].labels.forEach { g.drawString(font, it.text, it.x, it.y, it.color, false) }
    }

    private companion object {
        const val PANEL_W = 420
        const val PANEL_H = 200

        const val TAB_DISPLAY = 0

        val PANEL_BG = 0xF01E1E1E.toInt()
        val PANEL_BORDER = 0xFF5A5A5A.toInt()
    }
}
