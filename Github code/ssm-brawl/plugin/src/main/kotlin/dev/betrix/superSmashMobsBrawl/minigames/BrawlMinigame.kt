package dev.betrix.superSmashMobsBrawl.minigames

import com.github.michaelbull.result.onFailure

import com.github.shynixn.mccoroutine.bukkit.launch
import dev.betrix.superSmashMobsBrawl.Manageable
import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.events.MinigameEndAnalyticsEvent
import dev.betrix.superSmashMobsBrawl.events.PlayerJoinMinigameAnalyticsEvent
import dev.betrix.superSmashMobsBrawl.events.PlayerSelectKitEvent
import dev.betrix.superSmashMobsBrawl.extensions.getEquidistant
import dev.betrix.superSmashMobsBrawl.extensions.getFarthestFromPlayers
import dev.betrix.superSmashMobsBrawl.extensions.location
import dev.betrix.superSmashMobsBrawl.extensions.teleport
import dev.betrix.superSmashMobsBrawl.minigames.managers.DefaultCombatManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.DefaultCountdownManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.DefaultEnvironmentProtectionManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.DefaultHazardManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.DefaultPlayerConnectionManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.DefaultRespawnManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.DefaultScoreboardManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.DefaultTeamManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.DefaultWorldManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.FfaGameObjectiveManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.ICombatManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.ICountdownManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.IEnvironmentProtectionManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.IGameObjectiveManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.IHazardManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.IPlayerConnectionManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.IRespawnManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.IScoreboardManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.ITeamManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.IWorldManager
import dev.betrix.superSmashMobsBrawl.minigames.managers.TeamBasedStocksObjectiveManager
import dev.betrix.superSmashMobsBrawl.models.SpawnPoint
import dev.betrix.superSmashMobsBrawl.models.brawlData.FfaMinigameDef
import dev.betrix.superSmashMobsBrawl.models.brawlData.KitDef
import dev.betrix.superSmashMobsBrawl.models.brawlData.KitSwitchingMode
import dev.betrix.superSmashMobsBrawl.models.brawlData.MinigameDef
import dev.betrix.superSmashMobsBrawl.models.brawlData.TeamBasedStocksMinigameDef
import dev.betrix.superSmashMobsBrawl.services.KitService
import gg.flyte.twilight.event.event
import java.util.UUID
import org.bukkit.OfflinePlayer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class MinigameTeam(
    val players: List<OfflinePlayer>,
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val metadata: MutableMap<String, Any> = mutableMapOf(),
) {
    init {
        require(players.isNotEmpty()) { "A team must have at least one player." }
    }
}

enum class MinigameState {
    PREFLIGHT,
    STARTING,
    IN_PROGRESS,
    ENDING,
    ENDED,
}

interface ITeleportationManager {
    fun teleportTeamToStartingLocation(team: MinigameTeam)

    fun teleportPlayerToSpectatorArea(player: OfflinePlayer)

    fun teleportPlayerRandomSpawnPoint(player: OfflinePlayer)
}

