package dev.betrix.superSmashMobsBrawl.services

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.data.BlockFamilies
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.SlabType

/** Two-times terrain uses half-block voxels, rather than repeating partial block models. */
object MapBlockShapes {
    private val bases by lazy {
        buildMap<Block, Block> {
            BlockFamilies.getAllFamilies().forEach { family ->
                family.variants.values.filter { it is SlabBlock || it is StairBlock }.forEach { put(it, family.baseBlock) }
            }
            // These slab families intentionally have no normal recipe family in vanilla.
            put(Blocks.PETRIFIED_OAK_SLAB, Blocks.OAK_PLANKS)
            put(Blocks.SMOOTH_STONE_SLAB, Blocks.SMOOTH_STONE)
        }
    }
    private val variants by lazy {
        bases.entries.groupBy({ it.value }, { it.key }).mapValues { (_, blocks) ->
            blocks.flatMap { block -> block.stateDefinition.possibleStates }
                .filter { !it.hasProperty(BlockStateProperties.WATERLOGGED) || !it.getValue(BlockStateProperties.WATERLOGGED) }
                .map { it to mask(it) }.distinctBy { it.second }
        }
    }
    fun base(state: BlockState): BlockState {
        if (state.block !is SlabBlock && state.block !is StairBlock) return state
        val block = bases[state.block]
            ?: error("No full-block material for " + BuiltInRegistries.BLOCK.getKey(state.block))
        return block.defaultBlockState()
    }
    fun mask(state: BlockState): Int {
        if (state.isAir) return 0
        if (state.block !is SlabBlock && state.block !is StairBlock) return 255
        val boxes = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()
        var result = 0
        for (y in 0..1) for (z in 0..1) for (x in 0..1) {
            if (boxes.any { it.contains(.25 + .5*x, .25 + .5*y, .25 + .5*z) })
                result = result or (1 shl (y*4+z*2+x))
        }
        return result
    }
    /** Surface soil keeps its cap once; the enlarged lower layer is ordinary dirt. */
    fun isSurfaceSoil(state: BlockState): Boolean =
        state.block == Blocks.GRASS_BLOCK || state.block == Blocks.MYCELIUM || state.block == Blocks.PODZOL

    fun enlarged(state: BlockState): Array<BlockState> {
        ScaledMapDetails.enlarged(state)?.let { return it }
        if (ScaledMapPlants.isPlant(state)) return Array(8) { Blocks.AIR.defaultBlockState() }
        if (isSurfaceSoil(state)) return Array(8) {
            if (it < 4) Blocks.DIRT.defaultBlockState() else state
        }
        val mask = mask(state); val base = base(state)
        return Array(8) { if (mask and (1 shl it) != 0) base else Blocks.AIR.defaultBlockState() }
    }
    /** Source voxels become octants of the output block. Exact slab/stair matches are
     * preferred. Other patterns use the smallest supported shape containing the terrain,
     * so thin platforms do not disappear when their material has no quarter-block form. */
    fun reduced(states: Array<BlockState>): BlockState {
        ScaledTreePosts.reduced(states)?.let { return it }
        val weights = linkedMapOf<BlockState, Int>()
        var occupied = 0
        states.forEachIndexed { i, s ->
            if (!s.isAir) {
                occupied = occupied or (1 shl i)
                val base = base(s)
                weights[base] = (weights[base] ?: 0) + Integer.bitCount(mask(s))
            }
        }
        if (occupied == 0) return Blocks.AIR.defaultBlockState()
        val material = weights.maxBy { it.value }.key
        // Miniature posts aren't vanilla log blocks, so retain the canopy explicitly.
        if (material.block is LeavesBlock) return material.setValue(BlockStateProperties.PERSISTENT, true)
        if (occupied == 255) return material
        val choices = variants[material.block].orEmpty()
        return choices.filter { (_, m) -> m and occupied == occupied }
            .minByOrNull { (_, m) -> Integer.bitCount(m xor occupied) }?.first ?: material
    }
}
