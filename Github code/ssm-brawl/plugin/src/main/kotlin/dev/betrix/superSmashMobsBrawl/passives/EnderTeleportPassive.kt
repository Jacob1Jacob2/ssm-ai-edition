package dev.betrix.superSmashMobsBrawl.passives

import gg.flyte.twilight.scheduler.repeatingTask
import org.bukkit.*
import org.bukkit.entity.Player

class EnderTeleportPassive(player: Player) : BrawlPassive("ender_teleport",player) {
    override fun setup() {
        super.setup()
        var ticks = 0
        var released = true
        runnables.add(repeatingTask(1) {
            if (dev.betrix.superSmashMobsBrawl.smash.SmashService.ownsXp(player)) return@repeatingTask
            if (!player.isSneaking) { ticks = 0; released = true }
            else if (released) ticks++
            player.exp = (ticks / 140f).coerceIn(0f,1f)
            if (ticks < 140 || !released) return@repeatingTask
            released = false; ticks = 0; player.exp = 0f
            val ray = player.rayTraceBlocks(48.0,FluidCollisionMode.NEVER) ?: return@repeatingTask
            val block = ray.hitBlock ?: return@repeatingTask
            val destination = block.location.add(0.5,1.0,0.5)
            if (!destination.block.isPassable || !destination.clone().add(0.0,1.0,0.0).block.isPassable) return@repeatingTask
            destination.yaw = player.location.yaw; destination.pitch = player.location.pitch
            player.world.spawnParticle(Particle.PORTAL,player.location,50,0.4,0.8,0.4,0.15)
            player.teleport(destination); player.fallDistance = 0f
            player.world.playSound(destination,Sound.ENTITY_ENDERMAN_TELEPORT,1f,1f)
        })
    }
}
