package dev.betrix.superSmashMobsBrawl.utils

import kotlin.math.pow

object NewKitMath {
    fun hurrDamage(distance: Double) = 1.0 + 8.0 * (distance / 14.0).coerceIn(0.0,1.0)
    fun hurrRadius(distance: Double) = 0.25 + (2.35 / 2.0 - 0.25) * (distance / 14.0).coerceIn(0.0,1.0)
    // A stable takeoff altitude deliberately avoids querying terrain beneath the player.
    fun flightDrain(heightAboveTakeoff: Double) = 2.0.pow(heightAboveTakeoff.coerceAtLeast(0.0) / 10.0).coerceAtMost(16.0) / 400.0
    fun absorption(current: Double, damage: Double, cap: Double = 6.0) = (current + damage.coerceAtLeast(0.0) * 0.3).coerceAtMost(cap)
}
