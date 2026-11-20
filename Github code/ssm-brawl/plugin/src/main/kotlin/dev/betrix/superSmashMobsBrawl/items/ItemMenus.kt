package dev.betrix.superSmashMobsBrawl.items

import dev.betrix.superSmashMobsBrawl.gui.brawlGui
import dev.betrix.superSmashMobsBrawl.gui.BrawlGui.Companion.openInventory
import dev.betrix.superSmashMobsBrawl.services.GameSetupService
import dev.betrix.superSmashMobsBrawl.services.GuideMenus
import dev.betrix.superSmashMobsBrawl.services.SelectionIcons
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.NamedTextColor.*
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * The three item menus. They are one chest, laid out by [ItemMenuLayout]: a row for each rarity,
 * opened by its concrete block (which says what the rarity is) and a pane of glass, then that
 * rarity's items; the redstone block in the last slot goes back.
 */
object ItemMenus {
    private enum class Mode(val title: String) { GUIDE("Carl's Item Guide"), CHOOSE("Choose Items"), SPAWN("Spawn Items") }

    private fun plain(text: String, color: NamedTextColor) = Component.text(text, color).decoration(TextDecoration.ITALIC, false)
    private fun wrap(kind: ArenaItem): List<Component> = kind.descriptionLines.map { plain(it, GRAY) }

    /** Carl's Item Guide, or with [select] the Choose Items page of Game Settings. */
    fun open(player: Player, select: Boolean = false) = show(player, if (select) Mode.CHOOSE else Mode.GUIDE)
    /** The sandbox's item chest: every item there is, and a click puts it into the arena in front of the player. */
    fun spawner(player: Player) = show(player, Mode.SPAWN)

    private fun show(player: Player, mode: Mode) {
        val settings = GameSetupService.setupOf(player)
        val choosing = mode == Mode.CHOOSE
        fun choose(enabled: Set<ArenaItem>) { settings.enabledItems = enabled; GameSetupService.save(); click(player); show(player, mode) }
        player.openInventory(brawlGui(Component.text(mode.title), ItemMenuLayout.SIZE, selectionEffects = choosing) {
            onClick { isCancelled = true }
            for (rarity in ItemRarity.entries) {
                val row = ItemMenuLayout.inRow(rarity)
                val allOn = settings.enabledItems.containsAll(row)
                // The block is the rarity; hovering over it says which, and how often it turns up.
                val block = ItemStack.of(rarity.block).apply { editMeta {
                    it.displayName(plain(rarity.label, rarity.color))
                    it.lore(listOf(plain("${rarity.weight}% of drops", GRAY)) +
                        if (choosing) listOf(plain(if (allOn) "Click to disable the row" else "Click to enable the row", if (allOn) RED else GREEN)) else emptyList())
                } }
                if (choosing) SelectionIcons.mark(block, allOn)
                set(ItemMenuLayout.blockSlot(rarity), block) {
                    if (choosing) choose(if (allOn) settings.enabledItems - row.toSet() else settings.enabledItems + row)
                }
                // The glass is only there to look good.
                set(ItemMenuLayout.paneSlot(rarity), GuideMenus.item(rarity.pane, ""))
                for (kind in row) {
                    val enabled = kind in settings.enabledItems
                    val icon = ItemStack.of(ItemPresentation.material(kind, player)).apply { editMeta {
                        it.displayName(plain(kind.label, kind.rarity.color))
                        it.lore(listOf(plain("${kind.rarity.label} · ${kind.usesText()}", kind.rarity.color)) + wrap(kind) + when (mode) {
                            Mode.GUIDE -> emptyList()
                            Mode.CHOOSE -> listOf(plain(if (enabled) "Enabled — click to disable" else "Disabled — click to enable", if (enabled) GREEN else RED))
                            Mode.SPAWN -> listOf(plain("Click to spawn it in front of you", YELLOW))
                        })
                        it.setEnchantmentGlintOverride(kind == ArenaItem.SHOTBOW || (choosing && enabled))
                    } }
                    ItemPresentation.plainTooltip(icon)
                    // Intrinsic Shotbow glint is independent of the menu selection.
                    if (choosing) SelectionIcons.mark(icon, enabled)
                    set(ItemMenuLayout.slotOf(kind), icon) {
                        when (mode) {
                            Mode.GUIDE -> {}
                            Mode.CHOOSE -> choose(if (enabled) settings.enabledItems - kind else settings.enabledItems + kind)
                            Mode.SPAWN -> {
                                player.closeInventory()
                                player.sendMessage(dev.betrix.superSmashMobsBrawl.services.SandboxTools.notice(ItemService.spawnInFront(player, kind)))
                                click(player)
                            }
                        }
                    }
                }
            }
            set(ItemMenuLayout.BACK, GuideMenus.item(Material.REDSTONE_BLOCK, "Back", color = RED)) {
                click(player)
                if (choosing) GameSetupService.openMenu(player) else player.closeInventory()
            }
        })
        if (dev.betrix.superSmashMobsBrawl.bots.BotManager.debug)
            ItemService.plugin.logger.info("[menu] ${player.name}: ${mode.title} open, ${settings.enabledItems.size}/${ArenaItem.entries.size} items enabled")
    }
    private fun click(player: Player) = player.playSound(player.location, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, .5f, 1f)
}
