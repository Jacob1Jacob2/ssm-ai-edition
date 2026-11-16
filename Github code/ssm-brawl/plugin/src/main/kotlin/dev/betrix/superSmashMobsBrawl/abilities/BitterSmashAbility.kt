package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.smash.SmashService
import dev.betrix.superSmashMobsBrawl.smash.SmashItems
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.block.Block
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.*

class BitterSmashAbility(player: Player) : ClassicKitAbility("bitter_smash", player) {
    var airborne = false
        private set
    private var diving = false
    private var enhanced = false
    private var boost = 1.0
    override fun canActivate(sendMessage: Boolean) = !airborne && super.canActivate(sendMessage)
    override fun activate() { super.activate(); launch(false) }
    fun launchSmash(): Boolean {
        if (airborne) return false
        super.activate(); launch(true); return true
    }
    private fun launch(ice: Boolean) {
        airborne = true; diving = false; enhanced = ice; boost = itemBoost
        val start = player.location.clone()
        player.velocity = Vector(0.0, (if (ice) 1.35 else 1.0) * boost, 0.0)
        player.world.playSound(start, Sound.ENTITY_POLAR_BEAR_WARNING, .8f, if (ice) .8f else 1.3f)
        val struck = mutableSetOf<UUID>()
        loop(finish = { airborne = false; diving = false }) { age ->
            player.fallDistance = 0f
            val held = player.inventory.itemInMainHand
            val sameTool = if (ice) held.itemMeta?.persistentDataContainer?.get(SmashItems.POWER, PersistentDataType.STRING) == "polar_bear:1"
                else isCorrectItemForAbility(held)
            if (!diving && age >= 4 && (!player.isHandRaised || !sameTool || age >= 20)) dive()
            if (diving) {
                player.world.spawnParticle(Particle.SNOWFLAKE, player.location.add(0.0, .4, 0.0), 4, .25, .25, .25, .015)
                if (!ice) targets(player.location, 1.6 * boost).forEach {
                    if (it.uniqueId !in struck && hit(it, metadata.double("damage") ?: 5.0, 1.2, player.location)) struck += it.uniqueId
                }
            }
            val supported = player.isOnGround || (player.velocity.y <= .05 && player.world.rayTraceBlocks(
                player.location.clone().add(0.0, .05, 0.0), Vector(0, -1, 0), .2, FluidCollisionMode.NEVER, true) != null)
            if (age >= 5 && supported) {
                if (ice) freeze(player.location.clone())
                else player.world.playSound(player.location, Sound.BLOCK_SNOW_BREAK, 1f, .8f)
                return@loop false
            }
            age < 100
        }
    }
    fun dive() {
        if (!airborne || diving) return
        diving = true
        player.velocity = player.eyeLocation.direction.normalize().multiply((if (enhanced) 2.3 else 1.7) * boost)
        player.world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, .7f, .8f)
    }
    private fun freeze(center: Location) {
        val arena = game?.let(SmashService::arena) ?: return
        val radius = 9.0 * boost
        arena.destroy(center.clone().subtract(0.0,1.0,0.0),1.6,5)
        val ice = mutableSetOf<Block>()
        for (x in -ceil(radius).toInt()..ceil(radius).toInt()) for (z in -ceil(radius).toInt()..ceil(radius).toInt()) {
            if (x*x + z*z > radius*radius) continue
            for (dy in 1 downTo -3) {
                val block = center.block.getRelative(x, dy, z)
                if (!block.type.isSolid || !block.getRelative(0, 1, 0).isPassable) continue
                if (arena.temporaryBlock(block, if((x+z)%2==0)Material.PACKED_ICE else Material.ICE, 13)) ice += block
                break
            }
        }
        center.world.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.5f, .7f)
        val bounced = targets(center, radius).filter { abs(it.y - center.y) <= 3 }.toMutableSet()
        bounced.forEach { it.velocity = it.velocity.setY(1.0) }
        loop { age ->
            if (age < 12) {
                if (age % 3 == 0) ice.forEach { center.world.spawnParticle(Particle.SNOWFLAKE, it.location.add(.5, 1.1, .5), 1, .3, .1, .3, .01) }
                return@loop true
            }
            targets(center, radius + 1).filter { abs(it.y - center.y) <= 4 }.forEach { bounced += it }
            bounced.forEach { hit(it, 6.0, 1.6, center, sourceName = "Bitter Blizzard") }
            ice.forEach {
                center.world.spawnParticle(Particle.BLOCK, it.location.add(.5, 1.0, .5), 12, .4, .1, .4, .15, Material.PACKED_ICE.createBlockData())
            }
            center.world.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.5f, .7f)
            // The arena restores this ice at the shatter; a later overlapping ice cast keeps its own expiry.
            false
        }
    }
    override fun teardown() { super.teardown(); airborne = false; diving = false; player.fallDistance = 0f }
}
