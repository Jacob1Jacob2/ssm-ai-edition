package dev.betrix.superSmashMobsBrawl.abilities

import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Wolf
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID

class CubTackleAbility(player: Player) : ClassicKitAbility("cub_tackle", player) {
    private var caught: LivingEntity? = null
    private var cub: Wolf? = null
    private var originalSlowness: PotionEffect? = null
    private var caughtKit: dev.betrix.superSmashMobsBrawl.kits.BrawlKit? = null
    private var caughtTime = 0L
    fun isTackled(target: LivingEntity) = caught == target && cub?.isValid == true
    fun release() {
        caught?.let { target ->
            if ((target !is Player || kitService.getKitForPlayer(target) === caughtKit) &&
                target.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier == 1) {
                target.removePotionEffect(PotionEffectType.SLOWNESS)
                originalSlowness?.let { old ->
                    val remaining = old.duration - ((System.currentTimeMillis() - caughtTime) / 50).toInt()
                    if (remaining > 0) target.addPotionEffect(PotionEffect(old.type, remaining, old.amplifier, old.isAmbient, old.hasParticles(), old.hasIcon()))
                }
            }
        }
        originalSlowness = null
        caughtKit = null
        caught = null
        remove(cub)
        cub = null
    }

    override fun activate() {
        super.activate()
        release()
        val wolf = player.world.spawn(player.eyeLocation, Wolf::class.java) { it.variant = Wolf.Variant.PALE }
        wolf.setBaby()
        summon(wolf, metadata.double("cubHealth") ?: 30.0)
        wolf.setGravity(false)
        cub = wolf
        var position = player.eyeLocation
        val velocity = position.direction.multiply(upgradedValue("speed", 1.8))
        var caughtAt = -1
        var landedAt = -1
        player.world.playSound(position, Sound.ENTITY_WOLF_AMBIENT, 1f, 1.8f)
        loop(finish = { if (cub === wolf) release() else remove(wolf) }) { age ->
            if (!wolf.isValid || cub !== wolf) false
            else if (caught != null) {
                val target = caught!!
                if (!targetable(target) || age - caughtAt >= (metadata.int("tackleTicks") ?: 70)) false
                else {
                    wolf.setGravity(false)
                    wolf.teleport(target.location.add(0.0, 0.55, 0.0))
                    wolf.velocity = org.bukkit.util.Vector()
                    target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS,18,1,false,false))
                    true
                }
            } else if (landedAt >= 0) {
                // Keep the missed cub on the ground for two seconds after impact.
                age - landedAt < 40
            } else if (age > 40) false
            else {
                val impact = collision(position, velocity, 0.35)
                if (impact != null) {
                    val target = impact.second
                    if (target == null) {
                        landedAt = age
                        wolf.teleport(impact.first.clone().subtract(velocity.clone().normalize().multiply(0.2)))
                        wolf.velocity = org.bukkit.util.Vector()
                        wolf.setGravity(true)
                        true
                    } else if (!hit(target, metadata.double("damage") ?: 5.0)) false
                    else {
                        target.world.playSound(target.location, Sound.ENTITY_WOLF_GROWL, 1.5f, 1.5f)
                        caught = target
                        caughtAt = age
                        originalSlowness = target.getPotionEffect(PotionEffectType.SLOWNESS)
                        caughtKit = (target as? Player)?.let { kitService.getKitForPlayer(it) }
                        caughtTime = System.currentTimeMillis()
                        true
                    }
                } else {
                    position = position.add(velocity)
                    wolf.teleport(position)
                    velocity.y -= 0.08
                    true
                }
            }
        }
    }

    override fun teardown() { release(); super.teardown() }
}

class WolfStrikeAbility(player: Player) : ClassicKitAbility("wolf_strike", player) {
    override fun activate() {
        super.activate()
        val direction = player.eyeLocation.direction
        val hitTargets = mutableSetOf<UUID>()
        player.velocity = direction.clone().multiply(upgradedValue("speed", 1.5))
        player.world.playSound(player.location, Sound.ENTITY_WOLF_AMBIENT, 1f, 1.2f)
        loop { age ->
            if (age >= (metadata.int("durationTicks") ?: 12)) false
            else {
                player.fallDistance = 0f
                player.world.spawnParticle(Particle.CRIT, player.location, 5, 0.3, 0.3, 0.3, 0.05)
                targets(player.location, 1.8 * itemBoost).forEach { target ->
                    if (hitTargets.add(target.uniqueId)) {
                        val tackle = ownerKit?.getAbility("cub_tackle") as? CubTackleAbility
                        val multiplier = if (tackle?.isTackled(target) == true) 3.0 else 2.0
                        if (hit(target, metadata.double("damage") ?: 7.0, multiplier)) {
                            if (tackle?.isTackled(target) == true) tackle.release()
                        }
                    }
                }
                true
            }
        }
    }
}
