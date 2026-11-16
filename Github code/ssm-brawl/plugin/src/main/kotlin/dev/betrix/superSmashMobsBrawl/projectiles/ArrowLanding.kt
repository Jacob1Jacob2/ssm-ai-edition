package dev.betrix.superSmashMobsBrawl.projectiles

import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.util.Vector
import kotlin.math.atan2
import kotlin.math.sqrt

/** Shared impact pose; the owning projectile keeps this visual for exactly four seconds. */
object ArrowLanding {
    const val LIFETIME_TICKS = 80

    fun stick(arrow: Arrow, impact: Location, motion: Vector) {
        val direction = motion.clone().let { if (it.lengthSquared() > .000001) it.normalize() else Vector(0, -1, 0) }
        val at = impact.clone().subtract(direction.clone().multiply(.05))
        at.yaw = Math.toDegrees(atan2(direction.x, direction.z)).toFloat()
        at.pitch = Math.toDegrees(atan2(direction.y, sqrt(direction.x * direction.x + direction.z * direction.z))).toFloat()
        arrow.teleport(at)
        arrow.velocity = Vector()
        arrow.setGravity(false)
        arrow.damage = 0.0
        arrow.isCritical = false
        arrow.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
        val handle = (arrow as org.bukkit.craftbukkit.entity.CraftArrow).handle
        handle.setNoPhysics(true)
        handle.yRotO = at.yaw
        handle.xRotO = at.pitch
        handle.shakeTime = 7
        arrow.world.playSound(at, Sound.ENTITY_ARROW_HIT, 1f, 1f)
    }
}
