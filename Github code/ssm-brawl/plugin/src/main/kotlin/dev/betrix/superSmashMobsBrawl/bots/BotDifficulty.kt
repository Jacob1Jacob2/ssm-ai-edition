package dev.betrix.superSmashMobsBrawl.bots

/**
 * How well a bot plays. Every number here is something a human player varies in: how fast they
 * react, how accurate their aim is, how fast they click, how often they use abilities, and how
 * reliably they recover after being knocked off the map.
 */
enum class BotDifficulty(
    /** Ticks between decisions (target choice, aim correction, ability choice). 20 ticks = 1s. */
    val reactionTicks: Int,
    /** Random aim error in degrees, re-rolled every decision. */
    val aimErrorDegrees: Double,
    /** Max degrees the bot can turn its head per tick. */
    val turnSpeed: Float,
    /** Average melee clicks per second while in range. */
    val clicksPerSecond: Double,
    /** Chance to use a ready ability when the situation fits. */
    val abilityChance: Double,
    /** Chance per decision to notice it is falling off the map and try to recover. */
    val recoverySkill: Double,
    /** Strafes and jumps around targets instead of walking straight at them. */
    val strafes: Boolean,
    /** Prefers low-health targets and players standing near an edge. */
    val smartTargeting: Boolean,
    /** Chance to walk off an edge by accident while chasing someone. */
    val edgeCarelessness: Double,
) {
    NORMAL(9, 14.0, 14f, 4.5, 0.25, 0.55, false, false, 0.05),
    HARD(6, 9.0, 22f, 6.5, 0.5, 0.75, false, false, 0.02),
    HARDER(4, 5.0, 32f, 8.5, 0.75, 0.9, true, true, 0.02),
    EXPERT(2, 2.5, 45f, 10.5, 0.95, 1.0, true, true, 0.0);

    companion object {
        fun parse(text: String): BotDifficulty =
            entries.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) } ?: NORMAL
    }
}
