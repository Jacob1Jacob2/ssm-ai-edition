package dev.betrix.superSmashMobsBrawl.utils

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import kotlin.math.floor

/** Restore a whole batch before finding a surface, so later blocks cannot bury the player again. */
object TerrainRestoration {
    fun restore(states: Collection<BlockState>) {
        if (states.isEmpty()) return
        states.forEach { it.update(true, false) }
        states.groupBy { it.world }.forEach { (world, restored) ->
            val boxes = restored.flatMap { collision(it.block) }
            world.players.filter { it.gameMode != GameMode.SPECTATOR && !it.isDead }.forEach { player ->
                val body = player.boundingBox.clone().expand(-.001)
                if (boxes.none { it.overlaps(body) }) return@forEach
                // The footprint includes a player standing across two block columns.
                val top = boxes.filter { it.maxX > body.minX && it.minX < body.maxX &&
                    it.maxZ > body.minZ && it.minZ < body.maxZ && it.maxY > body.minY }
                    .maxOf { it.maxY }
                lift(player, world, top)
            }
        }
    }

    private fun collision(block: Block) = block.collisionShape.boundingBoxes.map {
        it.clone().shift(block.x.toDouble(), block.y.toDouble(), block.z.toDouble())
    }

    private fun clear(world: World, body: BoundingBox): Boolean {
        if (body.maxY >= world.maxHeight) return false
        for (x in floor(body.minX).toInt()..floor(body.maxX).toInt())
            for (z in floor(body.minZ).toInt()..floor(body.maxZ).toInt())
                for (y in floor(body.minY).toInt()..floor(body.maxY).toInt()) {
                    val block = world.getBlockAt(x, y, z)
                    if (block.type in hazards || collision(block).any { it.overlaps(body) }) return false
                }
        return true
    }

    private fun lift(player: Player, world: World, top: Double) {
        val at = player.location
        val body = player.boundingBox.clone().expand(-.001)
        // Start above every restored block in this footprint, then clear any existing low ceiling.
        var y = top + .01
        while (y + body.height < world.maxHeight) {
            val candidate = body.clone().shift(0.0, y - at.y, 0.0)
            if (clear(world, candidate)) {
                player.teleport(at.clone().apply { this.y = y })
                player.velocity = Vector()
                player.fallDistance = 0f
                return
            }
            y = floor(y) + 1.01
        }
    }

    private val hazards = setOf(Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
        Material.POWDER_SNOW, Material.CACTUS, Material.SWEET_BERRY_BUSH)
}
