package dev.betrix.superSmashMobsBrawl.items

import dev.betrix.superSmashMobsBrawl.gui.brawlGui
import dev.betrix.superSmashMobsBrawl.gui.BrawlGui.Companion.openInventory
import dev.betrix.superSmashMobsBrawl.services.GameSetupService
import dev.betrix.superSmashMobsBrawl.services.GuideMenus
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor.*
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

object ItemMenus {
    private fun wrap(value: String): List<Component> {
        val lines = mutableListOf<String>(); var line = ""
        value.split(' ').forEach { word ->
            if (line.length + word.length > 42) { lines += line; line = "" }
            line += (if (line.isEmpty()) "" else " ") + word
        }
        if (line.isNotEmpty()) lines += line
        return lines.map { Component.text(it, GRAY).decoration(TextDecoration.ITALIC, false) }
    }
    fun open(player: Player, select: Boolean = false) {
        val settings = GameSetupService.setupOf(player)
        val items = ArenaItem.entries.sortedBy { it.rarity.ordinal }
        player.openInventory(brawlGui(Component.text(if (select) "Choose Items" else "Carl's Item Guide"), 54, selectionEffects=select) {
            onClick { isCancelled = true }
            for (slot in 0..53) set(slot, GuideMenus.item(Material.GRAY_STAINED_GLASS_PANE, ""))
            set(4, GuideMenus.item(Material.CREEPER_HEAD, if (select) "${settings.enabledItems.size}/${ArenaItem.entries.size} items enabled" else "Carl's Item Guide",
                color = GREEN).apply { editMeta {
                    it.lore(listOf(Component.text("Rarity chances:", GRAY).decoration(TextDecoration.ITALIC, false)) +
                        ItemRarity.entries.map { rarity ->
                            Component.text("${rarity.label} ${rarity.weight}%", rarity.color).decoration(TextDecoration.ITALIC, false)
                        })
                } })
            items.forEachIndexed { index, kind ->
                val enabled = kind in settings.enabledItems
                val icon = ItemStack.of(ItemPresentation.material(kind,player)).apply { editMeta {
                    it.displayName(Component.text(kind.label, kind.rarity.color).decoration(TextDecoration.ITALIC, false))
                    it.lore(listOf(Component.text("${kind.rarity.label} · ${kind.rarity.weight}% rarity chance · ${kind.uses} ${kind.useLabel}", kind.rarity.color)
                        .decoration(TextDecoration.ITALIC, false)) + wrap(kind.description) +
                        if (select) listOf(Component.text(if (enabled) "Enabled — click to disable" else "Disabled — click to enable", if (enabled) GREEN else RED)
                            .decoration(TextDecoration.ITALIC, false)) else emptyList())
                    it.setEnchantmentGlintOverride(kind == ArenaItem.SHOTBOW || (select && enabled))
                } }
                ItemPresentation.plainTooltip(icon)
                // Intrinsic Shotbow glint is independent of the menu selection.
                dev.betrix.superSmashMobsBrawl.services.SelectionIcons.mark(icon, select && enabled)
                set(9 + index, icon) {
                    if (select) {
                        settings.enabledItems = if (enabled) settings.enabledItems - kind else settings.enabledItems + kind
                        GameSetupService.save(); click(player); open(player, true)
                    }
                }
            }
            if (select) {
                set(45, GuideMenus.item(if (settings.enabledItems.size == ArenaItem.entries.size) Material.LIME_DYE else Material.GRAY_DYE, "Enable All", color = GREEN)) {
                    settings.enabledItems = ArenaItem.entries.toSet(); GameSetupService.save(); click(player); open(player, true)
                }
                set(46, GuideMenus.item(if (settings.enabledItems.isEmpty()) Material.RED_DYE else Material.GRAY_DYE, "Disable All", color = RED)) {
                    settings.enabledItems = emptySet(); GameSetupService.save(); click(player); open(player, true)
                }
            }
            set(if (select) 53 else 49, GuideMenus.item(Material.REDSTONE_BLOCK, if (select) "Back to Settings" else "Close", color = RED)) {
                if (select) GameSetupService.openMenu(player) else player.closeInventory()
            }

        })
        val inventory=player.openInventory.topInventory
        var frame=0
        object: org.bukkit.scheduler.BukkitRunnable() {
            override fun run() {
                if (!player.isOnline || player.openInventory.topInventory !== inventory) { cancel(); return }
                val slot=9+items.indexOf(ArenaItem.FOOD)
                inventory.getItem(slot)?.let { it.type=ItemPresentation.foods[frame++ % ItemPresentation.foods.size]; inventory.setItem(slot,it) }
            }
        }.runTaskTimer(ItemService.plugin,20L,20L)
    }
    /** The sandbox's item chest: every item there is, and a click puts it into the arena in front of the player. */
    fun spawner(player: Player) {
        val items = ArenaItem.entries.sortedBy { it.rarity.ordinal }
        player.openInventory(brawlGui(Component.text("Spawn Items"), 54) {
            onClick { isCancelled = true }
            for (slot in 0..53) set(slot, GuideMenus.item(Material.GRAY_STAINED_GLASS_PANE, ""))
            items.forEachIndexed { index, kind ->
                val icon = ItemStack.of(ItemPresentation.material(kind, player)).apply { editMeta {
                    it.displayName(Component.text(kind.label, kind.rarity.color).decoration(TextDecoration.ITALIC, false))
                    it.lore(listOf(Component.text("${kind.rarity.label} · ${kind.uses} ${kind.useLabel}", kind.rarity.color)
                        .decoration(TextDecoration.ITALIC, false)) + wrap(kind.description) +
                        listOf(Component.text("Click to spawn it in front of you", YELLOW).decoration(TextDecoration.ITALIC, false)))
                    it.setEnchantmentGlintOverride(kind == ArenaItem.SHOTBOW)
                } }
                ItemPresentation.plainTooltip(icon)
                set(9 + index, icon) {
                    player.closeInventory()
                    player.sendMessage(dev.betrix.superSmashMobsBrawl.services.SandboxTools.notice(ItemService.spawnInFront(player, kind)))
                    click(player)
                }
            }
            set(49, GuideMenus.item(Material.REDSTONE_BLOCK, "Close", color = RED)) { player.closeInventory() }
        })
    }
    private fun click(player: Player) = player.playSound(player.location, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, .5f, 1f)
}
