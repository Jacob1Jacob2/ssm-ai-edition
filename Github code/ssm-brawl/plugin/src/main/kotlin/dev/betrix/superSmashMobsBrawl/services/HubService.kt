package dev.betrix.superSmashMobsBrawl.services

import com.github.michaelbull.result.*
import com.github.michaelbull.result.Ok
import com.github.shynixn.mccoroutine.bukkit.launch
import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.hotbar.HubHotbarPreset
import dev.betrix.superSmashMobsBrawl.kits.BrawlKit
import dev.betrix.superSmashMobsBrawl.models.BrawlHubWorld
import dev.betrix.superSmashMobsBrawl.utils.createLocation
import gg.flyte.twilight.event.event
import gg.flyte.twilight.extension.feed
import gg.flyte.twilight.extension.heal
import gg.flyte.twilight.extension.resetFlySpeed
import gg.flyte.twilight.extension.resetWalkSpeed
import java.util.UUID
import org.bukkit.GameMode
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Service responsible for managing hub worlds and player hub interactions */
object HubService : KoinComponent {
    private lateinit var defaultHubWorld: BrawlHubWorld
    private lateinit var plugin: SuperSmashMobsBrawl
    private val dataService: DataService by inject()
    private val lang: LangService by inject()
    private val hotbarService: HotbarService by inject()
    private val playersInHub = mutableSetOf<Player>()
    private val playerHubKits = mutableMapOf<Player, BrawlKit>()
    private var lobbyTask: org.bukkit.scheduler.BukkitTask? = null
    private var voidTask: org.bukkit.scheduler.BukkitTask? = null
    /** How far below the lobby spawn a player may fall before being put back on it. */
    private const val VOID_DROP = 40.0

    fun teardown() {
        lobbyTask?.cancel()
        lobbyTask = null
        voidTask?.cancel()
        voidTask = null
        LobbyInfoService.teardown()
        LobbyLeaderboardService.teardown()
        LobbyNpcPresentation.teardown()
        LobbyService.teardown()
        playersInHub.forEach { it.resetPlayerTime(); it.resetPlayerWeather() }
        playerHubKits.values.toList().forEach { it.teardown() }
        playerHubKits.clear()
        playersInHub.clear()
        plugin.logger.info("Hub service cleaned up")
    }

