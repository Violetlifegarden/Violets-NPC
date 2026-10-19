package com.huashanlunjian.glbrender.npc

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.pathfinder.PathFinder
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.level.pathfinder.PathfindingContext
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator

/**
 * Ground navigation for [GlbNpc]. Identical to vanilla except for [GlbNpcNodeEvaluator], which makes closed fence
 * gates pathable while door pathing is enabled. Vanilla's [WalkNodeEvaluator] only honours `canOpenDoors` for wooden
 * doors; a closed gate is typed [PathType.FENCE] and stays impassable even for a mob that can open doors, so the
 * pathfinder would never route through one and [GlbDoorGoal] would never get the chance to open it.
 */
class GlbNpcNavigation(mob: Mob, level: Level) : GroundPathNavigation(mob, level) {
    override fun createPathFinder(maxVisitedNodes: Int): PathFinder {
        val evaluator = GlbNpcNodeEvaluator()
        // Vanilla's GroundPathNavigation sets this too; the override replaces its createPathFinder wholesale.
        evaluator.setCanPassDoors(true)
        nodeEvaluator = evaluator
        return PathFinder(evaluator, maxVisitedNodes)
    }
}

private class GlbNpcNodeEvaluator : WalkNodeEvaluator() {
    override fun getPathType(context: PathfindingContext, x: Int, y: Int, z: Int): PathType {
        val type = super.getPathType(context, x, y, z)
        if (!canOpenDoors) return type
        if (type != PathType.FENCE && type != PathType.DOOR_WOOD_CLOSED) return type
        val state = context.getBlockState(BlockPos(x, y, z))
        val block = state.block
        // Fence posts and walls stay blocked: only actual closed gates become passable.
        if (block is FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) return PathType.WALKABLE_DOOR
        // Vanilla already converts wooden doors in the mob pathing flags, but keep it explicit here so this evaluator
        // does not depend on that internal branch.
        if (block is DoorBlock && type == PathType.DOOR_WOOD_CLOSED &&
            DoorBlock.isWoodenDoor(state) && !state.getValue(DoorBlock.OPEN)
        ) {
            return PathType.WALKABLE_DOOR
        }
        return type
    }
}
