package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.extensions.disguise
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import kotlin.math.min

/** Aim at the actual mob body, including short disguises, rather than the player's eyes. */
object SmashTargeting {
    fun center(player: Player): Vector = (player.disguise?.boundingBox ?: player.boundingBox).center

    fun steer(position: Vector, velocity: Vector, target: Vector, targetVelocity: Vector,
              speed: Double, strength: Double): Vector {
        val distance = target.distance(position)
        if (distance < 0.001) return velocity.clone()
        // Lead a moving body briefly. Long predictions overshoot jumping or knocked-back players.
        val lead = targetVelocity.clone().multiply(min(2.0, distance / speed) * .5)
        if (lead.lengthSquared() > .64) lead.normalize().multiply(.8)
        val desired = target.clone().add(lead).subtract(position).normalize()
        val turn = (strength + (1.0 - distance / (speed * 4)).coerceIn(0.0, 1.0) * strength).coerceAtMost(.95)
        val direction = velocity.clone().normalize().multiply(1 - turn).add(desired.multiply(turn))
        if (direction.lengthSquared() < .001) return target.clone().subtract(position).normalize().multiply(speed)
        return direction.normalize().multiply(speed)
    }
}
