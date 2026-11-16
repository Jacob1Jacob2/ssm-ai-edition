package dev.betrix.superSmashMobsBrawl.smash

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Material

enum class SmashTier(val title: String, val weight: Int, val descentSeconds: Int,
    val fireworksPerSecond: Int, val color: NamedTextColor, val rgb: Color, val glass: Material) {
    LIGHT("Light Smash Crystal", 40, 8, 3, NamedTextColor.AQUA, Color.fromRGB(85, 210, 255), Material.LIGHT_BLUE_STAINED_GLASS),
    REGULAR("Regular Smash Crystal", 30, 10, 6, NamedTextColor.GREEN, Color.fromRGB(70, 245, 90), Material.LIME_STAINED_GLASS),
    SUPER("Super Smash Crystal", 20, 14, 9, NamedTextColor.YELLOW, Color.fromRGB(255, 235, 30), Material.YELLOW_STAINED_GLASS),
    ULTRA("Ultra Smash Crystal", 10, 20, 12, NamedTextColor.RED, Color.fromRGB(255, 40, 40), Material.RED_STAINED_GLASS);

    val landingTicks get() = descentSeconds * 20
    /** Integer accumulation distributes exact counts even when the rate does not divide 20. */
    fun fireworksAt(tick: Int): Int = if (tick in 1..landingTicks)
        tick * fireworksPerSecond / 20 - (tick - 1) * fireworksPerSecond / 20 else 0
    fun descentHeight(start: Double, tick: Int): Double =
        start + (.35 - start) * (tick.toDouble() / landingTicks).coerceIn(0.0, 1.0)

    companion object {
        fun fromRoll(roll: Int): SmashTier {
            require(roll in 0..99)
            return when (roll) { in 0..39 -> LIGHT; in 40..69 -> REGULAR; in 70..89 -> SUPER; else -> ULTRA }
        }
        fun random() = fromRoll(kotlin.random.Random.nextInt(100))
        fun random(enabled:Set<SmashTier>):SmashTier? {
            val choices=entries.filter {it in enabled}
            if(choices.isEmpty())return null
            var roll=kotlin.random.Random.nextInt(choices.sumOf {it.weight})
            return choices.first {roll-=it.weight;roll<0}
        }
    }
}
