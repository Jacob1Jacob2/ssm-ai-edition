package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageType
import dev.betrix.superSmashMobsBrawl.events.Damager
import dev.betrix.superSmashMobsBrawl.extensions.setVelocity
import dev.betrix.superSmashMobsBrawl.projectiles.BrawlProjectile
import dev.betrix.superSmashMobsBrawl.projectiles.ProjectileAction
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.util.Vector

class RopedArrowAbility(player: Player) : BrawlAbility("roped_arrow", player) {

    private val arrowVelocityModifier = metadata.double("arrowVelocityModifier") ?: 2.4
    private val arrowDamage = metadata.double("arrowDamage") ?: 6.0

    private val activeProjectiles = mutableListOf<BrawlProjectile>()

    override fun activate() {
        super.activate()

        val ropedArrow =
            BrawlProjectile.arrow(player, arrowVelocityModifier, "abilities.roped_arrow.name")
                .onHitBlock { block, projectile ->
                    pullPlayerToLocation(block.location.add(0.5, 1.0, 0.5), projectile.velocityBeforeImpact)
                    ProjectileAction.DESTROY
                }
                .onHitEntity { entity, projectile ->
                    pullPlayerToLocation(entity.location, projectile.velocityBeforeImpact)

                    BrawlDamageEvent(
                            entity,
                            Damager.DamagerLivingEntity(player),
                            arrowDamage, knockbackMultiplier = itemBoost,
                            damageType = BrawlDamageType.Projectile, sourceName = displayName,
                            automaticKnockback = true, hitCooldownTicks = 10, hitSound = true)
                        .callEvent()

                    ProjectileAction.DESTROY
                }
                .onTeardown { activeProjectiles.remove(it) }
                .upgraded().launch()

        activeProjectiles.add(ropedArrow)
    }

    override fun teardown() {
        activeProjectiles.toList().also { activeProjectiles.clear() }.forEach { it.teardown() }
        super.teardown()
    }

    private fun pullPlayerToLocation(location: Location, velocity: Vector) {
        val playerLocationVector = player.location.toVector()
        val arrowLocationVector = location.toVector()

        val delta = arrowLocationVector.subtract(playerLocationVector)

        // A rope fired into the floor is a vault: never pull the player into the block.
        if (delta.y <= 0.25) {
            val horizontal = delta.clone().setY(0.0)
            if (horizontal.lengthSquared() > 0.001) horizontal.normalize().multiply(0.65)
            player.velocity = horizontal.setY(1.0).multiply(itemBoost)
            player.fallDistance = 0f
            return
        }

        if (delta.lengthSquared() == 0.0) {
            return
        }

        val trajectory = delta.normalize()
        val mult = velocity.length() / 3.0

        player.setVelocity(trajectory, 0.4 + mult, false, 0.0, 0.2 * mult, 1.2 * mult, true)

    }
}
