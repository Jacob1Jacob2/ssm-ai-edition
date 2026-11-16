package dev.betrix.superSmashMobsBrawl.passives

import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageType
import dev.betrix.superSmashMobsBrawl.events.Damager
import dev.betrix.superSmashMobsBrawl.extensions.doKnockback
import gg.flyte.twilight.event.event
import gg.flyte.twilight.scheduler.repeatingTask
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Item
import org.bukkit.event.EventPriority
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector

class DeadlyBonesPassive(player: Player) : BrawlPassive("deadly_bones", player) {
    private val bones = mutableSetOf<Item>()

    override fun setup() {
        super.setup()
        listeners.add(event<BrawlDamageEvent>(priority = EventPriority.MONITOR) {
            if (victim != player || isCancelled || appliedDamage < 2.0 || (damager as? Damager.DamagerLivingEntity)?.livingEntity == player) return@event
            val game = minigameService.getMinigameForPlayer(player) ?: return@event
            if (game.respawnManager.isRespawning(player)) return@event
            val bone = player.world.dropItem(player.location.add(0.0, 0.5, 0.0), ItemStack.of(Material.BONE)).apply {
                pickupDelay = Int.MAX_VALUE
                setCanMobPickup(false)
                setCanPlayerPickup(false)
                velocity = Vector(0.0, 0.2, 0.0)
            }
            bones.add(bone)
            var age = 0
            runnables.add(repeatingTask(1) {
                if (!bone.isValid) { bones.remove(bone); cancel(); return@repeatingTask }
                if (++age < (metadata.int("fuseTicks") ?: 30)) return@repeatingTask
                val point = bone.location
                bone.remove()
                bones.remove(bone)
                cancel()
                point.world.spawnParticle(Particle.EXPLOSION, point, 1)
                point.world.playSound(point, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.4f)
                game.allPlayers().mapNotNull { it.player }.filter {
                    it != player && it.world == point.world && !game.arePlayersOnSameTeam(player, it) &&
                        it.location.distanceSquared(point) <= 6.25
                }.forEach { target ->
                    val health = target.health
                    val hit = BrawlDamageEvent(target, Damager.DamagerLivingEntity(player),
                        metadata.double("damage") ?: 2.0, damageType = BrawlDamageType.Explosion, sourceName = "Deadly Bones")
                    hit.callEvent()

                }
            })
        })
    }

    override fun teardown() {
        super.teardown()
        bones.forEach { it.remove() }
        bones.clear()
    }
}
