package dev.betrix.superSmashMobsBrawl.bots

import com.charleskorn.kaml.Yaml
import com.mojang.authlib.GameProfile
import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.bots.nms.BotPlayer
import dev.betrix.superSmashMobsBrawl.bots.nms.FakeConnection
import dev.betrix.superSmashMobsBrawl.models.brawlData.FfaMinigameDef
import dev.betrix.superSmashMobsBrawl.models.brawlData.MinigameDef
import dev.betrix.superSmashMobsBrawl.models.brawlData.TeamBasedStocksMinigameDef
import dev.betrix.superSmashMobsBrawl.services.ApiService
import dev.betrix.superSmashMobsBrawl.services.DataService
import dev.betrix.superSmashMobsBrawl.services.KitService
import dev.betrix.superSmashMobsBrawl.services.MinigameService
import dev.betrix.superSmashMobsBrawl.services.QueueService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import net.kyori.adventure.text.minimessage.MiniMessage
import net.minecraft.server.network.CommonListenerCookie
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.CraftServer
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Spawns and removes AI bots and fills game queues with them.
 *
 * Bots are real (server-side) players: they show in the tab list, get join/leave messages, queue
 * for games and are treated exactly like humans by the game code.
 */
object BotManager : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val dataService: DataService by inject()
    private val minigameService: MinigameService by inject()

    data class BotProfile(val name: String, val rank: String, val difficulty: BotDifficulty, val level: Int = BotIdentity.rollLevel(difficulty))

    @Serializable
    private data class RosterFile(
        val rankStyles: Map<String, String> = emptyMap(),
        val bots: List<RosterEntry> = emptyList(),
    )

    @Serializable
    private data class RosterEntry(val name: String, val rank: String = "none", val difficulty: String = "normal")

    private class Bot(val profile: BotProfile, val nms: BotPlayer, val brain: BotBrain, var lobbyPreview: Boolean = false) {
        var idleSinceMs: Long? = null
        /** Spawned by /bots game: keeps playing even with no real players around. */
        var testBot = false
        /** How long this bot hangs around in the hub after a game before "leaving". */
        val lingerMs: Long = 4000L + (Math.random() * 10000).toLong()
    }

    private val bots = ConcurrentHashMap<UUID, Bot>()
    private val pendingFill = mutableSetOf<Pair<String, Int>>()
    private val pendingBots = mutableSetOf<UUID>()

    /** /bots debug: log every bot jump, double jump and recovery decision. */
    @Volatile var debug = false
    /** Debug: Sheep bots try their Wool Mine perch on any opponent well below them, reachable or not. */
    @Volatile var forcePerch = false
    /** Debug: bots forget how they died, the way they played before they learned from it. */
    @Volatile var noLearning = false
    private var roster: List<BotProfile> = emptyList()
    private var rankStyles: Map<String, String> = emptyMap()

    /** Every playable kit participates; the hub loadout is not a combat kit. */
    private val excludedKits = setOf("hub")

    /** Ticks to wait after a real player queues before bots join (feels like people joining). */
    private const val FILL_DELAY_TICKS = 50L

    fun setup() {
        val text =
            plugin.getResource("data/bots.yml")?.bufferedReader()?.readText()?.removePrefix("ï»¿")
                ?: error("data/bots.yml missing from plugin jar")
        val file =
            Yaml(configuration = Yaml.default.configuration.copy(strictMode = false))
                .decodeFromString(RosterFile.serializer(), text)
        rankStyles = file.rankStyles.mapKeys { it.key.lowercase() }
        roster = file.bots.map { BotProfile(it.name, it.rank.lowercase(), BotDifficulty.parse(it.difficulty)) }
        plugin.logger.info(
            "Loaded ${roster.size} AI bots (" +
                roster.groupingBy { it.difficulty }.eachCount().entries.joinToString { "${it.key.name.lowercase()}=${it.value}" } +
                ")"
        )

        // Where each arena's long jumps are kept once they have been worked out.
        ArenaJumps.folder = java.io.File(plugin.dataFolder, "nav")

        // Bots that are not queued or playing leave after a short while.
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { removeIdleBots() }, 40L, 40L)

        // Log how bots die and who wins, so bot behaviour can be tuned from the server log.
        dev.betrix.superSmashMobsBrawl.events.BrawlDeathEvent.listen {
            val profile = profileOf(player) ?: return@listen
            val game = minigameService.getMinigameForPlayer(player)
            val lives = game?.teamManager?.findTeamOf(player)?.metadata?.get("stocks")
            plugin.logger.info(
                "[bots] ${player.name} (${profile.difficulty.name.lowercase()}) died: " +
                    "${reason::class.simpleName}${(reason as? dev.betrix.superSmashMobsBrawl.events.DeathReason.Hazard)?.type?.let { " $it" } ?: ""}" +
                    " at ${"%.1f %.1f %.1f".format(player.location.x, player.location.y, player.location.z)}, lives before=$lives"
            )
            // What the bot makes of it: not to die the same way to the same opponent twice.
            if (game != null) brainOf(player)?.died(reason is dev.betrix.superSmashMobsBrawl.events.DeathReason.Hazard)
        }
        gg.flyte.twilight.event.event<dev.betrix.superSmashMobsBrawl.events.MinigameEndAnalyticsEvent> {
            plugin.logger.info(
                "[bots] game ${minigame.minigameDef.id} ended after ${duration.inWholeSeconds}s " +
                    "($endReason), winners: ${winners.joinToString { it.name }}"
            )
        }
    }

    fun isBot(player: Player): Boolean = bots.containsKey(player.uniqueId)

    fun isBot(uuid: UUID): Boolean = bots.containsKey(uuid)

    fun onlineBots(): List<Player> = bots.values.map { it.nms.bukkitEntity }

    fun isLobbyBot(player: Player): Boolean = bots[player.uniqueId]?.lobbyPreview == true
    fun isReserved(player: Player): Boolean = player.uniqueId in pendingBots || QueueService.getQueueEntry(player) != null || minigameService.isPlayerInMinigame(player)
    fun claimLobbyBot(player: Player, kitId: String?) {
        val bot = bots[player.uniqueId] ?: return
        bot.lobbyPreview = false
        bot.idleSinceMs = null
        val kit = kitId ?: randomKitId()
        if (kit != null) {
            ApiService.setSelectedKit(player.uniqueId, kit)
            dataService.getKit(kit)?.let { KitService.playerSelectKit(player, it) }
        }
    }

    fun profileOf(player: Player): BotProfile? = bots[player.uniqueId]?.profile
    fun rosterProfiles():List<BotProfile> = roster.toList()
    fun displayNameOf(player:org.bukkit.OfflinePlayer):String = bots[player.uniqueId]?.profile?.name ?: player.name ?: "Player"

    // ------------------------------------------------------------------ spawning

    /** Spawns a bot on the main thread, keeping a custom opponent separate from its online namesake. */
    @JvmOverloads fun spawn(profile: BotProfile, kitId: String? = randomKitId(), lobbyPreviewId: Int? = null): Player? {
        if(lobbyPreviewId == null && bots.values.any {!it.lobbyPreview && it.profile.name.equals(profile.name,true)})return null
        val occupied=Bukkit.getPlayerExact(profile.name)
        val internalName=if(occupied==null)profile.name else {
            if(lobbyPreviewId == null && !isBot(occupied) && !dev.betrix.superSmashMobsBrawl.services.CustomBotService.isCustom(profile.name))return null
            (1..99).map {val suffix="_AI$it";profile.name.take(16-suffix.length)+suffix}
                .firstOrNull {Bukkit.getPlayerExact(it)==null} ?: return null
        }

        val server = (Bukkit.getServer() as CraftServer).server
        val level = (Bukkit.getWorlds().first() as CraftWorld).handle
        val key = if(lobbyPreviewId == null) "SSMAIBot:${profile.name}" else "SSMLobbyBot:$lobbyPreviewId:${profile.name.lowercase()}"
        val uuid = UUID.nameUUIDFromBytes(key.toByteArray())
        if(Bukkit.getPlayer(uuid)!=null)return null
        if (kitId != null) ApiService.setSelectedKit(uuid, kitId)

        val skin = dev.betrix.superSmashMobsBrawl.services.BotSkinService.cached(profile.name)
        val properties = (skin as? org.bukkit.craftbukkit.profile.CraftPlayerProfile)?.buildGameProfile()?.properties()
        val gameProfile = if(properties==null)GameProfile(uuid,internalName) else GameProfile(uuid,internalName,properties)
        val nms = BotPlayer(server, level, gameProfile)
        val brain = BotBrain(nms, profile)
        bots[uuid] = Bot(profile, nms, brain, lobbyPreviewId != null)
        // Assign before the join hook teleports the player; a preview never flashes into another hub.
        if (lobbyPreviewId != null) dev.betrix.superSmashMobsBrawl.services.LobbyService.assign(nms.bukkitEntity, lobbyPreviewId)

        try {
            server.playerList.placeNewPlayer(
                FakeConnection(),
                nms,
                CommonListenerCookie.createInitial(gameProfile, false),
            )
        } catch (e: Exception) {
            bots.remove(uuid)
            dev.betrix.superSmashMobsBrawl.services.LobbyService.forgetBot(nms.bukkitEntity)
            plugin.logger.severe("Failed to spawn bot ${profile.name}: ${e.stackTraceToString()}")
            return null
        }

        val player = nms.bukkitEntity
        val identity = BotIdentity.worldName(profile)
        player.playerListName(identity)
        player.displayName(identity)
        dev.betrix.superSmashMobsBrawl.services.BotSkinService.resolve(profile.name) {skin ->
            if(skin!=null && player.isOnline && bots[uuid]?.nms===nms)
                dev.betrix.superSmashMobsBrawl.services.BotSkinService.applyTo(player,skin)
        }
        if (kitId != null) dataService.getKit(kitId)?.let { KitService.playerSelectKit(player, it) }
        brain.start()
        return player
    }

    fun despawn(player: Player) {
        val bot = bots[player.uniqueId] ?: return
        // Ended games can retain the previous Player after a lobby replacement reuses its UUID.
        if (bot.nms.bukkitEntity !== player) return
        pendingBots.remove(player.uniqueId)
        bot.brain.stop()
        QueueService.removePlayer(player)
        try {
            (Bukkit.getServer() as CraftServer).server.playerList.remove(bot.nms)
        } catch (e: Exception) {
            plugin.logger.warning("Error removing bot ${bot.profile.name}: ${e.message}")
        }
        bots.remove(player.uniqueId)
        dev.betrix.superSmashMobsBrawl.services.LobbyService.forgetBot(player)
    }

    fun despawnAll() {
        bots.values.toList().forEach { despawn(it.nms.bukkitEntity) }
    }

    private fun randomKitId(): String? =
        dataService.getAllKits().filter { it.userFacing && it.id !in excludedKits }.randomOrNull()?.id

    private fun freeProfiles(): List<BotProfile> =
        roster.filter { p -> Bukkit.getPlayerExact(p.name) == null }

    /** Manual opponents first; only built-in profiles may fill the remaining slots. */
    @JvmOverloads fun spawnMatch(count:Int,difficulty:BotDifficulty?,preferred:List<String>,kits:Map<String,String> = emptyMap()):List<Player> {
        // Ended-match bots may linger cosmetically. Reclaim only idle bots, never another match.
        bots.values.toList().map { it.nms.bukkitEntity }.filter {
            !isLobbyBot(it) && !isReserved(it)
        }.forEach(::despawn)
        val custom=dev.betrix.superSmashMobsBrawl.services.CustomBotService.profiles()
        val free=(freeProfiles()+custom.filter {candidate -> bots.values.none {it.profile.name.equals(candidate.name,true)}})
        val names=preferred.distinctBy {it.lowercase()}.take(count)
        val named=names.mapNotNull {name -> free.find {it.name.equals(name,true)}}
        if(named.size!=names.size)return emptyList() // Never silently replace an unavailable selected opponent.
        val fillers=freeProfiles().filter {candidate -> named.none {it.name.equals(candidate.name,true)}}.shuffled()
        return (named+fillers).take(count).mapNotNull {
            val selected=if(custom.any {c -> c.name.equals(it.name,true)})it.difficulty else difficulty ?: it.difficulty
            spawn(it.copy(difficulty=selected,level=BotIdentity.rollLevel(selected)),kits[it.name.lowercase()] ?: randomKitId())
        }
    }

    /** Spawns [count] random bots (optionally of one difficulty). */
    fun spawnRandom(count: Int, difficulty: BotDifficulty? = null): List<Player> =
        freeProfiles()
            .filter { difficulty == null || it.difficulty == difficulty }
            .shuffled()
            .take(count)
            .mapNotNull { spawn(it.copy(level = BotIdentity.rollLevel(it.difficulty))) }

    // ------------------------------------------------------------------ queue filling

    /** Called by QueueService whenever someone joins a queue. */
    fun onPlayerQueued(player: Player, def: MinigameDef) {
        if (isBot(player)) return
        val delay = FILL_DELAY_TICKS + (Math.random() * 40).toLong()
        val lobbyId = dev.betrix.superSmashMobsBrawl.services.LobbyService.lobbyOf(player)
        scheduleFill(def, lobbyId, delay)
    }

    private fun scheduleFill(def: MinigameDef, lobbyId: Int, delay: Long = FILL_DELAY_TICKS) {
        val key = def.id to lobbyId
        if (!pendingFill.add(key)) return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            pendingFill.remove(key)
            fillQueue(def, lobbyId)
        }, delay)
    }

    private fun fillQueue(def: MinigameDef, lobbyId: Int) {
        val queued = QueueService.getPlayersInQueue(def, lobbyId).filter { it.player.isOnline }
        if (queued.none { !isBot(it.player) }) return // the human left the queue
        if (dev.betrix.superSmashMobsBrawl.services.LobbyService.busy(lobbyId) || minigameService.capacityRejection(QueueService.requiredPlayers(def, lobbyId)) != null) {
            scheduleFill(def, lobbyId)
            return
        }
        val need = QueueService.requiredPlayers(def, lobbyId) - queued.size
        if (need <= 0) return

        // Join one at a time, a few ticks apart, like real people.
        val host = queued.first { !isBot(it.player) }.player
        dev.betrix.superSmashMobsBrawl.services.LobbyBotService.takeForMatch(host, need).forEachIndexed { i, bot ->
            pendingBots.add(bot.uniqueId)
            dev.betrix.superSmashMobsBrawl.services.LobbyService.assign(bot, lobbyId)
            dev.betrix.superSmashMobsBrawl.services.HubService.tryTeleportToDefaultHub(bot)
            Bukkit.getScheduler().runTaskLater(
                plugin,
                Runnable {
                    pendingBots.remove(bot.uniqueId)
                    val current = QueueService.getPlayersInQueue(def, lobbyId)
                    if (bot.isOnline && !dev.betrix.superSmashMobsBrawl.services.LobbyService.busy(lobbyId) &&
                        current.any { it.player.isOnline && !isBot(it.player) } && current.size < QueueService.requiredPlayers(def, lobbyId))
                        QueueService.addPlayer(bot, def)
                    else if (!minigameService.isPlayerInMinigame(bot)) despawn(bot)
                },
                5L + i * (5L + (Math.random() * 15).toLong()),
            )
        }
    }

    fun brainOf(player: Player): BotBrain? = bots[player.uniqueId]?.brain

    /** Test helper: start a game made only of bots. [kits] fixes the kit of each bot by position. */
    fun startBotGame(def: MinigameDef, count: Int, difficulty: BotDifficulty? = null, mapId: String? = null,
                     kits: List<String> = emptyList(), lobby: Int? = null,
                     setup: dev.betrix.superSmashMobsBrawl.services.GameSetupService.Setup? = null): List<Player> {
        val lobbyId = lobby ?: dev.betrix.superSmashMobsBrawl.services.LobbyRouting.ids.firstOrNull { !dev.betrix.superSmashMobsBrawl.services.LobbyService.busy(it) } ?: return emptyList()
        val spawned = freeProfiles()
            .filter { difficulty == null || it.difficulty == difficulty }
            .shuffled()
            .take(count)
            .mapIndexedNotNull { i, profile ->
                spawn(profile.copy(level = BotIdentity.rollLevel(profile.difficulty)), kits.getOrNull(i) ?: randomKitId())
            }
        spawned.forEach {
            dev.betrix.superSmashMobsBrawl.services.LobbyService.assign(it, lobbyId)
            dev.betrix.superSmashMobsBrawl.services.HubService.tryTeleportToDefaultHub(it)
        }
        spawned.forEach { bots[it.uniqueId]?.testBot = true }
        if (mapId != null) spawned.forEach { dev.betrix.superSmashMobsBrawl.services.MapVoteService.setVote(it.uniqueId, mapId) }
        // Start straight away (like the hub's Play button) instead of waiting for a full queue.
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                val players = spawned.filter { it.isOnline && !minigameService.isPlayerInMinigame(it) }
                if (players.size >= 2) dev.betrix.superSmashMobsBrawl.events.QueuePopEvent(def.id, players, setup, lobbyId = lobbyId).callEvent()
            },
            20L,
        )
        return spawned
    }

    private fun removeIdleBots() {
        // A game with only bots left (every real player quit or went to the hub) is pointless.
        bots.values
            .filter { !it.testBot }
            .mapNotNull { minigameService.getMinigameForPlayer(it.nms.bukkitEntity) }
            .distinct()
            .filter { game ->
                game.getState() == dev.betrix.superSmashMobsBrawl.minigames.MinigameState.IN_PROGRESS &&
                    game.allPlayers().none { p -> p.isOnline && !isBot(p.uniqueId) && game.connectionManager.isDisconnected(p).not() }
            }
            .forEach { game ->
                plugin.logger.info("[bots] ending ${game.minigameDef.id}: no real players left")
                game.endMinigame("no_real_players")
            }

        val now = System.currentTimeMillis()
        bots.values.toList().forEach { bot ->
            val p = bot.nms.bukkitEntity
            val busy = bot.lobbyPreview || isReserved(p)
            if (busy) {
                bot.idleSinceMs = null
                return@forEach
            }
            val since = bot.idleSinceMs ?: now.also { bot.idleSinceMs = it }
            if (now - since > bot.lingerMs) despawn(p)
        }
    }
}
