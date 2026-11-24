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
    /**
     * The level the bot called [name] has when it plays at [difficulty]: the same every time it
     * turns up, as a player's is ([BotCharacter]). It used to be rolled afresh at every match.
     */
    fun levelOf(name: String, difficulty: BotDifficulty): Int = levels(difficulty).let { range ->
        range.first + (BotCharacter.of(name).standing * (range.last - range.first + 1)).toInt().coerceIn(0, range.last - range.first)
    }
    fun levelColor(level: Int): NamedTextColor = when {
        level >= 80 -> NamedTextColor.RED
        level >= 70 -> NamedTextColor.GOLD
        level >= 60 -> NamedTextColor.YELLOW
        level >= 50 -> NamedTextColor.GREEN
        else -> NamedTextColor.BLUE
    }
    fun rankColor(rank: String): NamedTextColor = when (rank.lowercase()) {
        "elite" -> NamedTextColor.AQUA
        "mythic", "mythical" -> NamedTextColor.GREEN
        "celestial" -> NamedTextColor.RED
        "mod" -> NamedTextColor.GOLD
        "divine" -> NamedTextColor.LIGHT_PURPLE
        "god" -> NamedTextColor.WHITE
        else -> NamedTextColor.YELLOW
    }
    /** The name over a bot's head: classic yellow, with no rank. */
    fun worldName(profile: BotManager.BotProfile): Component = Component.text(profile.name, NamedTextColor.YELLOW)

    /** A rank as it is written before a name: bold capitals in the rank's colour. Null for no rank. */
    fun rankTag(rank: String): Component? {
        val shown = rank.trim().lowercase().let { if (it == "mythic") "mythical" else it }
        if (shown == "none" || shown.isEmpty()) return null
        return Component.text(shown.uppercase(), rankColor(shown)).decorate(TextDecoration.BOLD)
    }

    /**
     * A line of the player list (Tab): the rank of whoever has one, and then [name] as it stood
     * there before. (Side by side under an empty parent, so the name does not come out bold.)
     */
    fun listName(rank: String, name: Component): Component =
        rankTag(rank)?.let { Component.empty().append(it).append(Component.space()).append(name) } ?: name

    /** A bot's line in the player list. */
    fun listName(profile: BotManager.BotProfile): Component = listName(profile.rank, worldName(profile))
    fun rosterName(profile: BotManager.BotProfile): Component {
        val rank = if (profile.rank == "mythic") "mythical" else profile.rank
        val color = rankColor(rank)
        val prefix = if (rank == "none") Component.empty() else Component.text(rank.uppercase(), color)
            .decorate(TextDecoration.BOLD).append(Component.space())
        return prefix.append(Component.text(profile.name, color)).decoration(TextDecoration.ITALIC, false)
    }
    /**
     * Level, rank and name as chat shows them. [nameColor] is the colour of the bot's side in a
     * match of two teams: the name takes it, the level and the rank keep their own.
     */
    fun name(profile: BotManager.BotProfile, nameColor: NamedTextColor? = null): Component {
        val rank = if (profile.rank == "mythic") "mythical" else profile.rank
        val color = rankColor(rank)
        var result = Component.text("${profile.level} ", levelColor(profile.level))
        if (rank != "none") result = result.append(Component.text(rank.uppercase(), color)
            .decorate(TextDecoration.BOLD)).append(Component.space())
        return result.append(Component.text(profile.name, nameColor ?: color))
    }
}
