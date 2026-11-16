package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.gui.BrawlGui.Companion.openInventory
import dev.betrix.superSmashMobsBrawl.gui.brawlGui
import dev.betrix.superSmashMobsBrawl.models.BrawlHubWorld
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.bots.BotManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor.*
import org.bukkit.Material
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** All mutations run on the server thread; async chat reads only cached UUID channels. */
object LobbyService : KoinComponent {
    private val games: MinigameService by inject()
    private val membership = ConcurrentHashMap<UUID, Int>()
    private val channels = ConcurrentHashMap<UUID, String>()
    private val hubs = linkedMapOf<Int, BrawlHubWorld>()
    private val reservations = mutableMapOf<Int, BrawlMinigame>()

    fun register(id: Int, hub: BrawlHubWorld) { require(id in LobbyRouting.ids); hubs[id] = hub }
    fun hub(id: Int): BrawlHubWorld? = hubs[id]
    fun allHubs(): List<BrawlHubWorld> = hubs.values.toList()
    fun lobbyOf(player: org.bukkit.OfflinePlayer): Int = membership[player.uniqueId] ?: 1
    fun assign(player: org.bukkit.OfflinePlayer, id: Int) {
        require(id in LobbyRouting.ids)
        membership[player.uniqueId] = id
        channels[player.uniqueId] = "hub:$id"
    }
    fun arrive(player: Player) {
        // A reconnect returns to the player's waiting hub. Departures never auto-rejoin matches.
        assign(player, membership[player.uniqueId] ?: LobbyRouting.arrival(reservations.keys))
    }
    fun forgetBot(player: Player) { membership.remove(player.uniqueId); channels.remove(player.uniqueId) }
    fun waiting(player: Player) { channels[player.uniqueId] = "hub:${lobbyOf(player)}" }
    fun disconnected(player: Player) { channels.remove(player.uniqueId) }
    fun sameChannel(a: UUID, b: UUID): Boolean = channels[a]?.let { it == channels[b] } ?: false
    fun busy(id: Int): Boolean = id in reservations
    fun reserve(game: BrawlMinigame): Boolean {
        if (game.lobbyId !in LobbyRouting.ids || busy(game.lobbyId)) return false
        reservations[game.lobbyId] = game
        game.allPlayers().forEach { channels[it.uniqueId] = "match:${game.displayCode}" }
        return true
    }
    fun release(game: BrawlMinigame) { if (reservations[game.lobbyId] === game) reservations.remove(game.lobbyId) }
    fun changeLobby(player: Player, target: Int): Boolean {
        if (target !in LobbyRouting.ids || hub(target) == null) return false
        if (target == lobbyOf(player) && HubService.isPlayerInHub(player)) return true
        QueueService.removePlayer(player)
        games.getMinigameForPlayer(player)?.let {
            if (games.leaveMinigame(player).isErr) return false
        }
        val previous = lobbyOf(player)
        assign(player, target)
        val result = HubService.teleportToDefaultHub(player)
        if (result.isErr) { assign(player, previous); return false }
        player.sendMessage(Component.text("Joined Lobby $target", GREEN))
        return true
    }
    fun openMenu(player: Player) {
        player.openInventory(brawlGui(Component.text("Change Lobbies"), 27) {
            onClick { isCancelled = true }
            LobbyRouting.ids.forEach { id ->
                val current = lobbyOf(player) == id
                val count = hub(id)?.world?.players?.count { !BotManager.isBot(it) } ?: 0
                set(11 + id - 1, GuideMenus.item(if (current) Material.LIME_DYE else if (busy(id)) Material.ORANGE_DYE else Material.HOPPER,
                    "Lobby $id${if (current) " (Current)" else ""}",
                    "$count waiting · ${if (busy(id)) "Game active — join waiting hub" else "Ready for a game"}", if (current) GREEN else YELLOW)) {
                    player.closeInventory(); changeLobby(player, id)
                }
            }
            set(22, GuideMenus.item(Material.REDSTONE_BLOCK, "Back to Settings", color = RED)) { GameSetupService.openMenu(player) }
        })
    }
    fun teardown() { hubs.clear(); reservations.clear(); channels.clear(); membership.clear() }
}