class DefaultTeleportationManager(private val minigame: BrawlMinigame) :
    ITeleportationManager, KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private var startingSpawns: List<dev.betrix.superSmashMobsBrawl.models.SpawnPoint>? = null

    /**
     * SSM AI Edition: one starting spot per player. Uses the map's player spawns; if there are more
     * players than that (16-player games), adds item/crystal marker spots, each time picking the
     * one furthest from every spot already taken.
     */
    private fun startingSpotsFor(
        map: dev.betrix.superSmashMobsBrawl.models.brawlData.GameMapDef,
        count: Int,
    ): List<dev.betrix.superSmashMobsBrawl.models.SpawnPoint> {
        if (count <= map.spawnPoints.size) return map.spawnPoints.getEquidistant(count)
        fun dist(a: dev.betrix.superSmashMobsBrawl.models.SpawnPoint, b: dev.betrix.superSmashMobsBrawl.models.SpawnPoint) =
            (a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z) + (a.y - b.y) * (a.y - b.y)
        val chosen = map.spawnPoints.toMutableList()
        val extras =
            (map.itemSpawnPoints + map.crystalSpawnPoints)
                .distinctBy { Triple(it.x, it.y, it.z) }
                .filter { e -> chosen.none { dist(it, e) < 1.0 } }
                .toMutableList()
        while (chosen.size < count && extras.isNotEmpty()) {
            val next = extras.maxBy { e -> chosen.minOf { dist(it, e) } }
            chosen += next
            extras -= next
        }
        return chosen
    }

    override fun teleportTeamToStartingLocation(team: MinigameTeam) {
        val world = minigame.worldManager.getWorld() ?: return
        // Spread ALL players across the map. (Upstream picked spawns per team, so with one-player
        // teams every player was sent to the same spawn point.)
        val everyone = minigame.allPlayers()
        val allSpawns =
            startingSpawns ?: startingSpotsFor(world.data, everyone.size).shuffled().also { startingSpawns = it }

        if (allSpawns.isEmpty()) {
            plugin.logger.info(
                "World ${world.world.name} has no spawn points defined, teleporting players to spectator area."
            )

            team.players.forEach { player ->
                if (player.isOnline && !minigame.connectionManager.isDisconnected(player)) {
                    player.player?.teleport(world.data.spectatorSpawnPoint)
                }
            }
            return
        }

        team.players.forEach { player ->
            if (player.isOnline && !minigame.connectionManager.isDisconnected(player)) {
                val index = everyone.indexOfFirst { it.uniqueId == player.uniqueId }
                val spawnPoint = allSpawns.getOrNull(index) ?: allSpawns.random()
                player.player?.teleport(world.world.location(spawnPoint))

                // Clear offhand to remove shield mechanics (1.8 feel)
                try {
                    player.player
                        ?.inventory
                        ?.setItemInOffHand(
                            org.bukkit.inventory.ItemStack.of(org.bukkit.Material.AIR)
                        )
                } catch (e: Exception) {
                    plugin.logger.fine("Offhand clear failed for ${player.name}: ${e.message}")
                }
            }
        }
    }

    override fun teleportPlayerToSpectatorArea(player: OfflinePlayer) {
        val world = minigame.worldManager.getWorld() ?: return
        if (player.isOnline && !minigame.connectionManager.isDisconnected(player)) {
            player.player?.teleport(world.data.spectatorSpawnPoint)
        }
    }

    override fun teleportPlayerRandomSpawnPoint(player: OfflinePlayer) {
        val world = minigame.worldManager.getWorld() ?: return
        if (player.isOnline && !minigame.connectionManager.isDisconnected(player)) {
            val bukkitPlayer = player.player ?: return
            val activePlayers =
                minigame
                    .allPlayers()
                    .mapNotNull { if (it.isOnline) it.player else null }
                    .filter { it != bukkitPlayer && it.gameMode != org.bukkit.GameMode.SPECTATOR }

            val spawnPoint =
                world.data.spawnPoints.getFarthestFromPlayers(activePlayers, world.world)
                    ?: world.data.spawnPoints.firstOrNull()
                    ?: run {
                        plugin.logger.warning(
                            "World ${world.world.name} has no spawn points defined, teleporting to world spawn."
                        )

                        SpawnPoint(
                            world.world.spawnLocation.x,
                            world.world.spawnLocation.y,
                            world.world.spawnLocation.z,
                        )
                    }

            bukkitPlayer.teleport(world.world.location(spawnPoint))
        }
    }
}

interface IKitHandler {
    fun assignKitToPlayer(player: OfflinePlayer)

    fun removeKitFromPlayer(player: OfflinePlayer)
}

/** Default kit handler that keeps the player's kit unchanged throughout the game. */
class DefaultKitHandler(private val minigame: BrawlMinigame) : IKitHandler, KoinComponent {
    private val kitService: KitService by inject()

    private val initialPlayerKits = mutableMapOf<UUID, KitDef>()
    private val initialChoices = mutableMapOf<UUID, String>()

    override fun assignKitToPlayer(player: OfflinePlayer) {
        val choice = if (kitService.isRandomSelected(player)) "@random" else kitService.currentSelectedKitForPlayer(player).id
        if (player.uniqueId !in initialPlayerKits ||
            (minigame.getState() == MinigameState.STARTING && initialChoices[player.uniqueId] != choice)) {
            initialPlayerKits[player.uniqueId] = kitService.resolveKitForMatch(player)
            initialChoices[player.uniqueId] = choice
        }
        kitService.assignKit(player, initialPlayerKits.getValue(player.uniqueId))
    }

