package dev.betrix.superSmashMobsBrawl.passives

import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.Damager
import gg.flyte.twilight.event.event
import gg.flyte.twilight.scheduler.repeatingTask
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/** The snow aura follows the Blizzard tank, shrinking as its snowballs are spent. */
class ArcticAuraPassive(player: Player) : BrawlPassive("arctic_aura", player) {
    private val snow = mutableMapOf<Block, Long>()
    private var ticks = 0L
    private fun onSnow(target: org.bukkit.entity.LivingEntity) = snow.containsKey(target.location.block) ||
        snow.containsKey(target.location.clone().subtract(0.0, 0.1, 0.0).block)
    override fun setup() {
        super.setup()
        listeners.add(event<BrawlDamageEvent>(priority = EventPriority.NORMAL) {
            if (damageType == null && (damager as? Damager.DamagerLivingEntity)?.livingEntity == player && onSnow(victim)) {
                damage += 1.0
                knockbackMultiplier *= 0.25
            }
        })
        runnables.add(repeatingTask(1) {
            ticks++
            val expired = snow.filter { (block, until) -> until <= ticks || block.type != Material.SNOW }.keys.toList()
            expired.forEach { if (it.type == Material.SNOW) it.setType(Material.AIR, false); snow.remove(it) }
            val game = minigameService.getMinigameForPlayer(player) ?: return@repeatingTask
            if (ticks % 5 == 0L) {
                val radius=5.0*player.exp + if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"snowman:0"))5.0 else 0.0
                val extent=kotlin.math.ceil(radius).toInt()
                for (dx in -extent..extent) for (dz in -extent..extent) for(dy in -1..1) {
                    if (dx*dx+dz*dz+dy*dy > radius*radius) continue
                    val block=player.location.block.getRelative(dx,dy,dz)
                    if(dev.betrix.superSmashMobsBrawl.smash.SmashService.protectsCrystal(block))continue
                    if ((block.type == Material.AIR || (block.type == Material.SNOW && snow.containsKey(block))) && block.getRelative(0,-1,0).type.isSolid && block.getRelative(0,-1,0).type !in setOf(Material.ICE,Material.PACKED_ICE,Material.BLUE_ICE,Material.FROSTED_ICE)) {
                        block.setType(Material.SNOW,false)
                        snow[block]=ticks+40
                    }
                }
            }
            if (ticks % 5 == 0L) game.allPlayers().mapNotNull { it.player }.forEach { target ->
                if (target != player && target.gameMode == org.bukkit.GameMode.SURVIVAL && !game.arePlayersOnSameTeam(player, target) && onSnow(target))
                    target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 8, if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"snowman:0"))3 else 1, false, false))
            }
        })
    }

    override fun teardown() {
        super.teardown()
        snow.keys.forEach { if (it.type == Material.SNOW) it.setType(Material.AIR, false) }
        snow.clear()
    }
}
