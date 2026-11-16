package dev.betrix.superSmashMobsBrawl.hotbar

import org.bukkit.entity.Player

interface HotbarPreset {
    fun apply(player: Player): List<HotbarItem>
}

object HubHotbarPreset : HotbarPreset {
    override fun apply(player: Player): List<HotbarItem> {
        player.inventory.clear()
        if (!dev.betrix.superSmashMobsBrawl.services.KitService.isRandomSelected(player)) {
        dev.betrix.superSmashMobsBrawl.services.KitPresentation.equipPreview(player,
            dev.betrix.superSmashMobsBrawl.services.KitService.currentSelectedKitForPlayer(player))
        dev.betrix.superSmashMobsBrawl.services.MatchCompass.equip(player,
            dev.betrix.superSmashMobsBrawl.services.KitService.currentSelectedKitForPlayer(player))
        }
        return listOf(HubQueueItem(player))
    }
}