    override fun removeKitFromPlayer(player: OfflinePlayer) {
        kitService.unassignKit(player)
    }
}

/** Allows players to switch kits at anytime during the game */
class KitHandlerWithSwitching(private val minigame: BrawlMinigame) : IKitHandler, KoinComponent {
    private val kitService: KitService by inject()

    override fun assignKitToPlayer(player: OfflinePlayer) {
        kitService.assignKit(player)
    }

    override fun removeKitFromPlayer(player: OfflinePlayer) {
        kitService.unassignKit(player)
    }
}

/** Allows players to switch kits, but only after they respawn from death */
class KitHandlerWithSwitchingAfterDeath(private val minigame: BrawlMinigame) :
    IKitHandler, KoinComponent {
    private val kitService: KitService by inject()

    override fun assignKitToPlayer(player: OfflinePlayer) {
        kitService.assignKit(player)
    }

    override fun removeKitFromPlayer(player: OfflinePlayer) {
        kitService.unassignKit(player)
    }
}

/** Assigns a random kit to players on each respawn, avoiding consecutive duplicates */
class MysteryKitHandler(private val minigame: BrawlMinigame) : IKitHandler, KoinComponent {
    private val kitService: KitService by inject()
    private val lastAssignedKits = mutableMapOf<UUID, KitDef>()

    override fun assignKitToPlayer(player: OfflinePlayer) {
        val availableKits = kitService.getAllKitData().filter { it.userFacing }

        if (availableKits.isEmpty()) {
            // Fallback to default kit if no user-facing kits available
            kitService.assignKit(player)
            return
        }

        val lastKit = lastAssignedKits[player.uniqueId]
        val kitToAssign =
            if (availableKits.size > 1 && lastKit != null) {
                // Exclude the last kit to avoid consecutive duplicates
                availableKits.filter { it.id != lastKit.id }.random()
            } else {
                // If only one kit available or no last kit, just pick randomly
                availableKits.random()
            }

        lastAssignedKits[player.uniqueId] = kitToAssign
        kitService.assignKit(player, kitToAssign)
    }

    override fun removeKitFromPlayer(player: OfflinePlayer) {
        kitService.unassignKit(player)
    }
}

