package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageType
import dev.betrix.superSmashMobsBrawl.events.Damager
import dev.betrix.superSmashMobsBrawl.extensions.disguise
import dev.betrix.superSmashMobsBrawl.extensions.doKnockback
import gg.flyte.twilight.event.event
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerVelocityEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/** Short, heavy kick. Bracing resists knockback but does not prevent damage. */
class BoneKickAbility(player: Player) : BrawlAbility("bone_kick", player) {
    private var bracedUntil = 0
    val isBracing: Boolean get() = player.ticksLived < bracedUntil

    override fun setup() {
        super.setup()
        listeners.add(event<PlayerVelocityEvent> {
            if (player == this@BoneKickAbility.player && isBracing) isCancelled = true
        })
    }

    override fun activate() {
        super.activate()
        val game = minigameService.getMinigameForPlayer(player) ?: return
        val direction = player.eyeLocation.direction
        val center = player.location.add(direction.clone().setY(0.0).multiply(1.5)).add(0.0, 0.8, 0.0)
        val range = upgradedValue("range", 2.5)
        val area = org.bukkit.util.BoundingBox.of(center, range, 1.5, range)
        bracedUntil = player.ticksLived + (metadata.int("braceTicks") ?: 16)
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 16, 3, false, false))
        player.world.spawnParticle(Particle.LARGE_SMOKE, center, 12, 0.4, 0.3, 0.4, 0.0)
        var connected = false
        game.allPlayers().mapNotNull { it.player }.filter {
            it != player && it.world == player.world &&
                !game.arePlayersOnSameTeam(player, it) &&
                area.overlaps(it.disguise?.boundingBox ?: it.boundingBox)
        }.forEach { target ->
            val health = target.health
            val hit = BrawlDamageEvent(target, Damager.DamagerLivingEntity(player),
                metadata.double("damage") ?: 6.0, damageType = BrawlDamageType.MeleeAttack, sourceName = displayName)
            hit.callEvent()
            if (hit.appliedDamage > 0 && !game.respawnManager.isRespawning(target) &&
                target.gameMode == org.bukkit.GameMode.SURVIVAL) {
                connected = true
                target.doKnockback(upgradedValue("knockback", 2.0), hit.damage, health,
                    player.location.toVector(), null)
            }
        }
        // The crack of bone is the kick landing; a kick at thin air is silent.
        if (connected) repeat(2) { player.world.playSound(player.location, Sound.ENTITY_SKELETON_HURT, 4f, 0.6f) }
    }
}
