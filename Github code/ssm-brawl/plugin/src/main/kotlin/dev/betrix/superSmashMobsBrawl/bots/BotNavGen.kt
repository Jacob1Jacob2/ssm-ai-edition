package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.minigames.MinigameState
import dev.betrix.superSmashMobsBrawl.services.DataService
import dev.betrix.superSmashMobsBrawl.services.GameSetupService
import dev.betrix.superSmashMobsBrawl.services.LobbyRouting
import dev.betrix.superSmashMobsBrawl.services.LobbyService
import dev.betrix.superSmashMobsBrawl.services.MapScale
import dev.betrix.superSmashMobsBrawl.services.MinigameService
import dev.betrix.superSmashMobsBrawl.services.ReflectMode
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Debug harness (/bots navgen): works out the long jumps of every map ahead of time.
 *
 * A map's long jumps are worked out the first time it is played in a given shape and kept on disk
 * ([ArenaJumps]). This plays an empty two-bot match on each map in turn, does all of that work at
 * once and saves it, so that no real match ever has to wait for it. The files it leaves in
 * plugins/<plugin>/nav are what gets copied to a server.
 *
 * A map is given as "aviation", or with the shape it is played in: "aviation@small", "ruins@big",
 * "desert@rev" (reversed), "desert@left" / "@right" (reflected), or several ("aviation@small+rev").
 */
object BotNavGen : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val minigameService: MinigameService by inject()
    private val dataService: DataService by inject()

    /** The shape a map is played in, as written after the "@" of a map name. */
    class Shape(val scale: MapScale, val reverse: Boolean, val reflect: ReflectMode) {
        fun apply(setup: GameSetupService.Setup) = setup.also {
            it.mapScale = scale
            it.reverseMap = reverse
            it.reverseRandom = false
            it.reflectMode = reflect
        }
    }

    fun shapeOf(map: String): Shape {
        val words = map.substringAfter('@', "").lowercase().split('+', ',').filter { it.isNotEmpty() }
        return Shape(
            when { "big" in words || "2x" in words -> MapScale.BIG; "small" in words -> MapScale.SMALL; else -> MapScale.NORMAL },
            "rev" in words || "reverse" in words || "reversed" in words,
            when { "left" in words -> ReflectMode.LEFT; "right" in words -> ReflectMode.RIGHT; else -> ReflectMode.OFF })
    }

    private var task: BukkitTask? = null
    private val todo = ArrayDeque<String>()
    private var current: String? = null
    private var bots: List<Player> = emptyList()
    private var game: BrawlMinigame? = null
    private var jumps: ArenaJumps? = null
    private var seeds: List<Location> = emptyList()
    private var waited = 0
    private var rest = 0
    private var began = 0L
    private var done = 0
    private var failed = ArrayList<String>()

    val running: Boolean get() = task != null

    private fun log(message: String) = plugin.logger.info("[navgen] $message")

    /** [maps] are map names as [shapeOf] reads them; "all" is every map, and "all@small" every map in that shape. */
    fun start(maps: List<String>): String {
        if (running) return "The maps are already being worked out."
        if (BotChaseTest.running) return "A chase test is running."
        val every = dataService.getAllGameMaps().map { it.id }.sorted()
        todo.clear()
        for (spec in maps) {
            val id = spec.substringBefore('@')
            val shape = spec.substringAfter('@', "").let { if (it.isEmpty()) "" else "@$it" }
            if (id == "all") todo.addAll(every.map { it + shape })
            else if (dataService.getGameMap(id) != null) todo.addLast(spec)
            else return "Unknown map '$id'."
        }
        if (todo.isEmpty()) return "No maps given."
        done = 0
        failed = ArrayList()
        rest = 0
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
        return "Working out ${todo.size} maps."
    }

    private fun tick() {
        try {
            if (rest > 0) { rest--; return }
            val spec = current ?: run {
                val next = todo.removeFirstOrNull() ?: return finish()
                val def = dataService.getMinigame("super_smash_mobs") ?: return finish()
                val lobby = LobbyRouting.ids.firstOrNull { !LobbyService.busy(it) }
                if (lobby == null) { todo.addFirst(next); rest = 20; return }
                val setup = shapeOf(next).apply(GameSetupService.Setup(difficulty = BotDifficulty.NORMAL, players = 2, items = false, crystals = false))
                val started = BotManager.startBotGame(def, 2, BotDifficulty.NORMAL, next.substringBefore('@'), listOf("creeper", "skeleton"), lobby, setup)
                if (started.size < 2) {
                    started.forEach(BotManager::despawn)
                    log("FAILED $next: could not start a match")
                    failed += next
                    rest = 40
                    return
                }
                // Nobody fights: the match is only there for its arena.
                started.forEach { BotManager.brainOf(it)?.passive = true }
                bots = started
                current = next
                game = null
                jumps = null
                waited = 0
                return
            }
            val match = game ?: run {
                val found = bots.firstOrNull()?.let { minigameService.getMinigameForPlayer(it) }?.takeIf { it.getState() == MinigameState.IN_PROGRESS }
                if (found == null) {
                    if (++waited > 1200) { log("FAILED $spec: its match did not start within 60s"); failed += spec; close() }
                    return
                }
                val world = found.worldManager.getWorld() ?: return
                game = found
                seeds = world.data.spawnPoints.map { Location(world.world, it.x, it.y, it.z) }
                jumps = ArenaJumps.of(world.world, world.data.voidLevel.toInt(), world.data)
                began = System.nanoTime()
                return
            }
            val store = jumps ?: return close()
            if (match.getState() != MinigameState.IN_PROGRESS) { log("FAILED $spec: its match ended"); failed += spec; return close() }
            // A good part of each tick, and never so long that the server looks hung.
            if (!store.complete) store.work(WORK_NANOS, seeds, load = true)
            if (!store.complete) return
            log("DONE $spec: ${store.size} blocks, ${store.jumps} long jumps" +
                (if (store.loaded) " (already on disk)" else ", ${(System.nanoTime() - began) / 1_000_000} ms") +
                " -> ${match.worldManager.getWorld()?.data?.let { ArenaJumps.keyOf(it) }}.jumps")
            done++
            close()
        } catch (e: Exception) {
            log("ABORT ${e.stackTraceToString()}")
            close()
            todo.clear()
        }
    }

    /** Ends the match of the map just done, and gives its lobby a moment to come free. */
    private fun close() {
        val match = game
        val old = bots
        current = null
        game = null
        jumps = null
        bots = emptyList()
        rest = 80
        if (match != null && match.getState() == MinigameState.IN_PROGRESS) match.endMinigame("nav_gen")
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { old.forEach(BotManager::despawn) }, 40L)
    }

    private fun finish() {
        task?.cancel()
        task = null
        log("FINISHED $done maps worked out" + (if (failed.isEmpty()) "" else ", failed: ${failed.joinToString(" ")}"))
    }

    private const val WORK_NANOS = 40_000_000L
}
