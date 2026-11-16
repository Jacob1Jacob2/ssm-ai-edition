package dev.betrix.superSmashMobsBrawl.hotbar

import dev.betrix.superSmashMobsBrawl.services.KitService
import org.bukkit.Material
import org.bukkit.entity.Player

/** SSM AI Edition: hub item that opens the kit selector (same menu as /kit). */
class HubKitItem(player: Player) : HotbarItem(player, "hub_kit_selector",
    KitService.currentSelectedKitForPlayer(player).displayItem ?: Material.ARMOR_STAND, 3) {
    override fun onClick() {
        KitService.openKitSelectionGui(player)
    }

    override fun setup() {
        super.setup()
        val meta = itemStack.itemMeta
        meta.displayName(lang.t("gui.kitSelection.kitName") {
            "kitId" to KitService.currentSelectedKitForPlayer(player).id
        })
        itemStack.itemMeta = meta
        player.inventory.setItem(slot, itemStack)
    }

    override fun getDisplayName(): String = "messages.hub.hotbar.kitSelector.title"

    override fun getLore(): List<String> = listOf("messages.hub.hotbar.kitSelector.description")
}
