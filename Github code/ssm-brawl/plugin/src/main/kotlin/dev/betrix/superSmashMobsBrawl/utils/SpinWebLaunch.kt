package dev.betrix.superSmashMobsBrawl.utils

import org.bukkit.util.Vector

object SpinWebLaunch {
    fun velocity(direction: Vector, pitch: Float): Vector {
        if (pitch > 32f) {
            val horizontal = direction.clone().setY(0.0)
            if (horizontal.lengthSquared() > .001) horizontal.normalize().multiply(.65)
            return horizontal.setY(1.0)
        }
        return direction.clone().multiply(1.8).apply { y = y.coerceAtLeast(.2) }
    }
}
