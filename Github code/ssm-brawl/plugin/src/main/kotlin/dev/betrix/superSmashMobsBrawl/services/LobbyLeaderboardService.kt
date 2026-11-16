package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.bots.BotIdentity
import dev.betrix.superSmashMobsBrawl.bots.BotManager
import gg.flyte.twilight.event.event
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor.*
import net.kyori.adventure.text.format.TextDecoration.BOLD
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** One persistent set of results, with an independently selectable board for each viewer. */
object LobbyLeaderboardService {
    private lateinit var plugin: JavaPlugin
    private val worlds = mutableSetOf<World>()
    private var registered = false
    private var records: WinRecords? = null
    private val pedestals = mutableMapOf<World, Location>()
    private val interactions = mutableListOf<Interaction>()
    private var task: BukkitTask? = null
    private val key = NamespacedKey("ssm", "wins_leaderboard")
    private val boards = mutableMapOf<UUID, List<TextDisplay>>()
    private val periods = mutableMapOf<UUID, Boolean>() // false: wins, true: kills
    private val lastClicks = mutableMapOf<UUID, Long>()
    private val zone = ZoneId.of("America/New_York")

    fun initialize(plugin: JavaPlugin, world: World) {
        this.plugin = plugin
        worlds.add(world)
        if (records == null) records = runCatching { WinRecords(plugin.dataFolder.toPath().resolve("wins.json")) }
            .onFailure { plugin.logger.severe("Could not read wins.json; preserving the file: ${it.message}") }.getOrNull()
        // The user's gold pedestal, surrounded by light-blue glass. Never rebuild the platform.
        val at = Location(world, plugin.config.getDouble("leaderboard.x", 0.0),
            plugin.config.getDouble("leaderboard.y", 59.0), plugin.config.getDouble("leaderboard.z", -9.0))
        at.chunk.load()
        world.entities.filter { it.persistentDataContainer.has(key) }.forEach(Entity::remove)
        if (at.block.type != Material.GOLD_BLOCK) {
            plugin.logger.warning("Wins will still be saved, but the leaderboard needs its gold block at ${at.blockX}, ${at.blockY}, ${at.blockZ}.")
            return
        }
        pedestals[world] = at.block.location
        interactions += world.spawn(at.block.location.add(.5, 1.0, .5), Interaction::class.java) {
            it.interactionWidth = 1f; it.interactionHeight = 1f; it.isResponsive = true
            it.isPersistent = false
            it.persistentDataContainer.set(key, PersistentDataType.BYTE, 1.toByte())
        }
        if (registered) return
        registered = true
        event<PrePlayerAttackEntityEvent>(priority = EventPriority.HIGHEST) {
            if (!attacked.persistentDataContainer.has(key)) return@event
            isCancelled = true
            cycle(player)
        }
        event<PlayerInteractEntityEvent>(priority = EventPriority.HIGHEST) {
            if (!rightClicked.persistentDataContainer.has(key)) return@event
            isCancelled = true
            if (hand == EquipmentSlot.HAND) cycle(player)
        }
        event<PlayerInteractEvent>(priority = EventPriority.HIGHEST) {
            if (hand != EquipmentSlot.HAND || clickedBlock?.location != pedestals[player.world]) return@event
            if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return@event
            if (LobbyBuildService.isBuilding(player)) return@event
            isCancelled = true
            cycle(player)
        }
        event<BlockBreakEvent>(priority = EventPriority.HIGHEST) {
            if (block.location == pedestals[block.world] && !LobbyBuildService.isBuilding(player)) isCancelled = true
        }
        event<PlayerQuitEvent> { remove(player.uniqueId); periods.remove(player.uniqueId); lastClicks.remove(player.uniqueId) }
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { refresh() }, 1L, 20L)
        plugin.logger.info("Wins leaderboard ready on the gold/blue-glass platform at 0,59,-9")
    }

    fun recordVictory(matchId: String, winners: List<OfflinePlayer>) {
        val identities = winners.map { winner ->
            val bot = winner.player?.let(BotManager::profileOf)
            val name = bot?.name ?: winner.name ?: winner.uniqueId.toString()
            WinIdentity(winner.uniqueId.toString(), name, bot != null,
                bot?.rank ?: if (name.equals("Jacob1Jacob2", true)) "god" else "none")
        }
        runCatching { records?.record(matchId, identities, LocalDate.now(zone)) }
            .onFailure { plugin.logger.severe("Could not save match $matchId wins: ${it.message}") }
        refresh()
    }

    fun rankedName(identity: WinIdentity): Component {
        val rank = identity.rank.lowercase().let { if (it == "mythic") "mythical" else it }
        val color = BotIdentity.rankColor(rank)
        val prefix = if (rank == "none" || rank.isBlank()) Component.empty()
            else Component.text(rank.uppercase(), color).decorate(BOLD).append(Component.space())
        return prefix.append(Component.text(identity.name, color).decoration(BOLD, false))
    }

    fun recordKill(player: Player) {
        val bot = BotManager.profileOf(player)
        val identity = WinIdentity(player.uniqueId.toString(), bot?.name ?: player.name, bot != null,
            bot?.rank ?: if (player.name.equals("Jacob1Jacob2", true)) "god" else "none")
        runCatching { records?.recordKill(identity) }
            .onFailure { plugin.logger.severe("Could not save kill: ${it.message}") }
    }

    private fun cycle(player: Player) {
        if (player.world !in worlds || BotManager.isBot(player) || LobbyBuildService.isBuilding(player)) return
        val now = System.currentTimeMillis()
        if (now - (lastClicks[player.uniqueId] ?: 0) < 300) return
        lastClicks[player.uniqueId] = now
        val next = !(periods[player.uniqueId] ?: false)
        periods[player.uniqueId] = next
        player.sendMessage(Component.text("Leaderboard> ", BLUE).append(Component.text("You are now viewing ", GRAY))
            .append(Component.text("${if (next) "Most Kills" else "Most Wins"} — Super Smash Mobs", YELLOW)))
        player.playSound(player.location, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, .5f, 1f)
        update(player)
    }

    private fun refresh() {
        boards.keys.toList().filter { Bukkit.getPlayer(it)?.world !in worlds }.forEach(::remove)
        worlds.flatMap { it.players }.filterNot(BotManager::isBot).forEach(::update)
    }

    private fun update(player: Player) {
        val world = player.world
        val base = pedestals[world] ?: return
        val kills = periods[player.uniqueId] ?: false
        val standings = (if (kills) records?.kills() else records?.standings(WinPeriod.ALL_TIME, LocalDate.now(zone))).orEmpty()
        val lines = mutableListOf(
            Component.text("Super Smash Mobs", AQUA).decorate(BOLD),
            Component.text(if (kills) "Most Kills" else "Most Wins", GRAY), Component.empty())
        repeat(10) { index ->
            val row = standings.getOrNull(index)
            var line = Component.text("#${index + 1}", AQUA).append(Component.text(" - ", GRAY))
            line = if (row == null) line.append(Component.text("—", GRAY)) else line
                .append(rankedName(row.identity))
                .append(Component.text(" - ", GRAY)).append(Component.text(row.wins, YELLOW))
            lines.add(line)
        }
        val place = standings.indexOfFirst { it.identity.id == player.uniqueId.toString() }
        lines.add(Component.empty())
        lines.add(Component.text("You: ", GREEN).decorate(BOLD).append(Component.text(
            if (place < 0) "Unranked" else "#${place + 1} - ${standings[place].wins} ${if (kills) "kills" else "wins"}", GRAY).decoration(BOLD, false)))
        lines.add(Component.empty())
        lines.add(Component.text("Punch to view the", GOLD))
        lines.add(Component.text("next category", YELLOW))
        var displays = boards[player.uniqueId]
        if (displays == null || displays.any { !it.isValid || it.world != world }) {
            remove(player.uniqueId)
            displays = lines.indices.map { row ->
                world.spawn(base.clone().add(.5, 6.3 - row * .27, .5), TextDisplay::class.java) {
                    it.isVisibleByDefault = false; it.isPersistent = false
                    it.persistentDataContainer.set(key, PersistentDataType.BYTE, 1.toByte())
                    it.billboard = Display.Billboard.CENTER; it.brightness = Display.Brightness(15, 15)
                    it.isShadowed = true; it.backgroundColor = Color.fromARGB(0)
                    it.lineWidth = 600; it.viewRange = 1.5f
                    it.text(lines[row])
                }.also { player.showEntity(plugin, it) }
            }
            boards[player.uniqueId] = displays
        }
        displays.forEachIndexed { row, display -> if (display.text() != lines[row]) display.text(lines[row]) }
    }

    fun remove(id: UUID) { boards.remove(id)?.forEach(Entity::remove) }

    fun teardown() {
        task?.cancel(); task = null
        boards.keys.toList().forEach(::remove)
        interactions.forEach(Entity::remove); interactions.clear()
        pedestals.clear(); worlds.clear()
        periods.clear(); lastClicks.clear()
    }
}
