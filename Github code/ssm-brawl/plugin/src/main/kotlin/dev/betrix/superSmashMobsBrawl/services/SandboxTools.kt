package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.gui.BrawlGui.Companion.openInventory
import dev.betrix.superSmashMobsBrawl.gui.brawlGui
import dev.betrix.superSmashMobsBrawl.items.ItemMenus
import dev.betrix.superSmashMobsBrawl.minigames.MinigameState
import dev.betrix.superSmashMobsBrawl.smash.SmashService
import dev.betrix.superSmashMobsBrawl.smash.SmashTier
import gg.flyte.twilight.event.event
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The sandbox: a match with nobody in it but the player (No Players in Game Settings), to try
 * out items and smashes. Nothing is won or lost there and it lasts until the player leaves.
 *
 * Three tools sit at the right of the hotbar for as long as it does: a chest that spawns any
 * item, an ender chest that drops a Smash Crystal of any kind (or hands its smash over), and a
 * redstone block that goes back to the hub.
 */
object SandboxTools : KoinComponent {
    private val games: MinigameService by inject()
    private val key = NamespacedKey("ssm", "sandbox_tool")
    /** The tick of a player's last click on a tool: one click on a block arrives more than once. */
    private val lastUse = HashMap<UUID, Int>()

    enum class Tool(val material: Material, val label: String, val color: NamedTextColor, val hint: String) {
        ITEMS(Material.CHEST, "Spawn Items", NamedTextColor.YELLOW, "Right-click to spawn any item"),
        CRYSTALS(Material.ENDER_CHEST, "Spawn Smash Crystals", NamedTextColor.LIGHT_PURPLE, "Right-click to drop any Smash Crystal"),
        LEAVE(Material.REDSTONE_BLOCK, "Return to Hub", NamedTextColor.RED, "Right-click to leave the sandbox"),
    }

    private fun plain(text: String, color: NamedTextColor) = Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    /** "Sandbox> ..." in the style of the game's other notices. */
    fun notice(text: String): Component = Component.text("Sandbox> ", NamedTextColor.BLUE).append(Component.text(text, NamedTextColor.GRAY))

    fun toolOf(item: ItemStack?): Tool? =
        item?.itemMeta?.persistentDataContainer?.get(key, PersistentDataType.STRING)?.let { id -> Tool.entries.find { it.name == id } }

    private fun stack(tool: Tool) = ItemStack.of(tool.material).apply {
        editMeta {
            it.displayName(plain(tool.label, tool.color))
            it.lore(listOf(plain(tool.hint, NamedTextColor.GRAY)))
            it.persistentDataContainer.set(key, PersistentDataType.STRING, tool.name)
        }
    }

    /**
     * Puts the three tools into the free hotbar slots furthest to the right, in their order.
     * Called whenever a kit is put on (the start of the match and every respawn), after the kit's
     * own things are in place. Does nothing outside the sandbox.
     */
    fun equip(player: Player) {
        if (games.getMinigameForPlayer(player)?.sandbox != true) return
        remove(player)
        val free = (8 downTo 0).filter { slot -> player.inventory.getItem(slot).let { it == null || it.type.isAir } }
            .take(Tool.entries.size).sorted()
        Tool.entries.zip(free).forEach { (tool, slot) -> player.inventory.setItem(slot, stack(tool)) }
    }

    fun remove(player: Player) {
        for (slot in 0 until player.inventory.size) if (toolOf(player.inventory.getItem(slot)) != null) player.inventory.setItem(slot, null)
    }

    fun setup() {
        event<PlayerInteractEvent>(priority = EventPriority.LOWEST) {
            val tool = toolOf(item) ?: return@event
            // A tool is never placed, and never opens the block it is pointed at.
            isCancelled = true
            setUseInteractedBlock(Event.Result.DENY)
            setUseItemInHand(Event.Result.DENY)
            if (hand != EquipmentSlot.HAND || !action.isRightClick) return@event
            val game = games.getMinigameForPlayer(player)
            if (game?.sandbox != true) { remove(player); return@event }
            if (game.getState() != MinigameState.IN_PROGRESS) return@event
            val now = Bukkit.getCurrentTick()
            if (now - (lastUse[player.uniqueId] ?: -100) < 4) return@event
            lastUse[player.uniqueId] = now
            when (tool) {
                Tool.ITEMS -> ItemMenus.spawner(player)
                Tool.CRYSTALS -> crystals(player)
                Tool.LEAVE -> player.performCommand("hub")
            }
        }
        event<PlayerQuitEvent> { lastUse.remove(player.uniqueId) }
    }

    /** The four kinds of Smash Crystal, each showing what it gives the kit the player has on. */
    private fun crystals(player: Player) {
        val kit = KitService.getKitForPlayer(player)?.id ?: KitService.currentSelectedKitForPlayer(player).id
        player.openInventory(brawlGui(Component.text("Spawn Smash Crystals"), 27) {
            onClick { isCancelled = true }
            for (slot in 0..26) set(slot, GuideMenus.item(Material.GRAY_STAINED_GLASS_PANE, ""))
            SmashTier.entries.forEachIndexed { index, tier ->
                val icon = GuideMenus.smashItem(kit, tier).apply {
                    editMeta { meta ->
                        meta.lore((meta.lore() ?: emptyList()) + listOf(Component.empty(),
                            plain("Left-click: drop it into the arena", NamedTextColor.YELLOW),
                            plain("Right-click: take its smash now", NamedTextColor.YELLOW)))
                    }
                }
                set(10 + index * 2, icon) {
                    player.closeInventory()
                    if (isRightClick) {
                        if (SmashService.giveTestStar(player, tier)) player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, .65f, 1f)
                        else player.sendMessage(notice("Use up the smash you have first."))
                    } else {
                        val problem = SmashService.spawnCrystal(player, tier)
                        player.sendMessage(notice(problem.ifEmpty { "A ${tier.title} is on its way down." }))
                    }
                }
            }
            set(22, GuideMenus.item(Material.REDSTONE_BLOCK, "Close", color = NamedTextColor.RED)) { player.closeInventory() }
        })
    }
}
