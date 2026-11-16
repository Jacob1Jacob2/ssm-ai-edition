package dev.betrix.superSmashMobsBrawl.utils

/** Classic 1.8 armor: each displayed armor icon is two points, reducing damage by 8%. */
object ClassicCombat {
    fun damageAfterArmor(raw: Double, armorIcons: Double): Double =
        raw.coerceAtLeast(0.0) * (1.0 - armorIcons.coerceIn(0.0, 10.0) * 0.08)

    /** Weight belongs to the victim; losing health makes that victim easier to launch. */
    fun knockbackScale(weight: Double, health: Double, maxHealth: Double = 20.0): Double =
        weight.coerceAtLeast(0.0) * (1.0 + 0.1 * (maxHealth - health).coerceIn(0.0, maxHealth))
}
