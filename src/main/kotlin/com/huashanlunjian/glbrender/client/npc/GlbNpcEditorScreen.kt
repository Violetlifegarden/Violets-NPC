package com.huashanlunjian.glbrender.client.npc

import com.huashanlunjian.glbrender.client.npc.editor.GlbAdvancedTab
import com.huashanlunjian.glbrender.client.npc.editor.GlbAiTab
import com.huashanlunjian.glbrender.client.npc.editor.GlbDisplayTab
import com.huashanlunjian.glbrender.client.npc.editor.GlbEditorContext
import com.huashanlunjian.glbrender.client.npc.editor.GlbEditorTab
import com.huashanlunjian.glbrender.client.npc.editor.GlbGlobalTab
import com.huashanlunjian.glbrender.client.npc.editor.GlbInventoryTab
import com.huashanlunjian.glbrender.client.npc.editor.GlbLinesSubScreen
import com.huashanlunjian.glbrender.client.npc.editor.GlbMarksSubScreen
import com.huashanlunjian.glbrender.client.npc.editor.GlbMovementSubScreen
import com.huashanlunjian.glbrender.client.npc.editor.GlbSoundsSubScreen
import com.huashanlunjian.glbrender.client.npc.editor.GlbStatsSubScreen
import com.huashanlunjian.glbrender.client.npc.editor.GlbStatsTab
import com.huashanlunjian.glbrender.client.npc.editor.GlbTabButton
import com.huashanlunjian.glbrender.client.npc.preview.GlbPreviewScreen
import com.huashanlunjian.glbrender.npc.GlbModelDescriptor
import com.huashanlunjian.glbrender.npc.GlbModels
import com.huashanlunjian.glbrender.npc.GlbNpcAdvanced
import com.huashanlunjian.glbrender.npc.GlbNpcAi
import com.huashanlunjian.glbrender.npc.GlbNpcConfig
import com.huashanlunjian.glbrender.npc.GlbNpcMenu
import com.huashanlunjian.glbrender.npc.GlbNpcStats
import com.huashanlunjian.glbrender.npc.net.SetAdvancedPacket
import com.huashanlunjian.glbrender.npc.net.SetAiPacket
import com.huashanlunjian.glbrender.npc.net.SetConfigPacket
import com.huashanlunjian.glbrender.npc.net.SetModelPacket
import com.huashanlunjian.glbrender.npc.net.SetStatsPacket
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
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
class GlbNpcEditorScreen(
    menu: GlbNpcMenu,
    private val editorInventory: Inventory,
    title: Component,
    statsSeed: GlbNpcStats? = null,
    aiSeed: GlbNpcAi? = null,
    private val wanderSeed: Boolean? = null,
    advancedSeed: GlbNpcAdvanced? = null,
) :
    AbstractContainerScreen<GlbNpcMenu>(menu, editorInventory, title), GlbEditorContext {

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
    override var sit: Boolean = false
    override var lookPlayer: Boolean = true
    override var profession: Int = GlbNpcConfig.PROFESSION_NONE
    override var onAttack: Int = GlbNpcConfig.RESPONSE_NOTHING
    override var maxHealth: Float = 20.0f
    override var glowing: Boolean = false
    override var tint: Int = GlbNpcConfig.NO_TINT
    override var materialMode: Int = GlbNpcConfig.MATERIAL_PBR
    override var dialogs: String = menu.npc?.config?.dialogs ?: ""
    override var clip: String = menu.npc?.config?.clip ?: ""
    override var dropChances: String = menu.npc?.config?.dropChances ?: GlbNpcConfig.DEFAULT_DROP_CHANCES
    override var displayName: String = menu.npc?.customName?.string ?: ""

    /**
     * Local editable Stats copy. Seeded from the synced block, or handed back by a returning sub-screen so an edit
     * whose echo has not arrived yet is not visually rolled back.
     */
    override var stats: GlbNpcStats = statsSeed ?: menu.npc?.config?.stats?.copyStats() ?: GlbNpcStats()

    /**
     * Local editable AI copy. Seeded like [stats], and handed back by the Movement sub-screen so an edit whose echo has
     * not arrived yet is not visually rolled back.
     */
    override var ai: GlbNpcAi = aiSeed ?: menu.npc?.config?.ai?.copyAi() ?: GlbNpcAi()

    /** Local editable Advanced copy, seeded like [stats] and handed back by its sub-screens. */
    override var advanced: GlbNpcAdvanced =
        advancedSeed ?: menu.npc?.config?.advanced?.copyAdvanced() ?: GlbNpcAdvanced()

    private var currentTab = TAB_DISPLAY

    /**
     * Set while switching to the preview: `Minecraft.setScreen` calls [removed], and the base implementation is what
     * ends the server-side editing session. Suppressing it once keeps the menu and its session alive so the preview
     * can hand back to a rebuilt editor over the same menu.
     */
    private var keepMenuOnClose = false

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
        wander = wanderSeed ?: config?.wander ?: true
        followOwner = config?.followOwner ?: false
        sit = config?.sit ?: false
        lookPlayer = config?.lookPlayer ?: true
        profession = config?.profession ?: GlbNpcConfig.PROFESSION_NONE
        onAttack = config?.onAttack ?: GlbNpcConfig.RESPONSE_NOTHING
        maxHealth = config?.effectiveMaxHealth() ?: 20.0f
        glowing = config?.glowing ?: false
        tint = config?.tint ?: GlbNpcConfig.NO_TINT
        materialMode = config?.materialMode ?: GlbNpcConfig.MATERIAL_PBR
        dialogs = config?.dialogs ?: ""
        clip = config?.clip ?: ""
        dropChances = config?.dropChances ?: GlbNpcConfig.DEFAULT_DROP_CHANCES
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
        // The menu's slots belong to the Inventory tab: hide + disable them (and their hit boxes) on every other tab.
        menu.inventoryTabActive = currentTab == TAB_INVENTORY
    }

    // --- GlbEditorContext real-feature bindings ---

    override fun font(): Font = font

    override fun currentModelId(): String = menu.npc?.modelId ?: ""

    override fun clipNames(): List<String> {
        val id = currentModelId().ifEmpty { GlbModels.defaultId ?: return emptyList() }
        return GlbModelRegistry.clipsFor(id)?.mapNotNull { it.name } ?: emptyList()
    }

    override fun openPreview() {
        val id = currentModelId().ifEmpty { GlbModels.defaultId ?: return }
        keepMenuOnClose = true
        minecraft?.setScreen(
            GlbPreviewScreen(menu.npc, id, clip, menu.npc?.bbHeight ?: 1.8f) {
                // Rebuild the editor over the same menu (and the same still-open server session) on the way back;
                // if the NPC despawned in the meantime, closing to the game is the only safe destination.
                if (menu.npc?.isRemoved == false) {
                    GlbNpcEditorScreen(menu, editorInventory, title, stats, ai, wander, advanced)
                } else {
                    null
                }
            },
        )
    }

    override fun removed() {
        if (keepMenuOnClose) {
            keepMenuOnClose = false
            return
        }
        super.removed()
    }

    override fun applyModel(modelId: String) {
        val id = npcId ?: return
        PacketDistributor.sendToServer(SetModelPacket(id, modelId))
    }

    override fun applyConfig() {
        val id = npcId ?: return
        PacketDistributor.sendToServer(
            SetConfigPacket(
                id, scale, yawOffset, groundOffsetY, wander, followOwner, sit, lookPlayer, profession, onAttack, displayName,
                maxHealth, glowing, tint, dialogs, clip, materialMode, dropChances,
            ),
        )
    }

    override fun applyStats() {
        val id = npcId ?: return
        PacketDistributor.sendToServer(SetStatsPacket(id, stats.copyStats()))
    }

    override fun applyAi() {
        val id = npcId ?: return
        PacketDistributor.sendToServer(SetAiPacket(id, ai.copyAi()))
    }

    override fun applyAdvanced() {
        val id = npcId ?: return
        PacketDistributor.sendToServer(SetAdvancedPacket(id, advanced.copyAdvanced()))
    }

    override fun openAdvancedSub(section: Int) {
        keepMenuOnClose = true
        val returnTo: () -> Screen? = {
            if (menu.npc?.isRemoved == false) {
                GlbNpcEditorScreen(menu, editorInventory, title, stats, ai, wander, advanced)
            } else {
                null
            }
        }
        val screen = when (section) {
            GlbEditorContext.ADVANCED_SOUNDS -> GlbSoundsSubScreen(advanced, { applyAdvanced() }, returnTo)
            GlbEditorContext.ADVANCED_MARKS -> GlbMarksSubScreen(advanced, { applyAdvanced() }, returnTo)
            else -> GlbLinesSubScreen(advanced, { applyAdvanced() }, returnTo)
        }
        minecraft?.setScreen(screen)
    }

    override fun openStatsSub(section: Int) {
        keepMenuOnClose = true
        minecraft?.setScreen(
            GlbStatsSubScreen(section, stats, { applyStats() }) {
                if (menu.npc?.isRemoved == false) {
                    GlbNpcEditorScreen(menu, editorInventory, title, stats, ai, wander, advanced)
                } else {
                    null
                }
            },
        )
    }

    override fun openMovementSub() {
        keepMenuOnClose = true
        minecraft?.setScreen(
            GlbMovementSubScreen(
                ai,
                wander,
                { applyAi() },
                { value ->
                    wander = value
                    applyConfig()
                },
            ) {
                if (menu.npc?.isRemoved == false) {
                    GlbNpcEditorScreen(menu, editorInventory, title, stats, ai, wander)
                } else {
                    null
                }
            },
        )
    }

    override fun fmt(value: Float, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)

    override fun setDropChance(index: Int, value: Float) {
        dropChances = GlbNpcConfig.withDropChance(dropChances, index, value)
    }

    override fun renderBg(g: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        val right = leftPos + PANEL_W
        val bottom = topPos + PANEL_H
        // Flat dark panel + 1px border (CNPC blits a textured menubg; we draw equivalent geometry).
        g.fill(leftPos, topPos, right, bottom, PANEL_BG)
        g.fill(leftPos, topPos, right, topPos + 1, PANEL_BORDER)
        g.fill(leftPos, bottom - 1, right, bottom, PANEL_BORDER)
        g.fill(leftPos, topPos, leftPos + 1, bottom, PANEL_BORDER)
        g.fill(right - 1, topPos, right, bottom, PANEL_BORDER)
        // CNPC's inventory texture supplies the slot cells; our flat panel has to draw them itself.
        if (menu.inventoryTabActive) drawSlotCells(g)
    }

    /** One 18x18 cell per active slot (item icons are drawn on top by the container screen, at slot.x/slot.y). */
    private fun drawSlotCells(g: GuiGraphics) {
        for (slot in menu.slots) {
            if (!slot.isActive()) continue
            val x = leftPos + slot.x
            val y = topPos + slot.y
            g.fill(x - 1, y - 1, x + 17, y + 17, SLOT_BORDER)
            g.fill(x, y, x + 16, y + 16, SLOT_BG)
        }
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)
        // The Inventory tab carries CNPC's "NPC Inventory" header at the panel center, where our title would collide.
        if (currentTab != TAB_INVENTORY) {
            g.drawCenteredString(font, Component.literal("GLB NPC Editor"), leftPos + PANEL_W / 2, topPos + 6, 0xFFFFFF)
        }
        // Static labels are not widgets, so the active tab's labels are painted here (behind/on-top of its controls).
        tabs[currentTab].labels.forEach { g.drawString(font, it.text, it.x, it.y, it.color, false) }
    }

    private companion object {
        const val PANEL_W = 420
        const val PANEL_H = 200

        const val TAB_DISPLAY = 0
        const val TAB_INVENTORY = 3

        val PANEL_BG = 0xF01E1E1E.toInt()
        val PANEL_BORDER = 0xFF5A5A5A.toInt()
        val SLOT_BORDER = 0xFF8B8B8B.toInt()
        val SLOT_BG = 0xFF373737.toInt()
    }
}
