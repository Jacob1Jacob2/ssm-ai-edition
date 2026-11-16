package dev.betrix.superSmashMobsBrawl.hotbar

import dev.betrix.superSmashMobsBrawl.services.GameSetupService
import org.bukkit.Material
import org.bukkit.entity.Player

class HubSettingsItem(player: Player) : HotbarItem(player, "hub_settings", Material.CLOCK, 7) {
    override fun onClick() = GameSetupService.openMenu(player)
    override fun getDisplayName() = "messages.hub.hotbar.settings.title"
    override fun getLore() = listOf("messages.hub.hotbar.settings.description")
}
