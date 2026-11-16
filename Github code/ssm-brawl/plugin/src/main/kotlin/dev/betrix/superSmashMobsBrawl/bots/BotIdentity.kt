package dev.betrix.superSmashMobsBrawl.bots

import kotlin.random.Random
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object BotIdentity {
    fun levels(difficulty: BotDifficulty): IntRange = when (difficulty) {
        BotDifficulty.NORMAL -> 40..55
        BotDifficulty.HARD -> 55..70
        BotDifficulty.HARDER -> 70..85
        BotDifficulty.EXPERT -> 85..100
    }
    fun rollLevel(difficulty: BotDifficulty) = levels(difficulty).random()
    fun levelColor(level: Int): NamedTextColor = when {
        level >= 90 -> NamedTextColor.DARK_RED
        level >= 80 -> NamedTextColor.RED
        level >= 70 -> NamedTextColor.GOLD
        level >= 60 -> NamedTextColor.YELLOW
        level >= 50 -> NamedTextColor.GREEN
        else -> NamedTextColor.BLUE
    }
    fun rankColor(rank: String): NamedTextColor = when (rank.lowercase()) {
        "elite" -> NamedTextColor.AQUA
        "mythic", "mythical" -> NamedTextColor.GREEN
        "celestial", "mod" -> NamedTextColor.RED
        "divine" -> NamedTextColor.LIGHT_PURPLE
        "god" -> NamedTextColor.WHITE
        else -> NamedTextColor.YELLOW
    }
    /** Rank colors belong to chat and roster labels; world/tab names stay classic yellow. */
    fun worldName(profile: BotManager.BotProfile): Component = Component.text(profile.name, NamedTextColor.YELLOW)
    fun rosterName(profile: BotManager.BotProfile): Component {
        val rank = if (profile.rank == "mythic") "mythical" else profile.rank
        val color = rankColor(rank)
        val prefix = if (rank == "none") Component.empty() else Component.text(rank.uppercase(), color)
            .decorate(TextDecoration.BOLD).append(Component.space())
        return prefix.append(Component.text(profile.name, color)).decoration(TextDecoration.ITALIC, false)
    }
    fun name(profile: BotManager.BotProfile): Component {
        val rank = if (profile.rank == "mythic") "mythical" else profile.rank
        val color = rankColor(rank)
        var result = Component.text("${profile.level} ", levelColor(profile.level))
        if (rank != "none") result = result.append(Component.text(rank.uppercase(), color)
            .decorate(TextDecoration.BOLD)).append(Component.space())
        return result.append(Component.text(profile.name, color))
    }
}
