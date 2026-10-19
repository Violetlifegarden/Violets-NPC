package com.huashanlunjian.glbrender.client.npc.editor

import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component

/**
 * One editor tab (Display / Stats / AI / Inventory / Advanced / Global), authored CustomNPC-style: a flat list of
 * absolute-coord [GlbLabel]s plus the interactive widgets, laid out with labels on the left and controls on the right
 * at ~22px row pitch, using the full 420px panel width like CNPC's tab bodies.
 *
 * The widgets are built relative to [guiLeft]/[guiTop], which [rebuild] supplies when the host screen is sized in
 * `init()`. The host then adds every widget to its own list and toggles [widgets] visibility when the tab is selected,
 * drawing only the active tab's [labels].
 *
 * Helper split by intent: [stubButton]/[stubField]/[stubCycle]/[stubYesNo]/[stubStepper] build NON-functional
 * placeholders (the bulk of the replicated screens — CNPC controls we deliberately do not implement); [stepper]/
 * [toggle]/[modelCycle] wire the few controls backed by our already-implemented features.
 */
abstract class GlbEditorTab(protected val ctx: GlbEditorContext) {
    abstract val title: String

    protected var guiLeft = 0
    protected var guiTop = 0

    private val _widgets = ArrayList<AbstractWidget>()
    private val _labels = ArrayList<GlbLabel>()

    val widgets: List<AbstractWidget> get() = _widgets
    val labels: List<GlbLabel> get() = _labels

    protected abstract fun build()

    fun rebuild(guiLeft: Int, guiTop: Int) {
        this.guiLeft = guiLeft
        this.guiTop = guiTop
        _widgets.clear()
        _labels.clear()
        build()
    }

    fun setVisible(visible: Boolean) {
        _widgets.forEach { it.visible = visible }
    }

    protected fun label(x: Int, y: Int, text: String, color: Int = COLOR_LABEL) {
        _labels.add(GlbLabel(guiLeft + x, guiTop + y, text, color))
    }

    // --- functional controls (existing features) ---

    /** Stepper whose new value is pushed straight to a ctx field via [assign], then committed. */
    protected fun stepper(x: Int, y: Int, w: Int, initial: Float, step: Float, min: Float, max: Float, decimals: Int, assign: (Float) -> Unit) {
        add(
            GlbStepper(guiLeft + x, guiTop + y, w, initial, step, min, max, { ctx.fmt(it, decimals) }) { value ->
                assign(value)
                ctx.applyConfig()
            },
        )
    }

    protected fun toggle(x: Int, y: Int, w: Int, initial: Boolean, assign: (Boolean) -> Unit) {
        add(
            GlbToggleYesNo(guiLeft + x, guiTop + y, w, initial) { value ->
                assign(value)
                ctx.applyConfig()
            },
        )
    }

    /** Editable text field that commits its value to [assign] (then the whole config) on Enter. */
    protected fun textField(x: Int, y: Int, w: Int, initial: String, assign: (String) -> Unit) {
        add(
            GlbCommitField(ctx.font(), guiLeft + x, guiTop + y, w, initial) { value ->
                assign(value)
                ctx.applyConfig()
            },
        )
    }

    protected fun modelCycle(x: Int, y: Int, w: Int) {
        val options = ctx.modelIds.toTypedArray()
        val initial = options.indexOf(ctx.currentModelId()).coerceAtLeast(0)
        add(
            GlbCycleButton(guiLeft + x, guiTop + y, w, options, initial) { index ->
                options.getOrNull(index)?.let(ctx::applyModel)
            },
        )
    }

    /** A real cycle button over a fixed [options] list; the new index is pushed to [assign], then the config committed. */
    protected fun cycleOption(x: Int, y: Int, w: Int, options: Array<String>, initial: Int, assign: (Int) -> Unit) {
        add(
            GlbCycleButton(guiLeft + x, guiTop + y, w, options, initial.coerceIn(0, options.lastIndex)) { index ->
                assign(index)
                ctx.applyConfig()
            },
        )
    }

