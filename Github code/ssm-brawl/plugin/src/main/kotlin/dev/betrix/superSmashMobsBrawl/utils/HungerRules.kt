package dev.betrix.superSmashMobsBrawl.utils

/** The numbers of classic Super Smash Mobs hunger. A hunger bar is two points; a full bar is twenty. */
object HungerRules {
    /** Seconds for one point (half a bar) to go. */
    const val DRAIN_SECONDS = 6.25
    /** Seconds between hits of starvation on an empty bar: a tenth of the time a point lasts. */
    const val STARVE_SECONDS = 0.625
    /** Damage dealt to another player for one point back. */
    const val DAMAGE_PER_POINT = 2.0
    /** Points at or under which a bot counts as hungry enough to stop being careful (three bars: sprinting stops here too). */
    const val HUNGRY = 6

    /** Points one hit of [damage] gives back: one for every [perPoint] damage, and never less than one. */
    fun restored(damage: Double, perPoint: Double = DAMAGE_PER_POINT): Int =
        if (damage <= 0.0 || perPoint <= 0.0) 0 else maxOf(1, (damage / perPoint).toInt())

    /** How long a bar of [points] lasts with nobody hit, in seconds. */
    fun secondsUntilEmpty(points: Int, drainSeconds: Double = DRAIN_SECONDS): Double = points.coerceAtLeast(0) * drainSeconds
}