    fun initialize(plugin: SuperSmashMobsBrawl) {
        this.plugin = plugin

        plugin.launch {
            dataService.awaitReady()
            val mapDef = dataService.getHubMap("lobby") ?: error("No hub map with id 'lobby' in maps.yml")
            LobbyNpcPresentation.initialize(plugin)
            for (id in LobbyRouting.ids) {
                val result = WorldService.loadPersistentHub(mapDef, id)
                result.mapBoth(success = { hub ->
                    LobbyService.register(id, hub)
                    if (id == 1) defaultHubWorld = hub
                    LobbyBuildService.settleSelectors(hub.world.entities)
                    PolarBearLobby.install(hub.world)
                    LobbyGuideService.initialize(plugin, hub.world)
                    LobbyLeaderboardService.initialize(plugin, hub.world)
                    daylight(hub.world)
                    LobbyInfoService.initialize(plugin, hub.world)
                    plugin.logger.info("Lobby $id ready: ${hub.world.name}")
                }, failure = { throw it })
            }
            lobbyTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                LobbyService.allHubs().forEach { hub ->
                    daylight(hub.world)
                    hub.world.players.forEach { resetLobbySky(it, hub.world) }
                }
                LobbyInfoService.refresh()
            }, 1L, 20L)
            // Lobby damage is cancelled, so the void never kills: put fallers back on the spawn.
            voidTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
                LobbyService.allHubs().forEach { hub ->
                    val spawn = hub.data.spawnPoints.firstOrNull() ?: return@forEach
                    val floor = maxOf(spawn.y - VOID_DROP, hub.world.minHeight.toDouble())
                    hub.world.players.filter { it.location.y < floor }.forEach { player ->
                        player.fallDistance = 0f
                        player.velocity = org.bukkit.util.Vector(0, 0, 0)
                        player.teleport(createLocation(hub.world, spawn))
                        player.fallDistance = 0f
                    }
                }
            }, 2L, 2L)
            registerEvents()
            plugin.server.onlinePlayers.forEach { player ->
                LobbyService.arrive(player)
                teleportToDefaultHub(player)
            }
            LobbyBotService.setup()
            plugin.logger.info("All five lobbies ready")
        }
    }

    private fun daylight(world: World) {
        // 26.3 uses world clocks. The legacy daylight gamerule alone does not pause them.
        val level = (world as org.bukkit.craftbukkit.CraftWorld).handle
        level.dimensionType().defaultClock().ifPresent { clock ->
            val clocks = level.clockManager()
            if (!clocks.getInstance(clock).isPaused) clocks.setPaused(clock, true)
        }
        if (world.time != 6000L) world.time=6000L
        world.setStorm(false)
        world.isThundering=false
        level.setRainLevel(0f)
        level.setThunderLevel(0f)
        world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE,false)
        world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE,false)
    }

    private fun resetLobbySky(player:Player,world:World) {
        daylight(world)
        player.setPlayerTime(6000L, false)
        dev.betrix.superSmashMobsBrawl.utils.ClearWeather.immediately(player)
        player.removePotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS)
        player.removePotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS)
    }

    fun playersInHubCount(): Int {
        return playersInHub.size
    }

    private fun registerEvents() {
        event<PlayerJoinEvent> {
            plugin.logger.info("${player.name} joined")

            joinMessage(null)

            if (!::defaultHubWorld.isInitialized) {
                plugin.logger.severe("No default hub world set")
                player.kick(lang.t("messages.kick.serverStarting"))
                return@event
            }

            LobbyService.arrive(player)
            teleportToDefaultHub(player).onFailure {
                player.kick(lang.t("messages.kick.serverStarting"))
                return@event
            }
            giveHubPassives(player)
            if (!dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(player)) LobbyService.announce(player)
            if (!dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(player)) {
                val message = lang.t("messages.players.joinServer") { "playerName" to player.name }
                plugin.server.onlinePlayers.filter { LobbyService.sameChannel(player.uniqueId, it.uniqueId) }.forEach { it.sendMessage(message) }
            }
        }

        event<PlayerQuitEvent> {
            quitMessage(null)
            if (!dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(player)) {
                val message = lang.t("messages.players.quitServer") { "playerName" to player.name }
                plugin.server.onlinePlayers.filter { it != player && LobbyService.sameChannel(player.uniqueId, it.uniqueId) }.forEach { it.sendMessage(message) }
            }
            QueueService.removePlayer(player)
            LobbyService.disconnected(player)
            LobbyInfoService.remove(player.uniqueId)
            LobbyLeaderboardService.remove(player.uniqueId)
            removeHubPassives(player)
            playersInHub.remove(player)
        }

        // Clear the old arena's rain fade before the destination-world packets are sent.
        event<org.bukkit.event.player.PlayerTeleportEvent>(priority=org.bukkit.event.EventPriority.MONITOR,ignoreCancelled=true) {
            to.world.takeIf {isWorldHub(it)}?.let {resetLobbySky(player,it)}
        }
        // Build hub items only after arrival: the compass checks the player's actual world.
        event<PlayerChangedWorldEvent> {
            val fromHub = isWorldHub(from)
            val toHub = isWorldHub(player.world)

            if (fromHub && toHub) {
                LobbyInfoService.remove(player.uniqueId)
                LobbyLeaderboardService.remove(player.uniqueId)
                resetLobbySky(player, player.world)
            }
            if (!fromHub && toHub) {
                playersInHub.add(player)
                resetLobbySky(player, player.world)
                giveHubPassives(player)
            } else if (fromHub && !toHub) {
                player.resetPlayerTime()
                player.resetPlayerWeather()
                LobbyInfoService.remove(player.uniqueId)
                LobbyLeaderboardService.remove(player.uniqueId)
                playersInHub.remove(player)
                removeHubPassives(player)
            }
        }

        event<EntityDamageEvent> {
            if (entity is Player) {
                val player = entity as Player
                if (isPlayerInHub(player)) {
                    isCancelled = true
                }
            }
        }
    }

    fun teleportToHub(player: Player, hubWorld: BrawlHubWorld): Result<Unit, Exception> {
        if (hubWorld.data.spawnPoints.isEmpty()) {
            return Err(RuntimeException("Hub spawn points were empty"))
        }

        val spawnPoint = hubWorld.data.spawnPoints[0]
        val teleportSuccess = player.teleport(createLocation(hubWorld.world, spawnPoint))

        if (!teleportSuccess) {
            return Err(RuntimeException("Teleport to hub failed for ${player.name}"))
        }

        LobbyInfoService.remove(player.uniqueId)
        LobbyLeaderboardService.remove(player.uniqueId)
        LobbyService.waiting(player)
        resetLobbySky(player, hubWorld.world)
        player.feed()
        player.heal()
        player.resetWalkSpeed()
        player.resetFlySpeed()
        player.gameMode = GameMode.SURVIVAL
        player.fallDistance = 0f
        playersInHub.add(player)

        return Ok(Unit)
    }

    fun teleportToDefaultHub(player: Player): Result<Unit, Exception> {
        if (!::defaultHubWorld.isInitialized) {
            return Err(IllegalStateException("Default hub world is not yet initialized"))
        }
        val result = teleportToHub(player, LobbyService.hub(LobbyService.lobbyOf(player)) ?: defaultHubWorld)
        if (result.isOk) {
            giveHubPassives(player)
        }
        return result
    }

    fun tryTeleportToDefaultHub(player: Player): Result<Unit, Exception> {
        return if (::defaultHubWorld.isInitialized) {
            try {
                val result = teleportToHub(player, LobbyService.hub(LobbyService.lobbyOf(player)) ?: defaultHubWorld)
                if (result.isOk) {
                    giveHubPassives(player)
                }
                result
            } catch (e: Exception) {
                Err(e)
            }
        } else {
            Err(IllegalStateException("Default hub world is not yet initialized"))
        }
    }

    fun isPlayerInHub(player: Player): Boolean {
        return isWorldHub(player.world)
    }

    fun isWorldHub(world: World): Boolean =
        LobbyService.allHubs().any { it.world == world }

    private fun giveHubPassives(player: Player) {
        if (playerHubKits.containsKey(player)) {
            return
        }

        val hubKit = BrawlKit("hub", player)
        hubKit.setup()

        playerHubKits[player] = hubKit

        player.inventory.clear()
        hotbarService.applyPreset(player, HubHotbarPreset)
    }

    private fun removeHubPassives(player: Player) {
        hotbarService.clearHotbar(player)

        val hubKit = playerHubKits.remove(player)
        hubKit?.teardown()

        plugin.logger.info("Removed hub passives from player: ${player.name}")
    }
}
