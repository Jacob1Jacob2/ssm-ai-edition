package dev.betrix.superSmashMobsBrawl.passives

import dev.betrix.superSmashMobsBrawl.smash.SmashService
import gg.flyte.twilight.scheduler.repeatingTask
import org.bukkit.entity.Player
import org.bukkit.util.Vector

/** Spider's shared energy pool: ten seconds climbing, ten seconds recharging. */
class WallClimbPassive(player: Player) : BrawlPassive("wall_climb", player) {
    var lastCooldownTime = 0L
    private var chargedDoubleJump = false

    override fun setup() {
        super.setup()
        runnables.add(repeatingTask(1) { stepEnergy() })
    }

    internal fun stepEnergy() {
        if(minigameService.getMinigameForPlayer(player)?.getState()!=dev.betrix.superSmashMobsBrawl.minigames.MinigameState.IN_PROGRESS)return
        if (SmashService.ownsXp(player)) return
        if (player.isOnGround) chargedDoubleJump = false
        val climbing = player.isSneaking && hasNearbyWall() &&
            System.currentTimeMillis() - lastCooldownTime >= 150L
        val next = player.exp + if (climbing) -0.005f else if (player.isOnGround) 0.005f else 0f
        player.exp = when { next < 0.00001f -> 0f; next > 0.99999f -> 1f; else -> next }
        if (!climbing || player.exp <= 0f) return
        player.velocity = Vector(player.velocity.x, metadata.double("wallClimbPower") ?: 0.4, player.velocity.z)
        player.fallDistance = 0f
        if (!chargedDoubleJump) { player.allowFlight = true; chargedDoubleJump = true }
    }

    private fun hasNearbyWall(): Boolean {
        val at = player.location
        // Check beside the body, not below it: standing on a floor isn't wall climbing.
        for (height in listOf(0.1, 0.9)) for ((dx, dz) in listOf(0.8 to 0.0, -0.8 to 0.0, 0.0 to 0.8, 0.0 to -0.8)) {
            val block = at.clone().add(dx, height, dz).block
            if (!block.isPassable && !block.isLiquid) return true
        }
        return false
    }

    fun onSpiderLeapUsed() { lastCooldownTime = System.currentTimeMillis() }
}
