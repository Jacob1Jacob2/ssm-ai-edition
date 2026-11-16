package dev.betrix.superSmashMobsBrawl.abilities

import gg.flyte.twilight.scheduler.TwilightRunnable
import kotlin.math.abs
import org.bukkit.Effect
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.util.Vector

class IcePathAbility(player: Player) : BrawlAbility("ice_path", player) {
    private var pathTask: TwilightRunnable? = null
    private val trackedBlockStates = mutableMapOf<Block, BlockData>()

    override fun setup() {
        super.setup()
        listeners.add(gg.flyte.twilight.event.event<org.bukkit.event.block.BlockFadeEvent> {
            if (block in trackedBlockStates && block.type == Material.ICE) isCancelled = true
        })
    }

    override fun activate() {
        super.activate()
        val pathLength = ((metadata.int("path_length") ?: 16) * itemBoost).toInt()
        val meltTimeMs = metadata.long("melt_time_ms") ?: 6000L
        val launchVelocityY = upgradedValue("launch_velocity_y", 0.5)
        player.teleport(player.location.add(0.0, 1.0, 0.0))
        player.velocity = Vector(0.0, launchVelocityY, 0.0)
        val blocks = mutableListOf<Block>()
        val playerLocation = player.location
        val direction = playerLocation.direction
        val isXDominant = abs(direction.x) > abs(direction.z)
        if (isXDominant) {
            collectBlocks(
                    playerLocation.clone().add(0.0, 0.0, 1.0),
                    pathLength,
                    blocks,
            )
            collectBlocks(
                    playerLocation.clone().add(0.0, 0.0, -1.0),
                    pathLength,
                    blocks,
            )
        } else {
            collectBlocks(
                    playerLocation.clone().add(1.0, 0.0, 0.0),
                    pathLength,
                    blocks,
            )
            collectBlocks(
                    playerLocation.clone().add(-1.0, 0.0, 0.0),
                    pathLength,
                    blocks,
            )
        }
        collectBlocks(playerLocation.clone(), pathLength, blocks)
        blocks.sortBy { player.location.distance(it.location.add(0.5, 0.5, 0.5)) }
        pathTask?.cancel()
        pathTask =
                repeatingTask(0, 0) {
                    if (blocks.isEmpty()) {
                        cancel()
                        pathTask = null
                        return@repeatingTask
                    }
                    val block = blocks.removeAt(0)
                    block.world.playEffect(block.location, Effect.STEP_SOUND, Material.ICE)
                    scheduleBlockChange(block, Material.ICE.createBlockData(), meltTimeMs)
                }
        pathTask?.let { runnables.add(it) }
    }

    override fun teardown() {
        pathTask?.cancel()
        pathTask = null
        trackedBlockStates.forEach { (block, original) ->
            if (block.type == Material.ICE) block.setBlockData(original, false)
        }
        trackedBlockStates.clear()
        super.teardown()
    }

    private fun collectBlocks(
            startLocation: Location,
            length: Int,
            blocks: MutableList<Block>,
    ) {
        val location = startLocation.clone().subtract(0.0, 1.0, 0.0)
        val direction = location.direction.clone()
        val horizontalLength =
                kotlin.math.sqrt(direction.x * direction.x + direction.z * direction.z)
        if (abs(direction.y) > horizontalLength) {
            direction.y = if (direction.y > 0) horizontalLength else -horizontalLength
            direction.normalize()
        }
        location.subtract(direction.clone().multiply(2.0))
        var distance = 0.0
        while (distance < length) {
            distance += 0.2
            location.add(direction.clone().multiply(0.2))
            val block = location.block
            if (block.type == Material.ICE) {
                continue
            }
            if (block.type == Material.AIR || block.type == Material.SNOW) {
                if (!blocks.contains(block)) {
                    blocks.add(block)
                }
            }
        }
    }

    private fun scheduleBlockChange(
            block: Block,
            newData: BlockData,
            durationMs: Long,
    ) {
        if (!trackedBlockStates.containsKey(block)) {
            trackedBlockStates[block] = block.blockData.clone()
        }
        val originalData = trackedBlockStates[block]
        val appliedData = newData.clone()
        block.setBlockData(appliedData, false)
        val delayTicks = (durationMs / 50L).coerceAtLeast(1L)
        runnables.add(delay(delayTicks) {
            val stored = trackedBlockStates.remove(block)
            if (stored != null && block.world.isChunkLoaded(block.chunk.x, block.chunk.z)) {
                if (block.blockData.matches(appliedData)) {
                    block.setBlockData(stored, false)
                }
            }
        })
    }
}
