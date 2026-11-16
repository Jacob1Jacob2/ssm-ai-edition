package dev.betrix.superSmashMobsBrawl.services

import com.github.michaelbull.result.*
import com.github.shynixn.mccoroutine.bukkit.launch
import dev.betrix.superSmashMobsBrawl.Manageable
import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.events.QueuePopEvent
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.minigames.MinigameState
import dev.betrix.superSmashMobsBrawl.minigames.MinigameTeam
import dev.betrix.superSmashMobsBrawl.models.brawlData.FfaMinigameDef
import dev.betrix.superSmashMobsBrawl.models.brawlData.MinigameDef
import dev.betrix.superSmashMobsBrawl.models.brawlData.TeamBasedStocksMinigameDef
import dev.betrix.superSmashMobsBrawl.utils.resultRunCatching
import gg.flyte.twilight.event.event
import gg.flyte.twilight.scheduler.repeatingTask
import java.util.UUID
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

sealed class MinigameInitError {
    data class PlayerAlreadyInMinigame(val players: List<Player>) : MinigameInitError()
}

enum class MinigameLeaveError {
    PlayerNotInMinigame,
    NotAllowedToLeave,
    HubNotReady,
    Unknown,
}

class MinigameService : Manageable(), KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val dataService: DataService by inject()
    private val hubService: HubService by inject()

    private val inFlightMinigames = arrayListOf<BrawlMinigame>()

    /** Games that are set up or running (SSM AI Edition: used by the sidebar scoreboard). */
    fun activeMinigames(): List<BrawlMinigame> = inFlightMinigames.toList()

    fun capacityRejection(requested: Int): String? = MatchCapacity(
        plugin.config.getInt("hosting.max-active-matches", 0),
        plugin.config.getInt("hosting.max-total-fighters", 0),
    ).rejection(
        inFlightMinigames.filter { it.getState() != MinigameState.ENDED }.map { it.allPlayers().size },
        requested,
    )

    init {
        // Cleanup ended minigames periodically to prevent memory leaks
        runnables.add(
            repeatingTask(100) { // Every 5 seconds
                cleanupEndedMinigames()
            }
        )

        event<QueuePopEvent> {
            val minigameDef = dataService.getMinigame(minigameId) ?: return@event

            // Every entry point (Play, queue and operator bot games) shares this check.
            val rejection = if (players.any { isPlayerInMinigame(it) })
                "Someone in this group is already playing. Please try again."
            else if (lobbyId !in LobbyRouting.ids || LobbyService.busy(lobbyId)) "This lobby already has a game. Wait or change lobbies."
            else if (players.isEmpty() || players.any { !it.isOnline || LobbyService.lobbyOf(it) != lobbyId }) "The group changed before the game could start. Please try again."
            else capacityRejection(players.size)
            if (rejection != null) {
                players.forEach { player ->
                    QueueService.removePlayer(player)
                    if (dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(player)) {
                        if (!isPlayerInMinigame(player)) dev.betrix.superSmashMobsBrawl.bots.BotManager.despawn(player)
                    } else player.sendMessage(net.kyori.adventure.text.Component.text(rejection, net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                }
                return@event
            }

            val gameId = UUID.randomUUID().toString()

            val teams =
                when (minigameDef) {
                    is TeamBasedStocksMinigameDef -> {
                        val playersPerTeam = minigameDef.playersPerTeam
                        val amountOfTeams = minigameDef.amountOfTeams

                        players.chunked(playersPerTeam).take(amountOfTeams).mapIndexed { idx, chunk
                            ->
                            MinigameTeam(chunk, name = "Team ${idx + 1}")
                        }
                    }

                    is FfaMinigameDef -> {
                        // In FFA, each player is their own team
                        players.map { player -> MinigameTeam(listOf(player), name = player.name) }
                    }
                }

            val actualHost = host ?: players.firstOrNull { !dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(it) }?.uniqueId
            val hostPlayer = players.firstOrNull { it.uniqueId == actualHost }
            val minigameInstance = BrawlMinigame(minigameDef, teams, setup?.copy() ?: hostPlayer?.let { GameSetupService.setupOf(it).copy() } ?: GameSetupService.Setup(), actualHost, lobbyId)
            // Reserve the participants before the coroutine can suspend for world loading.
            check(LobbyService.reserve(minigameInstance))
            inFlightMinigames.add(minigameInstance)
            minigameInstance.connectionManager.setup()
            plugin.launch { handleMinigameSetup(minigameInstance, gameId) }
        }
    }

    fun getMinigameData(id: String): MinigameDef? {
        return dataService.getMinigame(id)
    }

    fun getAllMinigameData(): List<MinigameDef> {
        return dataService.getAllMinigames()
    }

    fun findClosestMinigameById(id: String): MinigameDef? {
        if (id.isBlank()) return null

        getMinigameData(id)?.let {
            return it
        }

        getAllMinigameData()
            .find { it.id.equals(id, ignoreCase = true) }
            ?.let {
                return it
            }

        return getAllMinigameData().find { it.id.contains(id, ignoreCase = true) }
    }

    private suspend fun handleMinigameSetup(minigameInstance: BrawlMinigame, gameId: String) {
        val minigameSetupResult = resultRunCatching {
            minigameInstance.setup(gameId)
            minigameInstance.startGame()
        }
        minigameSetupResult
            .onSuccess { plugin.logger.info("Started minigame ${minigameInstance.minigameDef.id}") }
            .onFailure { err ->
                plugin.logger.severe("Failed to setup minigame: $err")
                if (minigameInstance.getState() != MinigameState.ENDED) {
                    // Clear match kits before rebuilding the hub hotbar, and never reclaim a
                    // departed/reconnected player from another lobby after a late IO failure.
                    minigameInstance.endMinigame("setup_failed")
                    minigameInstance.allPlayers().forEach { player ->
                        if (player.isOnline && !minigameInstance.connectionManager.isDisconnected(player)) {
                            hubService.teleportToDefaultHub(player.player!!)
                            HotbarService.applyPreset(player.player!!, dev.betrix.superSmashMobsBrawl.hotbar.HubHotbarPreset)
                        }
                    }
                }
                LobbyService.release(minigameInstance)
                inFlightMinigames.remove(minigameInstance)
            }
    }

    fun removeMinigameInstance(minigameInstance: BrawlMinigame): Boolean {
        LobbyService.release(minigameInstance)
        return inFlightMinigames.remove(minigameInstance)
    }

    fun getMinigameForPlayer(player: Player): BrawlMinigame? {
        return inFlightMinigames.find { minigame ->
            minigame.getState() != MinigameState.ENDED &&
                minigame.allPlayers().any { it.uniqueId == player.uniqueId } &&
                !minigame.connectionManager.isDisconnected(player)
        }
    }

    private fun cleanupEndedMinigames() {
        val endedMinigames = inFlightMinigames.filter { it.getState() == MinigameState.ENDED }
        if (endedMinigames.isNotEmpty()) {
            plugin.logger.info("Cleaning up ${endedMinigames.size} ended minigames")
            inFlightMinigames.removeAll(endedMinigames)
        }
    }

    fun isPlayerInMinigame(player: Player): Boolean {
        return getMinigameForPlayer(player) != null
    }

    fun leaveMinigame(player: Player): Result<Unit, MinigameLeaveError> {
        val minigame =
            getMinigameForPlayer(player) ?: return Err(MinigameLeaveError.PlayerNotInMinigame)

        if (!minigame.connectionManager.canPlayerLeaveMinigame(player)) {
            return Err(MinigameLeaveError.NotAllowedToLeave)
        }

        hubService.teleportToDefaultHub(player).onFailure {
            return Err(MinigameLeaveError.HubNotReady)
        }

        minigame.connectionManager.handlePlayerLeave(player)
        LobbyService.waiting(player)
        // Match teardown must finish before restoring equipment in the lobby.
        if (HubService.isPlayerInHub(player)) HotbarService.applyPreset(player, dev.betrix.superSmashMobsBrawl.hotbar.HubHotbarPreset)

        return Ok(Unit)
    }
}
