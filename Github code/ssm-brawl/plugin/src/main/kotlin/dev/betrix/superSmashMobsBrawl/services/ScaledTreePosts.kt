package dev.betrix.superSmashMobsBrawl.services

import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.bukkit.Material

/** Dedicated wall-post states provide the vanilla half-block footprint for miniature trunks.
 * These carrier walls are absent from the imported arena/lobby palettes. Ordinary connected
 * wall models and all cobblestone/mossy/stone-brick walls retain their original appearance. */
object ScaledTreePosts {
    val carriers = linkedMapOf(
        "oak" to "tuff_wall", "spruce" to "polished_tuff_wall", "birch" to "tuff_brick_wall",
        "jungle" to "resin_brick_wall", "acacia" to "mud_brick_wall", "dark_oak" to "sulfur_wall",
        "mangrove" to "polished_sulfur_wall", "cherry" to "sulfur_brick_wall",
        "pale_oak" to "cinnabar_wall", "crimson" to "polished_cinnabar_wall",
        "warped" to "cinnabar_brick_wall")
    private val blocks by lazy { BuiltInRegistries.BLOCK.associateBy { BuiltInRegistries.BLOCK.getKey(it).path } }
    val materials by lazy { carriers.values.map { Material.valueOf(it.uppercase()) }.toSet() }

    private fun species(state: BlockState): String? {
        if (!state.hasProperty(BlockStateProperties.AXIS) || state.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y) return null
        val id = BuiltInRegistries.BLOCK.getKey(state.block).path
        // Preserve stripped timber and horizontal building beams as their own material.
        return carriers.keys.firstOrNull { id == "${it}_log" || id == "${it}_wood" || id == "${it}_stem" || id == "${it}_hyphae" }
    }
    fun reduced(states: Array<BlockState>): BlockState? {
        val logs = states.mapIndexedNotNull { i, s -> species(s)?.let { Triple(i, it, s) } }
        if (logs.isEmpty() || logs.map { it.second }.distinct().size != 1) return null
        // Only slender, isolated columns become posts. Don't replace terrain or a thick trunk.
        if (logs.map { it.first % 4 }.distinct().size != 1) return null
        if (states.any { !it.isAir && species(it) == null && it.block !is LeavesBlock &&
                    !ScaledMapPlants.isPlant(it) }) return null
        return blocks.getValue(carriers.getValue(logs.first().second)).defaultBlockState()
            .setValue(WallBlock.UP, true).setValue(WallBlock.WATERLOGGED, false)
    }

    fun addAssets(files: MutableMap<String, ByteArray>) {
        for ((wood, wall) in carriers) {
            val log = if (wood in setOf("crimson","warped")) "${wood}_stem" else "${wood}_log"
            val custom = "ssm:block/small_${wood}_trunk"
            val faces = listOf("north","south","east","west","up","down").joinToString(",") { face ->
                val texture = if (face == "up" || face == "down") "#end" else "#side"
                "\"$face\":{\"uv\":[0,0,16,16],\"texture\":\"$texture\"}"
            }
            files["assets/ssm/models/block/small_${wood}_trunk.json"] =
                """{"textures":{"side":"minecraft:block/$log","end":"minecraft:block/${log}_top","particle":"minecraft:block/$log"},"elements":[{"from":[4,0,4],"to":[12,16,12],"faces":{$faces}}]}""".toByteArray()
            val parts = mutableListOf(
                """{"when":{"up":"true","north":"none","south":"none","east":"none","west":"none","waterlogged":"false"},"apply":{"model":"$custom"}}""",
                """{"when":{"AND":[{"up":"true"},{"OR":[{"north":"low|tall"},{"south":"low|tall"},{"east":"low|tall"},{"west":"low|tall"},{"waterlogged":"true"}]}]},"apply":{"model":"minecraft:block/${wall}_post"}}""")
            for ((dir, rotation) in listOf("north" to 0,"east" to 90,"south" to 180,"west" to 270))
                for (height in listOf("low","tall")) {
                    val model = if(height=="low") "${wall}_side" else "${wall}_side_tall"
                    parts += """{"when":{"$dir":"$height"},"apply":{"model":"minecraft:block/$model","y":$rotation,"uvlock":true}}"""
                }
            files["assets/minecraft/blockstates/$wall.json"] = """{"multipart":[${parts.joinToString(",")}]}""".toByteArray()
        }
    }
}