class BrawlMinigame(val minigameDef: MinigameDef, val teams: List<MinigameTeam>,
    val settings: dev.betrix.superSmashMobsBrawl.services.GameSetupService.Setup = dev.betrix.superSmashMobsBrawl.services.GameSetupService.Setup(),
    val host: UUID? = null,
    val lobbyId: Int = 1) :
    Manageable(), KoinComponent {

    private var state: MinigameState = MinigameState.PREFLIGHT
    private val resultId = UUID.randomUUID().toString()
    val eliminationOrder = mutableListOf<OfflinePlayer>()
    private var firstBloodClaimed = false
    fun claimFirstBlood(): Boolean {
        if (firstBloodClaimed) return false
        firstBloodClaimed = true
        return true
    }
    private var gameStartTime: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null

    // Managers (composable) - select based on minigame type
    val teleportationManager: ITeleportationManager = DefaultTeleportationManager(this)
    val worldManager: IWorldManager = DefaultWorldManager(this)
    val teamManager: ITeamManager = DefaultTeamManager(this)
    val respawnManager: IRespawnManager = DefaultRespawnManager(this)
    val combatManager: ICombatManager = DefaultCombatManager(this)
    val hazardManager: IHazardManager = DefaultHazardManager(this)
    val countdownManager: ICountdownManager = DefaultCountdownManager(this)
    /** Cosmetic match identifier shared by every viewer and stable through scoreboard refreshes. */
    val displayCode = buildString {
        repeat(8) { append("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random()) }
    }
    val introManager = dev.betrix.superSmashMobsBrawl.minigames.managers.MatchIntroManager(this)
    val scoreboardManager: IScoreboardManager = DefaultScoreboardManager()
    val connectionManager: IPlayerConnectionManager = DefaultPlayerConnectionManager(this)
    val environmentProtectionManager: IEnvironmentProtectionManager =
        DefaultEnvironmentProtectionManager(this)

    // Game objective manager - selected based on minigame definition
    val objectiveManager: IGameObjectiveManager =
        when (minigameDef) {
            is FfaMinigameDef -> FfaGameObjectiveManager(this)
            is TeamBasedStocksMinigameDef -> TeamBasedStocksObjectiveManager(this)
        }

    // Kit handler is pluggable
    val kitHandler: IKitHandler =
        when (minigameDef.kitSwitchingMode) {
            KitSwitchingMode.NEVER -> DefaultKitHandler(this)
            KitSwitchingMode.ON_DEATH -> KitHandlerWithSwitchingAfterDeath(this)
            KitSwitchingMode.IMMEDIATE -> KitHandlerWithSwitching(this)
            KitSwitchingMode.MYSTERY -> MysteryKitHandler(this)
        }

    fun allPlayers(): List<OfflinePlayer> = teams.flatMap { it.players }

    fun getState(): MinigameState = state

    fun getKitSwitchingMode(): KitSwitchingMode = minigameDef.kitSwitchingMode

    fun isPassiveValid(id: String): Boolean = minigameDef.isPassiveValid(id)

    suspend fun setup(gameId: String) {
        // Load world first
        worldManager.loadWorld(gameId).onFailure { throw it }

        // /hub may cancel while a larger map is being prepared on the IO dispatcher.
        if (state == MinigameState.ENDED) {
            worldManager.getWorld()?.let { dev.betrix.superSmashMobsBrawl.services.WorldService.deleteWorld(it) }
            worldManager.teardown()
            return
        }

        // Initialize all managers
        teamManager.setup()
        hazardManager.setup()
        combatManager.setup()
        respawnManager.setup()
        objectiveManager.setup()
        environmentProtectionManager.setup()
        scoreboardManager.setup()

        // Setup kit switching event handling
        setupKitSwitchingEvents()

        // Fire join analytics events
        allPlayers().forEach { player ->
            if (player.isOnline && !connectionManager.isDisconnected(player)) {
                PlayerJoinMinigameAnalyticsEvent(player.player!!, this).callEvent()
            }
        }

        // Teleport teams to starting locations
        teams.forEach { team ->
            teleportationManager.teleportTeamToStartingLocation(team)
            // Assign kits to all players in the team
            team.players.forEach { player ->
                if (player.isOnline && !connectionManager.isDisconnected(player)) {
                    kitHandler.assignKitToPlayer(player)
                }
            }
        }

        state = MinigameState.STARTING
    }

    private fun setupKitSwitchingEvents() {
        // Handle kit selection events based on switching mode
        listeners.add(
            event<PlayerSelectKitEvent> {
                // Only handle if this player is in this minigame
                if (connectionManager.isDisconnected(player) || !allPlayers().any { it.isOnline && it.player?.uniqueId == player.uniqueId })
                    return@event

                if(state==MinigameState.STARTING && minigameDef.kitSwitchingMode!=KitSwitchingMode.MYSTERY) {
                    kitHandler.removeKitFromPlayer(player)
                    kitHandler.assignKitToPlayer(player)
                    return@event
                }
                when (minigameDef.kitSwitchingMode) {
                    KitSwitchingMode.IMMEDIATE -> {
                        if (shouldSwitchImmediately && !respawnManager.isRespawning(player)) {
                            // Switch kit immediately (but not if player is currently respawning)
                            kitHandler.removeKitFromPlayer(player)
                            kitHandler.assignKitToPlayer(player)
                        }
                    }
                    else -> {}
                }
            }
        )
    }

    fun startGame() {
        if (state != MinigameState.STARTING) return
        val begin = {
            state = MinigameState.IN_PROGRESS
            dev.betrix.superSmashMobsBrawl.services.BotConversationService.started(this)
            gameStartTime = kotlin.time.TimeSource.Monotonic.markNow()
            allPlayers().filter { !connectionManager.isDisconnected(it) }.mapNotNull { it.player }.forEach {
                dev.betrix.superSmashMobsBrawl.services.KitService.getKitForPlayer(it)?.startPassives()
            }
        }
        if(minigameDef.id!="prototyping")introManager.start(begin) else begin()
    }

    fun endMinigame(reason: String, winners: List<OfflinePlayer> = emptyList()) {
        if (state == MinigameState.ENDED || state == MinigameState.ENDING) return

        if (state == MinigameState.IN_PROGRESS && reason in setOf("winner_determined", "team_victory")) {
            dev.betrix.superSmashMobsBrawl.services.LobbyLeaderboardService.recordVictory(resultId,
                winners.filter { winner -> allPlayers().any { it.uniqueId == winner.uniqueId } })
        }

        state = MinigameState.ENDING

        val gameDuration = gameStartTime?.elapsedNow() ?: kotlin.time.Duration.ZERO

        // Fire analytics event
        MinigameEndAnalyticsEvent(
                this,
                winners.mapNotNull { if (it.isOnline) it.player else null },
                gameDuration,
                reason,
            )
            .callEvent()

        state = MinigameState.ENDED

        allPlayers().forEach { player ->
            if (player.isOnline && !connectionManager.isDisconnected(player)) {
                kitHandler.removeKitFromPlayer(player)
            }
        }

        val gameWorld = worldManager.getWorld()
        val online = allPlayers().filter { !connectionManager.isDisconnected(it) }.mapNotNull { it.player }
        val ending=dev.betrix.superSmashMobsBrawl.minigames.managers.MatchEnding.presentation(this,winners)
        teardown()

        // SSM AI Edition: upstream left everyone stranded in the game map with no kit and no
        // protection (a TODO). Show the winner, then send everyone home and delete the map copy.
        online.forEach { p ->
            if (winners.isNotEmpty()) {
                p.gameMode = org.bukkit.GameMode.SPECTATOR
                ending?.let {it.show(p)}
            } else p.clearTitle()
        }
        val plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("super-smash-mobs-brawl") ?: return
        val podium = if (winners.isEmpty()) null else
            dev.betrix.superSmashMobsBrawl.minigames.managers.WinPodium.show(gameWorld?.world, winners, online, plugin)
        org.bukkit.Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                podium?.close()
                online.filter { it.isOnline }.forEach { p ->
                    if (gameWorld == null || p.world == gameWorld.world) {
                        dev.betrix.superSmashMobsBrawl.services.HubService.tryTeleportToDefaultHub(p)
                    }
                }
                if (gameWorld != null) {
                    (plugin as dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl).launch {
                        dev.betrix.superSmashMobsBrawl.services.WorldService.deleteWorld(gameWorld)
                        dev.betrix.superSmashMobsBrawl.services.LobbyService.release(this@BrawlMinigame)
                    }
                } else dev.betrix.superSmashMobsBrawl.services.LobbyService.release(this@BrawlMinigame)
            },
            if (winners.isEmpty()) 1L else 160L,
        )
    }

    fun arePlayersOnSameTeam(playerA: OfflinePlayer, playerB: OfflinePlayer): Boolean {
        // Team-Ups: bots that have teamed up on somebody do not fight each other until that is over.
        if (settings.teamUps && dev.betrix.superSmashMobsBrawl.services.BotConversationService.allied(this, playerA.uniqueId, playerB.uniqueId)) return true
        if (settings.allBotsVsPlayer && host != null && playerA.uniqueId != host && playerB.uniqueId != host &&
            allPlayers().any { it.uniqueId == playerA.uniqueId } && allPlayers().any { it.uniqueId == playerB.uniqueId }) return true
        val teamA = teamManager.findTeamOf(playerA)
        val teamB = teamManager.findTeamOf(playerB)

        if (teamA == null || teamB == null) return false

        return teamA.id == teamB.id
    }

    override fun teardown() {
        dev.betrix.superSmashMobsBrawl.services.BotConversationService.close(this)
        dev.betrix.superSmashMobsBrawl.items.ItemService.close(this)
        super.teardown()
        introManager.teardown()
        (hazardManager as? Manageable)?.teardown()
        (combatManager as? Manageable)?.teardown()
        (scoreboardManager as? Manageable)?.teardown()
        (respawnManager as? Manageable)?.teardown()
        (connectionManager as? Manageable)?.teardown()
        (objectiveManager as? Manageable)?.teardown()
        (environmentProtectionManager as? Manageable)?.teardown()
        (worldManager as? Manageable)?.teardown()
        (countdownManager as? Manageable)?.teardown()
        (teamManager as? Manageable)?.teardown()
    }
}
