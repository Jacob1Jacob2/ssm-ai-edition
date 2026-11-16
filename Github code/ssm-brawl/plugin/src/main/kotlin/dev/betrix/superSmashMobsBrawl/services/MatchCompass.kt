package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.models.brawlData.KitDef
import gg.flyte.twilight.event.event
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.CompassMeta
import org.bukkit.persistence.PersistentDataType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** A dimension-aware tracker that follows the nearest living opponent. */
object MatchCompass : KoinComponent {
    private val data: DataService by inject()
    private val games: MinigameService by inject()
    private val plugin: dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl by inject()
    private val key = NamespacedKey("ssm", "player_tracker")
    private fun isTracker(item: ItemStack?) = item?.itemMeta?.persistentDataContainer?.has(key, PersistentDataType.BYTE) == true

    fun equip(player: Player, kit: KitDef) {
        if (!games.isPlayerInMinigame(player) && !HubService.isPlayerInHub(player)) return
        val weaponSlots = kit.abilities.mapNotNull { data.getAbility(it.id)?.itemSlot }.toSet()
        val slot = (2..7).first { it !in weaponSlots && !(kit.id in setOf("skeleton", "zombie") && it == 3) }
        remove(player)
        player.inventory.setItem(slot, ItemStack.of(Material.COMPASS).apply {
            editMeta {
                it.displayName(Component.text("Player Compass", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
                it.persistentDataContainer.set(key, PersistentDataType.BYTE, 1)
            }
        })
        update(player, false)
    }
    fun remove(player: Player) {
        for (slot in 0 until player.inventory.size) if (isTracker(player.inventory.getItem(slot))) player.inventory.setItem(slot, null)
    }
    private fun update(player: Player, announce: Boolean) {
        val game = games.getMinigameForPlayer(player)
        if (game == null) {
            player.inventory.contents.filterNotNull().filter(::isTracker).forEach { item ->
                item.editMeta(CompassMeta::class.java) { meta ->
                    meta.setEnchantmentGlintOverride(false)
                    meta.isLodestoneTracked = false
                    meta.lodestone = player.location
                    meta.lore(listOf(Component.text("Tracks the nearest opponent once the match begins", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)))
                }
            }
            if (announce) player.sendMessage(Component.text("The compass will track opponents during your match.", NamedTextColor.GRAY))
            return
        }
        val target = game.allPlayers().mapNotNull { it.player }.filter {
            it != player && it.world == player.world && it.gameMode == GameMode.SURVIVAL &&
                !game.connectionManager.isDisconnected(it) && !game.respawnManager.isRespawning(it) && !game.arePlayersOnSameTeam(player, it)
        }.minByOrNull { it.location.distanceSquared(player.location) }
        player.inventory.contents.filterNotNull().filter(::isTracker).forEach { item ->
            item.editMeta(CompassMeta::class.java) { meta ->
                meta.setEnchantmentGlintOverride(false)
                    meta.isLodestoneTracked = false
                meta.lodestone = target?.location ?: player.location
                meta.lore(listOf(Component.text(target?.let { "${it.name} — ${player.location.distance(it.location).toInt()} blocks" }
                    ?: "No opponents remaining", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)))
            }
        }
        if (game.getState() == dev.betrix.superSmashMobsBrawl.minigames.MinigameState.IN_PROGRESS &&
            isTracker(player.inventory.itemInMainHand)) {
            fun label(value: String) = Component.text(value, NamedTextColor.WHITE, TextDecoration.BOLD)
            fun value(value: String) = Component.text(value, NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false)
            fun decimal(number: Double) = String.format(java.util.Locale.ROOT, "%.1f", number)
            dev.betrix.superSmashMobsBrawl.services.EnergyHud.message(player, if (target == null) label("Nearest Target: ").append(value("None")) else
                label("Nearest Target: ").append(value(target.name))
                    .append(label("   Distance: ")).append(value(decimal(player.location.distance(target.location))))
                    .append(label("   Height: ")).append(value(decimal(target.location.y - player.location.y))))
        }
        if (announce) player.sendMessage(Component.text(target?.let { "Tracking ${it.name}: ${player.location.distance(it.location).toInt()} blocks" }
            ?: "No opponents remaining", NamedTextColor.YELLOW))
    }
    fun setup() {
        event<PlayerInteractEvent> {
            if (isTracker(item)) { isCancelled = true; if (action.isRightClick) update(player, true) }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            Bukkit.getOnlinePlayers().filter { p -> p.inventory.contents.any(::isTracker) }.forEach { update(it, false) }
        }, 10L, 10L)
    }
}
