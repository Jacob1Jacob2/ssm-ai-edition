package dev.betrix.superSmashMobsBrawl.services

import gg.flyte.twilight.event.event
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

/** The familiar yellow name and gray separator, without vanilla angle brackets. */
object ClassicChatService {
    private val ownerId = java.util.UUID.fromString("784cabf9-a8a2-4d39-a381-e716baaf5cbe")
    /** The colour of [player]'s side while they are in a match of two teams, and null everywhere else. */
    fun teamColor(player: java.util.UUID): NamedTextColor? = dev.betrix.superSmashMobsBrawl.minigames.TeamRoster.sideOf(player)?.color

    /**
     * Level, rank and name as chat shows them. In a match of two teams the name is in the colour of
     * the fighter's side, so a line in chat says at a glance which team is talking; the level and
     * the rank keep their own colours.
     */
    fun identity(source: org.bukkit.entity.Player): Component {
        val team = teamColor(source.uniqueId)
        if (source.uniqueId == ownerId) return Component.text("100 ", NamedTextColor.RED)
            .append(Component.text("GOD", NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
            .append(Component.text(" ${source.name}", team ?: NamedTextColor.YELLOW))
        return dev.betrix.superSmashMobsBrawl.bots.BotManager.profileOf(source)?.let {
            dev.betrix.superSmashMobsBrawl.bots.BotIdentity.name(it, team)
        } ?: if (team != null) Component.text(source.name, team) else source.displayName().colorIfAbsent(NamedTextColor.YELLOW)
    }

    /** The rank [player] has: a bot's own, the owner's, and "none" for everybody else. */
    fun rankOf(player: org.bukkit.entity.Player): String =
        dev.betrix.superSmashMobsBrawl.bots.BotManager.profileOf(player)?.rank ?: if (player.uniqueId == ownerId) "god" else "none"

    /**
     * Puts a real player's rank before their name in the player list (Tab). A bot's line is set
     * where the bot is made ([dev.betrix.superSmashMobsBrawl.bots.BotManager]); somebody with no
     * rank keeps the line the game gives them.
     */
    fun listRank(player: org.bukkit.entity.Player) {
        if (dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(player)) return
        val rank = rankOf(player)
        if (dev.betrix.superSmashMobsBrawl.bots.BotIdentity.rankTag(rank) == null) return
        player.playerListName(dev.betrix.superSmashMobsBrawl.bots.BotIdentity.listName(rank, Component.text(player.name)))
    }

    /**
     * The name shown over a fighter's head, as the legacy text a disguise takes: their side's
     * colour in a match of two teams, and otherwise the name alone (a bot's in yellow). The rank
     * the player list shows beside it is not part of it.
     */
    fun nameTag(player: org.bukkit.entity.Player): String =
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(
            teamColor(player.uniqueId)?.let { Component.text(dev.betrix.superSmashMobsBrawl.bots.BotManager.displayNameOf(player), it) }
                ?: dev.betrix.superSmashMobsBrawl.bots.BotManager.profileOf(player)?.let { dev.betrix.superSmashMobsBrawl.bots.BotIdentity.worldName(it) }
                ?: Component.text(player.name))
    fun message(source: org.bukkit.entity.Player, body: Component): Component =
        Component.empty()
            .append(identity(source))
            .append(Component.text(" ", NamedTextColor.GRAY))
            .append(body.colorIfAbsent(NamedTextColor.WHITE))
            .decoration(TextDecoration.ITALIC, false)

    fun setup() {
        event<org.bukkit.event.player.PlayerJoinEvent> { listRank(player) }
        org.bukkit.Bukkit.getOnlinePlayers().forEach(::listRank)
        event<AsyncChatEvent> {
            val sender = player.uniqueId
            viewers().removeIf { it is org.bukkit.entity.Player && !LobbyService.sameChannel(sender, it.uniqueId) }
            renderer { source, _, body, _ -> message(source, body) }
        }
    }
}
