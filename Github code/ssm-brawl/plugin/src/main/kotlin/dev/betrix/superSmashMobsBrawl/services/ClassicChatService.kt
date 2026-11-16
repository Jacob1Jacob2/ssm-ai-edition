package dev.betrix.superSmashMobsBrawl.services

import gg.flyte.twilight.event.event
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

/** The familiar yellow name and gray separator, without vanilla angle brackets. */
object ClassicChatService {
    private val ownerId = java.util.UUID.fromString("784cabf9-a8a2-4d39-a381-e716baaf5cbe")
    fun identity(source: org.bukkit.entity.Player): Component {
        if (source.uniqueId == ownerId) return Component.text("100 ", NamedTextColor.DARK_RED)
            .append(Component.text("GOD", NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
            .append(Component.text(" ${source.name}", NamedTextColor.YELLOW))
        return dev.betrix.superSmashMobsBrawl.bots.BotManager.profileOf(source)?.let {
            dev.betrix.superSmashMobsBrawl.bots.BotIdentity.name(it)
        } ?: source.displayName().colorIfAbsent(NamedTextColor.YELLOW)
    }
    fun message(source: org.bukkit.entity.Player, body: Component): Component =
        Component.empty()
            .append(identity(source))
            .append(Component.text(" ", NamedTextColor.GRAY))
            .append(body.colorIfAbsent(NamedTextColor.WHITE))
            .decoration(TextDecoration.ITALIC, false)

    fun setup() {
        event<AsyncChatEvent> {
            val sender = player.uniqueId
            viewers().removeIf { it is org.bukkit.entity.Player && !LobbyService.sameChannel(sender, it.uniqueId) }
            renderer { source, _, body, _ -> message(source, body) }
        }
    }
}
