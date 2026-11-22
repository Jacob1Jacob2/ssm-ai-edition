package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.Damager
import gg.flyte.twilight.extension.addY
import gg.flyte.twilight.scheduler.TwilightRunnable
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.util.Vector

class MilkSpiralAbility(player: Player) : BrawlAbility("milk_spiral", player) {

    private val spiralDurationMs = metadata.long("spiralDurationMs") ?: 3000
    private val velocityDurationMs = metadata.long("velocityDurationMs") ?: 1800
    private val hitboxRadius get() = upgradedValue("hitboxRadius", 2.0)
    private val damage = metadata.double("damage") ?: 5.0
    private val maxTimesHit = metadata.int("maxTimesHit") ?: 2
    private val damageCooldownMs = metadata.long("damageCooldownMs") ?: 250
    private val particleSpiralRadius get() = upgradedValue("particleSpiralRadius", 1.5)

    private val lastDamageTime = hashMapOf<Player, Long>()
    private val timesHit = hashMapOf<Player, Int>()

    private var spiralRunnable: TwilightRunnable? = null
    private var riding = false
    /** Whether the spiral is carrying its player along at this moment. Only read (by bots, to know when to get off). */
    val ridingNow get() = riding
    override fun canActivate(sendMessage: Boolean) = riding || super.canActivate(sendMessage)

    override fun activate() {
        if (riding) { riding = false; player.velocity = Vector(0.0,-0.15,0.0); return }
        riding = true
        super.activate()

        spiralRunnable?.cancel()
        lastDamageTime.clear()
        timesHit.clear()

        val direction = player.location.direction
        val spiralLocation =
            player.location.clone().add(Vector(0.0, 1.0, 0.0)).add(direction.clone().multiply(2))
        

        spiralRunnable =
            repeatingTask(1) {
                var first = true
                if (elapsedSinceLastActivation >= spiralDurationMs) {
                    riding = false
                    cancel()
                    return@repeatingTask
                }

                if (player.isSneaking || elapsedSinceLastActivation >= velocityDurationMs) {
                    riding = false
                }

                if (riding) {
                    player.velocity =
                        direction.clone().add(Vector(0.0, 0.1, 0.0)).normalize().multiply(0.45 * itemBoost)
                }

                val oldLocation = spiralLocation.clone()
                val totalDistance = 0.7 * itemBoost

                spiralLocation.add(direction.clone().multiply(totalDistance))

                val circleFirst = Vector(-direction.z, 0.0, direction.x).normalize()
                val circleSecond = direction.clone().crossProduct(circleFirst).normalize()

                val speed = 3
                var theta = player.ticksLived.toDouble() / speed
                var totalAddedDistance = 0.0

                while (totalAddedDistance < totalDistance) {
                    val firstParticle =
                        oldLocation
                            .clone()
                            .add(
                                getCirclePoint(
                                    circleFirst,
                                    circleSecond,
                                    theta,
                                    particleSpiralRadius,
                                )
                            )
                    val secondParticle =
                        oldLocation
                            .clone()
                            .add(
                                getCirclePoint(
                                    circleFirst,
                                    circleSecond,
                                    theta + Math.PI,
                                    particleSpiralRadius,
                                )
                            )

                    if (first) {
                        firstParticle.world.playSound(
                            firstParticle,
                            Sound.ENTITY_FISHING_BOBBER_SPLASH,
                            0.2f,
                            0.75f,
                        )
                        secondParticle.world.playSound(
                            secondParticle,
                            Sound.ENTITY_FISHING_BOBBER_SPLASH,
                            0.2f,
                            0.75f,
                        )
                        first = false
                    }

                    Particle.FIREWORK.builder()
                        .location(firstParticle)
                        .count(1)
                        .extra(0.0)
                        .receivers(96, true)
                        .spawn()

                    Particle.FIREWORK.builder()
                        .location(secondParticle)
                        .count(1)
                        .extra(0.0)
                        .receivers(96, true)
                        .spawn()

                    val distance = totalDistance / 4
                    oldLocation.add(direction.clone().multiply(distance))
                    theta += (1.0 / speed) * (distance / totalDistance)
                    totalAddedDistance += distance
                }

                player.world.players
                    .filter { it != player }
                    .forEach {
                        if (
                            lastDamageTime[it] != null &&
                                System.currentTimeMillis() - lastDamageTime[it]!! < damageCooldownMs
                        ) {
                            return@forEach
                        }

                        if (timesHit[it] != null && timesHit[it]!! >= maxTimesHit) {
                            return@forEach
                        }

                        if (it.eyeLocation.distance(spiralLocation) >= hitboxRadius) {
                            return@forEach
                        }

                        lastDamageTime[it] = System.currentTimeMillis()
                        timesHit.putIfAbsent(it, 0)
                        timesHit[it] = timesHit[it]!! + 1

                        Particle.FIREWORK.builder()
                            .location(it.location.addY(1.0))
                            .offset(0.2, 0.2, 0.2)
                            .count(30)
                            .extra(0.3)
                            .receivers(96, true)
                            .spawn()
                        it.world.playSound(
                            it.eyeLocation,
                            Sound.ENTITY_FISHING_BOBBER_SPLASH,
                            0.2f,
                            2f,
                        )

                        BrawlDamageEvent(it, Damager.DamagerLivingEntity(player), damage, knockbackMultiplier = itemBoost, damageType = dev.betrix.superSmashMobsBrawl.events.BrawlDamageType.Projectile, sourceName = displayName, automaticKnockback = true)
                            .callEvent()
                    }
            }

        spiralRunnable?.let { runnables.add(it) }
    }

    override fun teardown() {
        spiralRunnable?.cancel()
        spiralRunnable = null
        riding = false
        super.teardown()
    }

    private fun getCirclePoint(
        circleFirst: Vector,
        circleSecond: Vector,
        theta: Double,
        radius: Double,
    ): Vector {
        val particleOffset = circleFirst.clone().multiply(Math.cos(theta) * radius)
        particleOffset.add(circleSecond.clone().multiply(Math.sin(theta) * radius))

        return particleOffset
    }
}
