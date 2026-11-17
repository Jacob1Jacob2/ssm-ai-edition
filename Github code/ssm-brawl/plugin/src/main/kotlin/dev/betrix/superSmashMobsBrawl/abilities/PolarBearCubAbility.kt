package dev.betrix.superSmashMobsBrawl.abilities

import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.entity.PolarBear
import org.bukkit.util.Vector

class PolarBearCubAbility(player: Player) : ClassicKitAbility("cub_carrier", player) {
    override fun activate() { super.activate(); throwBears(1, false, displayName) }
    fun throwPack(adult: Boolean, source: String) {
        super.activate()
        throwBears(if (adult) 1 else 6, adult, source)
    }
    private fun throwBears(count: Int, adult: Boolean, source: String) {
        // A distinct launch cue remains audible over the cubs' quiet ambient calls.
        player.world.playSound(player.eyeLocation, Sound.ENTITY_FISHING_BOBBER_THROW, SoundCategory.PLAYERS, 1f, 1.15f)
        player.world.playSound(player.location, if (adult) Sound.ENTITY_POLAR_BEAR_WARNING else Sound.ENTITY_POLAR_BEAR_AMBIENT_BABY, 1f, 1f)
        repeat(count) { index ->
            val bear = player.world.spawn(player.eyeLocation.clone().add(player.eyeLocation.direction.multiply(.8)), PolarBear::class.java)
            if (adult) bear.setAdult() else bear.setBaby()
            bear.ageLock = true
            bear.removeWhenFarAway = false
            bear.canPickupItems = false
            summon(bear, if (adult) metadata.double("adultHealth") ?: 30.0 else metadata.double("cubHealth") ?: 12.0, tagged = true)
            bear.velocity = player.eyeLocation.direction.rotateAroundY((index - (count - 1) / 2.0) * .12)
                .multiply(upgradedValue("throwSpeed", 1.4)).add(Vector(0.0, .35, 0.0))
            var lastAttack = -20
            loop(finish = { remove(bear) }) { age ->
                if (!bear.isValid || bear.isDead || bear.location.y < bear.world.minHeight) return@loop false
                if (age < 8) return@loop true
                val target = targets(bear.location, 40.0).minByOrNull { it.location.distanceSquared(bear.location) }
                if (target == null) {
                    if (age % 10 == 0 && bear.location.distanceSquared(player.location) > 16) bear.pathfinder.moveTo(player, 1.15)
                } else {
                    if (age % 5 == 0) bear.pathfinder.moveTo(target, (if(adult)1.95 else 1.3) * itemBoost)
                    val facing = target.location.toVector().subtract(bear.location.toVector())
                    if (facing.lengthSquared() > .01) bear.setRotation(facing.toLocation(bear.world).apply { direction = facing }.yaw, 0f)
                    if (bear.boundingBox.clone().expand(if (adult) 1.0 else .7).overlaps(target.boundingBox) &&
                        age - lastAttack >= 20 && bear.hasLineOfSight(target)) {
                        lastAttack = age
                        if (hit(target, if (adult) metadata.double("adultDamage") ?: 6.0 else metadata.double("cubDamage") ?: 3.0,
                                if (adult) 1.2 else .7, bear.location, sourceName = source)) {
                            bear.world.playSound(bear.location, Sound.ENTITY_POLAR_BEAR_WARNING, .6f, if (adult) .8f else 1.6f)
                            bear.world.spawnParticle(Particle.SWEEP_ATTACK, bear.location.add(0.0, .5, 0.0), 1)
                        }
                    }
                }
                true
            }
        }
    }
}
