package dev.betrix.superSmashMobsBrawl.hotbar

import dev.betrix.superSmashMobsBrawl.services.MapVoteService
import org.bukkit.Material
import org.bukkit.entity.Player

/** SSM AI Edition: hub item that opens the map vote menu. */
class HubMapVoteItem(player: Player) : HotbarItem(player, "hub_map_vote", Material.FILLED_MAP, 6) {
    override fun onClick() {
        MapVoteService.openVoteGui(player)
    }

    override fun getDisplayName(): String = "messages.hub.hotbar.mapVote.title"

    override fun getLore(): List<String> = listOf("messages.hub.hotbar.mapVote.description")
}
