package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageType
import dev.betrix.superSmashMobsBrawl.events.Damager
import dev.betrix.superSmashMobsBrawl.extensions.doKnockback
import dev.betrix.superSmashMobsBrawl.extensions.playSound
import dev.betrix.superSmashMobsBrawl.extensions.sendDebugMessage
import dev.betrix.superSmashMobsBrawl.projectiles.BrawlProjectile
import dev.betrix.superSmashMobsBrawl.projectiles.ProjectileAction
import gg.flyte.twilight.extension.round
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

class SulphurBombAbility(player: Player) : BrawlAbility("sulphur_bomb", player) {

    private val projectileKnockbackModifier get() = upgradedValue("projectileKnockbackModifier", 2.5)
    private val projectileDamage = metadata.double("projectileDamage") ?: 6.5
    private val projectileVelocityMultiplier =
        metadata.double("projectileVelocityMultiplier") ?: 1.55
    private val projectileSize = (metadata.double("projectileSize") ?: 0.65).coerceAtLeast(0.1)
    private val projectileTrailMaxParticles = metadata.int("projectileTrailMaxParticles") ?: 8

    private val activeProjectiles = mutableListOf<BrawlProjectile>()

    override fun activate() {
        super.activate()
        throwProjectile()
    }

    override fun teardown() {
        activeProjectiles.toList().also { activeProjectiles.clear() }.forEach { it.teardown() }
        super.teardown()
    }

    private fun throwProjectile() {
        player.world.playSound(player.location, Sound.ENTITY_CREEPER_DEATH, 2f, 1.5f)

        val sulphurBombProjectile =
            BrawlProjectile.potion(
                    player,
                    ItemStack.of(Material.COAL),
                    "abilities.sulphur_bomb.name",
                )
                .velocityMultiplier(projectileVelocityMultiplier)
                .projectileSize(projectileSize)
                .impactEffect(Particle.EXPLOSION)
                .onHitEntity { entity, projectile ->
                    handleSulphurBombHit(entity, projectile)
                    ProjectileAction.DESTROY
                }
                .onHitBlock { _, projectile ->
                    handleSulphurBombHit(null, projectile)
                    ProjectileAction.DESTROY
                }
                .onTeardown { activeProjectiles.remove(it) }
                .upgraded().launch()

        activeProjectiles.add(sulphurBombProjectile)
    }

    private fun handleSulphurBombHit(entity: Entity?, projectile: BrawlProjectile) {
        projectile.projectileEntity?.location?.let { at ->
            at.world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.5f)
        }
        player.sendDebugMessage(
            "[SB] Sulphur bomb exploded at ${projectile.projectileEntity?.x?.round(1)}, ${
                projectile.projectileEntity?.y?.round(
                    1
                )
            }, ${projectile.projectileEntity?.z?.round(1)} after ${projectile.projectileEntity?.ticksLived} ticks"
        )

        if (entity != null && entity is LivingEntity) {
            player.sendDebugMessage("[SB] ${entity.name} was hit")

            val damageEvent =
                BrawlDamageEvent(
                    entity,
                    Damager.DamagerLivingEntity(player),
                    projectileDamage,
                    0.0,
                    BrawlDamageType.Projectile, sourceName = displayName)

            damageEvent.callEvent()
            if (damageEvent.appliedDamage <= 0) return
            if (entity is Player) player.playSound(player.location, Sound.ENTITY_ARROW_HIT_PLAYER, 0.5f, 1f)

            entity.doKnockback(
                projectileKnockbackModifier,
                projectileDamage,
                entity.health,
                projectile.projectileEntity?.location?.toVector(),
                null,
            )
        } else {
            player.sendDebugMessage("[SB] No entity was hit")
        }
    }
}