    /**
     * Animation-preview cycle over the current model's clips (`Auto` = empty = normal state machine). Reads the list
     * live so it stays correct after a model switch; the new name is pushed to [GlbEditorContext.clip] and committed.
     */
    protected fun clipCycle(x: Int, y: Int, w: Int) {
        add(
            GlbClipCycleButton(guiLeft + x, guiTop + y, w, { ctx.clipNames() }, { ctx.clip }) { name ->
                ctx.clip = name
                ctx.applyConfig()
            },
        )
    }

    /** A real button that runs [onPress]; unlike [stubButton] it is backed by an actual action. */
    protected fun button(x: Int, y: Int, w: Int, text: String, onPress: () -> Unit) {
        add(Button.builder(Component.literal(text)) { onPress() }.bounds(guiLeft + x, guiTop + y, w, 20).build())
    }

    // --- functional Stats controls (the GlbNpcStats block; each change commits a SetStatsPacket) ---

    protected fun statsStepper(
        x: Int,
        y: Int,
        w: Int,
        initial: Int,
        step: Int,
        min: Int,
        max: Int,
        assign: (Int) -> Unit,
    ) {
        add(
            GlbStepper(guiLeft + x, guiTop + y, w, initial.toFloat(), step.toFloat(), min.toFloat(), max.toFloat(), { ctx.fmt(it, 0) }) { value ->
                assign(value.toInt())
                ctx.applyStats()
            },
        )
    }

    protected fun statsStepperF(
        x: Int,
        y: Int,
        w: Int,
        initial: Float,
        step: Float,
        min: Float,
        max: Float,
        decimals: Int,
        assign: (Float) -> Unit,
    ) {
        add(
            GlbStepper(guiLeft + x, guiTop + y, w, initial, step, min, max, { ctx.fmt(it, decimals) }) { value ->
                assign(value)
                ctx.applyStats()
            },
        )
    }

    protected fun statsToggle(x: Int, y: Int, w: Int, initial: Boolean, assign: (Boolean) -> Unit) {
        add(
            GlbToggleYesNo(guiLeft + x, guiTop + y, w, initial) { value ->
                assign(value)
                ctx.applyStats()
            },
        )
    }

    protected fun statsCycle(x: Int, y: Int, w: Int, options: Array<String>, initial: Int, assign: (Int) -> Unit) {
        add(
            GlbCycleButton(guiLeft + x, guiTop + y, w, options, initial.coerceIn(0, options.lastIndex)) { index ->
                assign(index)
                ctx.applyStats()
            },
        )
    }

    // --- placeholder controls (CNPC buttons we do not implement) ---

    protected fun stubButton(x: Int, y: Int, w: Int, text: String) {
        add(Button.builder(Component.literal(text)) {}.bounds(guiLeft + x, guiTop + y, w, 20).build())
    }

    protected fun stubField(x: Int, y: Int, w: Int, text: String = "") {
        val box = EditBox(ctx.font(), guiLeft + x, guiTop + y, w, 18, Component.literal(text))
        box.setEditable(false)
        box.value = text
        add(box)
    }

    /** A cycle button that does nothing — a placeholder for CNPC's option cycler. */
    protected fun stubCycle(x: Int, y: Int, w: Int, vararg options: String) {
        stubButton(x, y, w, options.firstOrNull() ?: "")
    }

    protected fun stubYesNo(x: Int, y: Int, w: Int, initial: Boolean = true) {
        stubButton(x, y, w, if (initial) "Yes" else "No")
    }

    /** An arrow stepper that does nothing — a placeholder for CNPC's [L/R] control. */
    protected fun stubStepper(x: Int, y: Int, w: Int, text: String) {
        stubButton(x, y, w, "< $text >")
    }

    private fun <T : AbstractWidget> add(widget: T): T {
        _widgets.add(widget)
        return widget
    }

    companion object {
        const val COLOR_LABEL = 0xE0E0E0

        const val SCALE_STEP = 0.05f
        const val SCALE_MIN = 0.01f
        const val SCALE_MAX = 16.0f
        const val YAW_STEP = 5.0f
        const val YAW_MIN = -360.0f
        const val YAW_MAX = 360.0f
        const val GROUND_STEP = 0.05f
        const val GROUND_MIN = -16.0f
        const val GROUND_MAX = 16.0f
        const val HEALTH_STEP = 1.0f
        const val HEALTH_MIN = 1.0f
        const val HEALTH_MAX = 512.0f
    }
}
