package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageType
import dev.betrix.superSmashMobsBrawl.events.Damager
import dev.betrix.superSmashMobsBrawl.extensions.doKnockback
import dev.betrix.superSmashMobsBrawl.extensions.setVelocity
import gg.flyte.twilight.event.event
import gg.flyte.twilight.extension.getNearbyEntities
import kotlin.math.min
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.util.Vector

class ExplodeAbility(player: Player) : BrawlAbility("explode", player) {

    private var isExplodeActive = false
    private val fuseTimeTicks = metadata.int("fuseTimeTicks") ?: 30
    private val explosionRadius get() = upgradedValue("explosionRadius", 8.0)
    private val explosionKnockbackMultiplier get() = upgradedValue("explosionKnockbackMultiplier", 2.5)
    private val explosionVelocityStrength = metadata.double("explosionVelocityStrength") ?: 1.8

    override fun canActivate(sendMessage: Boolean): Boolean {
        if (isExplodeActive) {
            if (sendMessage) {
                player.sendMessage(lang.t("messages.abilities.explode.alreadyCharging"))
            }
            return false
        }

        return super.canActivate(sendMessage) && !isExplodeActive
    }

    override fun teardown() {
        isExplodeActive = false
        resetPlayerData()
        super.teardown()
    }

    override fun setup() {
        listeners.add(
            event<PlayerToggleSneakEvent> {
                if (this@ExplodeAbility.player != player || !isExplodeActive) {
                    return@event
                }

                setCooldown()
                isExplodeActive = false
                resetPlayerData()
            }
        )

        super.setup()
    }

    override fun activate() {
        val currentTimeAtActivation = System.currentTimeMillis()

        super.activate()

        isExplodeActive = true
        player.setGravity(false)

        player.velocity = Vector()
        player.exp = 0f

        runnables.add(
            repeatingTask(1) {
                if (!isExplodeActive) {
                    cancel()

                    return@repeatingTask
                }

                player.velocity = Vector()
                player.fallDistance = 0f
                val elapsed = System.currentTimeMillis() - currentTimeAtActivation
                player.exp = min(elapsed / (fuseTimeTicks * 50f), 0.9999f)

                // Classic Explode ramps the hiss by elapsed seconds, including its
                // final fuse tick, immediately followed by one full-volume blast.
                val volume = 0.5f + elapsed / 1000f

                player.world.playSound(player.location, Sound.ENTITY_CREEPER_PRIMED, volume, volume)

                if (elapsed >= fuseTimeTicks * 50L) {
                    cancel()
                    detonate(currentTimeAtActivation)
                }
            }
        )
    }

    private fun detonate(currentTimeAtActivation: Long) {
                resetPlayerData()

                isExplodeActive = false

                player.world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 2f, 1f)
                player.world.spawnParticle(Particle.EXPLOSION_EMITTER, player.location, 1)
                breakNearbyTerrain(player.location)

                player
                    .getNearbyEntities(explosionRadius)
                    .filter { it is LivingEntity && it != player }
                    .forEach { entity ->
                        val distance = player.location.distance(entity.location)
                        val damage =
                            ((0.1 + 0.9 * ((explosionRadius - distance) / explosionRadius)) * 20) *
                                0.75

                        entity.doKnockback(
                            explosionKnockbackMultiplier,
                            damage,
                            (entity as LivingEntity).health,
                            player.location.toVector(),
                            null,
                        )

                        val damageEvent =
                            BrawlDamageEvent(
                                entity,
                                Damager.DamagerLivingEntity(player),
                                damage,
                                explosionKnockbackMultiplier,
                                BrawlDamageType.Explosion, sourceName = displayName)

                        damageEvent.callEvent()
                    }

                player.setVelocity(explosionVelocityStrength, 0.2, 1.4, true)

                setCooldown(currentTimeAtActivation)
    }

    private fun resetPlayerData() {
        player.setGravity(true)
        player.exp = 0f
    }
}
