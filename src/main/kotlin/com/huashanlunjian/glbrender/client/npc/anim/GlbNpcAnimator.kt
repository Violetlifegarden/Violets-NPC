package com.huashanlunjian.glbrender.client.npc.anim

import net.minecraft.world.entity.LivingEntity
import top.fifthlight.blazerod.model.animation.AnimationState
import top.fifthlight.blazerod.model.animation.SimpleAnimationState
import top.fifthlight.blazerod.render.api.animation.AnimationItemInstance
import top.fifthlight.blazerod.render.api.resource.ModelInstance
import top.fifthlight.blazerod.render.runtime.animation.AnimationItemImpl

/**
 * Per-entity animation runtime: the single active clip plus its playback state. One NPC plays one clip at a time
 * (hard-cut in Step4-a2; a crossfade blends two of these in Step4-a3), so a runtime only needs to remember what it
 * last selected and its live [AnimationState]. Lifetime is tied to the NPC's [ModelInstance] cache in
 * `GlbNpcModels`, which drops both when the entity is removed.
 */
class NpcAnimRuntime {
    var currentClip: AnimationItemImpl? = null
    var currentLoop: GlbLoopMode? = null
    var itemInstance: AnimationItemInstance? = null
    var animState: AnimationState? = null

    fun reset() {
        currentClip = null
        currentLoop = null
        itemInstance = null
        animState = null
    }
}

/**
 * Drives NPC bone animation by evaluating a [GlbAnimationTable] each frame and playing the winning clip through the
 * ported glb-core [AnimationItemInstance] layer — the same layer the player's `ClipStateMachine` uses, but keyed
 * per-entity so several NPCs on screen can each be at a different point in (or a different) clip.
 *
 * This object is stateless; all per-entity state lives in the caller-owned [NpcAnimRuntime]. Clip selection is
 * first-match-wins by priority (the table's job); clip *resolution* maps the selected row's name fragments onto the
 * model's actual glTF clips with a first-contains match, falling back to the model's first clip so a single-clip rig
 * (CesiumMan) still animates every state instead of going static.
 */
object GlbNpcAnimator {

    /**
     * Applies this frame's clip to [instance] for [entity]. Returns true when a clip wrote bone transforms (the
     * caller must then let [ModelInstance.updateRenderData] pick them up before drawing), false when the model has
     * no clips at all and the pose is left as-is.
     */
    fun animate(
        runtime: NpcAnimRuntime,
        entity: LivingEntity,
        instance: ModelInstance,
        clips: List<AnimationItemImpl>,
        table: GlbAnimationTable,
        gameTick: Long,
        partialTick: Float,
    ): Boolean {
        if (clips.isEmpty()) return false

        val ctx = frameCtx(entity, gameTick, partialTick)
        val selected = table.select(ctx) ?: return false
        val clip = resolveClip(clips, selected.clipKeys) ?: return false

        // Restart only when the resolved clip or its loop mode actually changes; re-selecting the same walking clip
        // frame after frame must keep advancing, not re-seek to zero (name+loop dedup, mirroring TLM's setAnimation).
        if (clip !== runtime.currentClip || selected.loop != runtime.currentLoop || runtime.animState == null) {
            val createContext = NpcAnimationContext(entity, gameTick, partialTick)
            val itemInstance = AnimationItemInstance.Factory.of(clip)
            val animState = itemInstance.createState(createContext)
            (animState as? SimpleAnimationState)?.let { applyLoopMode(it, selected.loop) }
            runtime.currentClip = clip
            runtime.currentLoop = selected.loop
            runtime.itemInstance = itemInstance
            runtime.animState = animState
        }

        val itemInstance = runtime.itemInstance ?: return false
        val animState = runtime.animState ?: return false
        val playContext = NpcAnimationContext(entity, gameTick, partialTick)
        animState.updateTime(playContext)
        val pendingValues = itemInstance.update(playContext, animState)
        itemInstance.apply(instance, pendingValues)
        return true
    }

    private fun frameCtx(entity: LivingEntity, gameTick: Long, partialTick: Float): GlbFrameCtx {
        val walk = entity.walkAnimation
        return GlbFrameCtx(
            gameTick = gameTick,
            partialTick = partialTick,
            limbSwing = walk.position(partialTick),
            limbSwingAmount = walk.speed(partialTick),
            onGround = entity.onGround(),
            isInWater = entity.isInWater,
            isSprinting = entity.isSprinting,
            isDeadOrDying = entity.isDeadOrDying,
            hurtTime = entity.hurtTime,
        )
    }

    private fun resolveClip(clips: List<AnimationItemImpl>, keys: List<String>): AnimationItemImpl? {
        for (key in keys) {
            val match = clips.firstOrNull { (it.name ?: "").lowercase().contains(key) }
            if (match != null) return match
        }
        // Graceful degradation: a rig without a clip for this state still plays its first clip rather than freezing.
        return clips.firstOrNull()
    }

    private fun applyLoopMode(state: SimpleAnimationState, loop: GlbLoopMode) {
        when (loop) {
            GlbLoopMode.LOOP -> {
                state.loop = true
                state.paused = false
            }
            // PLAY_ONCE / HOLD_ON_LAST_FRAME both stop at the clip's end; SimpleAnimationState coerces currentTime to
            // [0, duration] when loop is false, so it naturally freezes on the final frame once it runs out.
            GlbLoopMode.PLAY_ONCE, GlbLoopMode.HOLD_ON_LAST_FRAME -> {
                state.loop = false
                state.paused = false
            }
        }
    }
}
