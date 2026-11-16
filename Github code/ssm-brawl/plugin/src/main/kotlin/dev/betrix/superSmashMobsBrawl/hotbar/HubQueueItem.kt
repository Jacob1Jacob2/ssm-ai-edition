package dev.betrix.superSmashMobsBrawl.hotbar

import dev.betrix.superSmashMobsBrawl.services.GameSetupService
import org.bukkit.Material
import org.bukkit.entity.Player

/** Start the match with the options chosen using the settings clock. */
class HubQueueItem(player: Player) :
    HotbarItem(player, "hub_queue_items", Material.CLOCK, 8) {

    override fun onClick() {
        GameSetupService.openMenu(player)
    }

    override fun getDisplayName(): String {
        return "messages.hub.hotbar.settings.title"
    }

    override fun getLore(): List<String> {
        return listOf("messages.hub.hotbar.settings.description")
    }
}
