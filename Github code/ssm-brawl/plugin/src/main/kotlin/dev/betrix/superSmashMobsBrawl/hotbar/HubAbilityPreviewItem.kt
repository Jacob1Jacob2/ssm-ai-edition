package dev.betrix.superSmashMobsBrawl.hotbar

import dev.betrix.superSmashMobsBrawl.models.brawlData.AbilityDef
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player

/** Lobby weapon previews do not install combat listeners or carry an abilityId. */
class HubAbilityPreviewItem(player: Player, private val ability: AbilityDef, slot: Int) :
    HotbarItem(player, "hub_preview_${ability.id}", Material.matchMaterial(ability.hotbarItem) ?: Material.IRON_AXE, slot) {
    override fun getDisplayName() = "abilities.${ability.id}.name"
    override fun getLore() = listOf("abilities.${ability.id}.description")
    override fun setup() {
        super.setup()
        val meta = itemStack.itemMeta
        meta.displayName(lang.t(getDisplayName()).color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
        meta.lore(listOf(
            lang.t(getLore().first()).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Available when the match begins", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
        ))
        itemStack.itemMeta = meta
        player.inventory.setItem(slot, itemStack)
    }
    override fun onClick() {
        player.sendMessage(Component.text("Kit> ", NamedTextColor.BLUE)
            .append(Component.text("Your kit abilities activate when the match begins.", NamedTextColor.GRAY)))
    }
}
