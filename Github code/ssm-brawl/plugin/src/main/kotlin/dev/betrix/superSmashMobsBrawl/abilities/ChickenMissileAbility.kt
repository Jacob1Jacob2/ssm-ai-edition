package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageType
import dev.betrix.superSmashMobsBrawl.events.Damager
import dev.betrix.superSmashMobsBrawl.extensions.disguise
import dev.betrix.superSmashMobsBrawl.extensions.setVelocity
import dev.betrix.superSmashMobsBrawl.utils.ClassicEffects
import dev.betrix.superSmashMobsBrawl.utils.ChickenMissileTargets
import gg.flyte.twilight.extension.getNearbyEntities
import org.bukkit.*
import org.bukkit.entity.Chicken
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

class ChickenMissileAbility(player: Player) : BrawlAbility("chicken_missile", player) {
    private val damage = metadata.double("damage") ?: 8.0
    private val explosionRadius get() = upgradedValue("explosionRadius", 3.0)
    private val directHitboxRadius get() = upgradedValue("directHitboxRadius", 2.0)
    private val minimumHitTimeMs = metadata.long("minimumHitTimeMs") ?: 200L
    private val durationMs = metadata.long("durationMs") ?: 4000L
    private val velocityStrength get() = upgradedValue("velocityStrength", 0.6)
    private val knockbackStrength get() = upgradedValue("knockbackStrength", 1.6)
    private val knockbackYAdd get() = upgradedValue("knockbackYAdd", 0.8)
    private val knockbackYMax get() = upgradedValue("knockbackYMax", 10.0)

    private val missiles = mutableSetOf<Chicken>()

    override fun activate() {
        super.activate()

        val chicken =
            player.world
                .spawn(player.eyeLocation.add(player.location.direction), Chicken::class.java)
                .apply {
                    setBaby()
                    ageLock = true
                }

        missiles.add(chicken)
        val direction = player.location.direction.multiply(velocityStrength)
        var lastLocation: Location? = null
        val launchedAt = System.currentTimeMillis()
        var flightTicks = 0

        runnables.add(
            repeatingTask(0) {
                if (!chicken.isValid || chicken.isDead) {
                    missiles.remove(chicken)
                    cancel()
                    return@repeatingTask
                }

                chicken.velocity = direction
                if(flightTicks++ % 2 == 0)ClassicEffects.chickenMissileSmoke(chicken.location,direction)
                chicken.world.playSound(chicken.location, Sound.ENTITY_CHICKEN_HURT, 0.3f, 1.5f)

                val elapsedTime = System.currentTimeMillis() - launchedAt

                if (elapsedTime < minimumHitTimeMs) {
                    return@repeatingTask
                }

                var shouldDetonate = false

                if (elapsedTime >= durationMs) {
                    shouldDetonate = true
                } else {
                    val nearbyEntities =
                        chicken
                            .getNearbyEntities(directHitboxRadius)
                            .filterIsInstance<LivingEntity>()
                            .filter { it != player && it != chicken }

                    val collisionBox = chicken.boundingBox.clone().expand(0.15).let { box ->
                        box.expand(box.widthX * 0.075, box.height * 0.075, box.widthZ * 0.075)
                        ChickenMissileTargets.enlarged(box)
                    }
                    for (entity in nearbyEntities) {
                        val targetBox = (entity as? Player)?.disguise?.boundingBox ?: entity.boundingBox
                        if (canDamageEntity(entity) && collisionBox.overlaps(targetBox)) {
                            shouldDetonate = true
                            break
                        }
                    }

                    if (
                        lastLocation != null && lastLocation!!.distance(chicken.location) < 0.2 ||
                            chicken.isOnGround
                    ) {
                        shouldDetonate = true
                    }
                    lastLocation = chicken.location.clone()
                }

                if (shouldDetonate) {
                    detonateChicken(chicken)
                    cancel()
                }
            }
        )
    }

    private fun detonateChicken(chicken: Chicken) {
        val explosionLocation = chicken.location
        var acceptedHit = false

        chicken
            .getNearbyEntities(explosionRadius)
            .filterIsInstance<LivingEntity>()
            .filter { it != player && it != chicken && canDamageEntity(it) }
            .forEach { entity ->
                val hit = BrawlDamageEvent(
                        entity,
                        Damager.DamagerLivingEntity(player),
                        damage,
                        knockbackMultiplier = 0.0,
                        damageType = BrawlDamageType.Explosion, sourceName = displayName)
                hit.callEvent()
                if (hit.appliedDamage <= 0) return@forEach
                acceptedHit = true
                if (!entity.isValid || entity.isDead || !canDamageEntity(entity)) return@forEach

                val living2d = entity.location.toVector().setY(0.0)
                val chicken2d = explosionLocation.toVector().setY(0.0)
                val knockbackDirection = living2d.subtract(chicken2d).let {
                    if (it.lengthSquared() < 0.001) player.location.direction.setY(0.0) else it.normalize()
                }

                entity.setVelocity(
                    knockbackDirection,
                    knockbackStrength,
                    false,
                    0.0,
                    knockbackYAdd,
                    knockbackYMax,
                    true,
                )
            }

        explosionLocation.world.spawnParticle(
            Particle.EXPLOSION_EMITTER,
            explosionLocation,
            1,
            0.0,
            0.0,
            0.0,
            0.0,
        )

        explosionLocation.world.playSound(explosionLocation, Sound.ENTITY_GENERIC_EXPLODE, 2f, 1.2f)

        if (acceptedHit) {
            dev.betrix.superSmashMobsBrawl.utils.HitFeedback.play(player)
            setCooldown(0L)
            spawnFirework(explosionLocation.clone().add(0.0, 0.6, 0.0))
        }

        chicken.remove()
        missiles.remove(chicken)
    }

    override fun teardown() {
        super.teardown()
        missiles.forEach { it.remove() }
        missiles.clear()
    }

    private fun spawnFirework(location: Location) {
        dev.betrix.superSmashMobsBrawl.utils.ClassicEffects.fireworkBurst(location, Color.WHITE, 15)
    }

    private fun canDamageEntity(entity: LivingEntity) = ChickenMissileTargets.canHit(player, entity)
}
