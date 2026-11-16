package dev.betrix.superSmashMobsBrawl.hotbar

import dev.betrix.superSmashMobsBrawl.services.BotConversationService
import org.bukkit.Material
import org.bukkit.entity.Player

class BotPersonalityItem(player: Player) : HotbarItem(player, "bot_personality", Material.BOOK, 6) {
    override fun onClick() = BotConversationService.openMenu(player)
    override fun getDisplayName() = "messages.hub.hotbar.personality.title"
    override fun getLore() = listOf("messages.hub.hotbar.personality.description")
}
