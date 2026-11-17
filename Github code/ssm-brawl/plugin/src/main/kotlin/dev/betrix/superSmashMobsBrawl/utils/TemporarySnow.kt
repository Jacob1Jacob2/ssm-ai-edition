package dev.betrix.superSmashMobsBrawl.utils

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData

/**
 * Snow that an ability laid down for a couple of seconds (Arctic Aura, Blizzard). Anything that
 * remembers a block in order to put it back later must not remember this snow: it would restore
 * it after its owner stopped tracking it, leaving snow on the map for the rest of the match.
 */
object TemporarySnow {
    private val blocks = HashSet<Block>()

    fun add(block: Block) { blocks += block }
    fun remove(block: Block) { blocks -= block }

    /** What this block should go back to: air when the snow on it is only temporary. */
    fun original(block: Block): BlockData =
        if (block in blocks && block.type == Material.SNOW) Material.AIR.createBlockData() else block.blockData.clone()
}
