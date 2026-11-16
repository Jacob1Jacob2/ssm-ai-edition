package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.gui.brawlGui
import dev.betrix.superSmashMobsBrawl.gui.BrawlGui.Companion.openInventory
import dev.betrix.superSmashMobsBrawl.smash.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Read-only guides share the same tier items and limits as the actual Smash powers. */
object GuideMenus : KoinComponent {
    private val lang: LangService by inject()
    val kitOrder = listOf("skeleton", "iron_golem", "spider", "slime", "goat", "squid",
        "creeper", "enderman", "snowman", "wolf", "magma_cube", "witch", "wither_skeleton",
        "zombie", "cow", "horse", "pig", "blaze", "chicken", "guardian", "mooshroom",
        "sheep", "villager", "phantom", "polar_bear")

    private fun text(value: String, color: NamedTextColor = NamedTextColor.GRAY) =
        Component.text(value, color).decoration(TextDecoration.ITALIC, false)

    private fun wrap(value: String): List<Component> {
        val lines = mutableListOf<String>()
        var line = ""
        value.split(' ').forEach { word ->
            if (line.isNotEmpty() && line.length + word.length + 1 > 40) { lines += line; line = "" }
            line = if (line.isEmpty()) word else "$line $word"
        }
        if (line.isNotEmpty()) lines += line
        return lines.map { text(it) }
    }

    fun item(material: Material, name: String, description: String = "", color: NamedTextColor = NamedTextColor.YELLOW) =
        ItemStack.of(material).apply { editMeta { it.displayName(text(name, color)); it.lore(wrap(description)) } }

    fun smashItem(kitId: String, tier: SmashTier): ItemStack {
        val spec = SmashCatalog.specs.firstOrNull { it.kit == kitId && it.tier == tier }
        return SmashItems.star(tier).apply {
            // A guide icon can never be activated as a collected Smash.
            editMeta {
                it.persistentDataContainer.remove(SmashItems.STAR)
                it.displayName(text(tier.title, tier.color))
                it.lore(if (spec == null) listOf(text("Coming Soon", NamedTextColor.DARK_GRAY)) else buildList {
                    add(text(spec.name, tier.color).decorate(TextDecoration.BOLD))
                    addAll(wrap(SmashDescriptions.get(spec)))
                    add(Component.empty())
                    if (spec.uses > 0) add(text("Uses: ${spec.uses}", NamedTextColor.WHITE))
                    if (spec.seconds > 0) add(text("Duration: ${spec.seconds}s", NamedTextColor.WHITE))
                    if (spec.fuelSeconds > 0) add(text("Fuel: ${spec.fuelSeconds}s of active use", NamedTextColor.WHITE))
                    if (spec.kind == SmashKind.TOOL) add(text(if (spec.cooldownTicks == 0) "No recharge delay" else "Keeps the ability's recharge", NamedTextColor.GRAY))
                    if (spec.uses < 0 && spec.seconds < 0 && spec.fuelSeconds < 0 && spec.kind in setOf(SmashKind.TOOL, SmashKind.PASSIVE))
                        add(text("Lasts until this life ends", NamedTextColor.WHITE))
                })
            }
        }
    }

    private fun kitIcon(id: String): ItemStack {
        val def = KitService.getKitData(id)
        val material = def?.displayItem ?: when (id) {
            "goat" -> Material.GOAT_HORN
            "phantom" -> Material.PHANTOM_MEMBRANE
            "mooshroom" -> Material.RED_MUSHROOM
            else -> Material.BOOK
        }
        return MenuIcons.kit(id, material).apply {
            editMeta { it.displayName(lang.t("kits.$id.name").color(lang.kitColor(id)))
                it.lore(listOf(text(if (id in LobbyBuildService.futureKits) "Coming Soon" else "Click for kit stats"))) }
            if (material == Material.GOAT_HORN) unsetData(io.papermc.paper.datacomponent.DataComponentTypes.INSTRUMENT)
            if (id == "mooshroom") editMeta { it.itemModel = org.bukkit.NamespacedKey("ssm", "large_mushroom") }
        }
    }

    fun smash(player: Player, requestedPage: Int = 0) {
        val pages = (kitOrder.size + 5) / 6
        val page = requestedPage.coerceIn(0, pages - 1)
        player.openInventory(brawlGui(text("Peppa's Smash Guide - ${page + 1}/$pages"), 54) {
            onClick { isCancelled = true }
            for (slot in 0..53) set(slot, item(Material.GRAY_STAINED_GLASS_PANE, ""))
            kitOrder.drop(page * 6).take(6).forEachIndexed { row, id ->
                set(row * 9, kitIcon(id)) {
                    if (id !in LobbyBuildService.futureKits) KitService.getKitData(id)?.let { KitService.openKitStats(player, it) }
                }
                SmashTier.entries.forEach { tier -> set(row * 9 + 1 + tier.ordinal, smashItem(id, tier)) }
            }
            set(50, item(Material.REDSTONE_BLOCK, if (page == 0) "Close" else "Previous Page", color = NamedTextColor.RED)) {
                if (page == 0) player.closeInventory() else smash(player, page - 1)
                ding(player)
            }
            if (page < pages - 1) set(53, item(Material.EMERALD_BLOCK, "Next Page", color = NamedTextColor.GREEN)) {
                smash(player, page + 1); ding(player)
            }
        })
    }

    fun items(player: Player) = dev.betrix.superSmashMobsBrawl.items.ItemMenus.open(player)

    fun ding(player: Player) = player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, .3f, 2f)
}
