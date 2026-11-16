package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.minigames.MinigameState
import dev.betrix.superSmashMobsBrawl.services.DataService
import dev.betrix.superSmashMobsBrawl.services.LobbyRouting
import dev.betrix.superSmashMobsBrawl.services.MinigameService
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Debug harness (/bots chasetest): can a bot actually get to an opponent standing still somewhere?
 *
 * Each pair is its own 1v1 match: a passive "hider" bot is pinned to one spot after another, and
 * a normal bot starts from the far side of the map and has to reach it. Every spot is logged as a
 * RESULT line, so stuck bots show up as numbers instead of anecdotes.
 *
 * Spots are "planned" (the route planner can find a way there) or "unplanned" (a double-jumping
 * player could get there, but the planner knows no route), to separate bad plans from bad execution.
 */
object BotChaseTest : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val minigameService: MinigameService by inject()
    private val dataService: DataService by inject()

    private class Spot(val x: Double, val y: Double, val z: Double, val kind: String)
    private class Result(val spot: Spot, val ticks: Int?, val hitTick: Int?, val deaths: Int, val detail: String)
    private class Chase(val chaser: Player, val dummy: Player) {
        var game: BrawlMinigame? = null
        var spots: List<Spot> = emptyList()
        var index = -1
        var ticks = 0
        var hitTick: Int? = null
        var deaths = 0
        var wasRespawning = false
        var done = false
        var start: Location? = null
        var lastGround: Location? = null
        val results = ArrayList<Result>()
    }

    private var task: BukkitTask? = null
    private var pairs: List<Chase> = emptyList()
    private var plan: List<Spot>? = null
    private var explicit: List<Spot>? = null
    private var limitTicks = 500
    private var perPair = 8
    private var seed = 1
    private var label = ""
    private var waited = 0

    val running: Boolean get() = task != null

    private fun log(message: String) = plugin.logger.info("[chase] $message")

    fun start(mapId: String, kit: String, difficulty: BotDifficulty, pairCount: Int, samples: Int, seconds: Int,
              seed: Int, spot: Triple<Double, Double, Double>? = null): String {
        if (running) return "A chase test is already running."
        val def = dataService.getMinigame("super_smash_mobs") ?: return "Minigame super_smash_mobs is missing."
        if (dataService.getKit(kit) == null) return "Unknown kit '$kit'."
        val started = LobbyRouting.ids.take(pairCount.coerceIn(1, 5)).mapNotNull { lobby ->
            val bots = BotManager.startBotGame(def, 2, difficulty, mapId, listOf(kit, "skeleton"), lobby)
            if (bots.size < 2) { bots.forEach(BotManager::despawn); null }
            else Chase(bots[0], bots[1]).also { BotManager.brainOf(bots[1])?.passive = true }
        }
        if (started.isEmpty()) return "Could not spawn any bot pairs."
        pairs = started
        plan = null
        explicit = spot?.let { listOf(Spot(it.first, it.second, it.third, "explicit")) }
        limitTicks = seconds.coerceIn(5, 120) * 20
        perPair = if (spot != null) 1 else samples.coerceIn(1, 200)
        this.seed = seed
        label = "map=$mapId kit=$kit diff=${difficulty.name.lowercase()}"
        waited = 0
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
        return "Chase test started: ${started.size} pairs, $perPair spots each, ${limitTicks / 20}s per spot."
    }

    private fun tick() {
        try {
            pairs.forEach(::step)
            if (pairs.any { it.game == null } && ++waited > 1200) {
                log("ABORT matches did not start within 60s")
                pairs.filter { it.game == null }.forEach { it.done = true }
            }
            if (pairs.all { it.done }) finish()
        } catch (e: Exception) {
            log("ABORT ${e.stackTraceToString()}")
            finish()
        }
    }

    private fun step(pair: Chase) {
        if (pair.done) return
        val game = pair.game ?: run {
            val found = minigameService.getMinigameForPlayer(pair.chaser)?.takeIf { it.getState() == MinigameState.IN_PROGRESS } ?: return
            val world = found.worldManager.getWorld() ?: return
            pair.game = found
            val all = explicit ?: plan ?: survey(world.world, world.data.voidLevel.toInt(), world.data.spawnPoints.map { Location(world.world, it.x, it.y, it.z) })
                .also { plan = it }
            val mine = pairs.indexOf(pair)
            pair.spots = if (explicit != null) all else all.filterIndexed { i, _ -> i % pairs.size == mine }
            next(pair, found)
            return
        }
        if (minigameService.getMinigameForPlayer(pair.chaser) !== game || game.getState() != MinigameState.IN_PROGRESS) {
            log("ABORT pair ${pairs.indexOf(pair)}: its match ended")
            pair.done = true
            return
        }
        val spot = pair.spots[pair.index]
        val at = Location(pair.dummy.world, spot.x, spot.y, spot.z)
        val dummy = pair.dummy
        val chaser = pair.chaser
        val maxHealth = dummy.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        if (dummy.health < maxHealth - 0.01 && pair.hitTick == null) pair.hitTick = pair.ticks
        if (!dummy.isDead) dummy.health = maxHealth
        if (dummy.location.distanceSquared(at) > 0.01) { dummy.teleport(at); dummy.velocity = org.bukkit.util.Vector() }
        val respawning = game.respawnManager.isRespawning(chaser)
        if (respawning && !pair.wasRespawning) {
            pair.deaths++
            log("DEATH chaser ${chaser.name} going for ${fmt(spot.x, spot.y, spot.z)} after ${pair.ticks} ticks, last on ground at " +
                "${pair.lastGround?.let { fmt(it.x, it.y, it.z) }}")
        }
        pair.wasRespawning = respawning
        if (chaser.isOnGround && !respawning) pair.lastGround = chaser.location
        pair.ticks++
        val reached = !respawning && chaser.world == at.world && chaser.location.distance(at) <= 3.3 && chaser.hasLineOfSight(dummy)
        if (!reached && pair.ticks < limitTicks) return
        val here = chaser.location
        val detail = if (reached) "" else
            "end=${fmt(here.x, here.y, here.z)} dist=${"%.1f".format(here.distance(at))} ${BotManager.brainOf(chaser)?.debugSummary()}"
        pair.results += Result(spot, pair.ticks.takeIf { reached }, pair.hitTick, pair.deaths, detail)
        log("RESULT kind=${spot.kind} spot=${fmt(spot.x, spot.y, spot.z)} start=${pair.start?.let { fmt(it.x, it.y, it.z) }} " +
            "ok=$reached ticks=${pair.ticks} hit=${pair.hitTick} deaths=${pair.deaths} $detail")
        next(pair, game)
    }

    private fun next(pair: Chase, game: BrawlMinigame) {
        pair.index++
        if (pair.index >= pair.spots.size) {
            pair.done = true
            // Its match runs on until every pair is finished: stand the chaser down, or it
            // spends that time killing a hider that is no longer held in place.
            BotManager.brainOf(pair.chaser)?.passive = true
            return
        }
        val spot = pair.spots[pair.index]
        val world = game.worldManager.getWorld() ?: run { pair.done = true; return }
        val at = Location(world.world, spot.x, spot.y, spot.z)
        // Start from the spawn point furthest away, so every chase crosses the map.
        val start = world.data.spawnPoints.map { Location(world.world, it.x, it.y, it.z) }.maxByOrNull { it.distanceSquared(at) } ?: at
        game.teams.forEach { it.metadata["stocks"] = 4 }
        pair.dummy.teleport(at)
        pair.chaser.teleport(start)
        pair.chaser.velocity = org.bukkit.util.Vector()
        pair.chaser.health = pair.chaser.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        BotManager.brainOf(pair.chaser)?.resetNavigation()
        pair.start = start
        pair.ticks = 0
        pair.hitTick = null
        pair.deaths = 0
        pair.wasRespawning = false
    }

    private fun finish() {
        task?.cancel()
        task = null
        val results = pairs.flatMap { it.results }
        for (kind in results.map { it.spot.kind }.distinct()) {
            val of = results.filter { it.spot.kind == kind }
            val ok = of.mapNotNull { it.ticks }.sorted()
            log("SUMMARY $label kind=$kind reached=${ok.size}/${of.size} medianTicks=${ok.getOrNull(ok.size / 2)} " +
                "hitWithoutReaching=${of.count { it.ticks == null && it.hitTick != null }} deaths=${of.sumOf { it.deaths }}")
        }
        log("DONE $label")
        val games = pairs.mapNotNull { it.game }.distinct()
        val bots = pairs.flatMap { listOf(it.chaser, it.dummy) }
        pairs = emptyList()
        games.forEach { if (it.getState() == MinigameState.IN_PROGRESS) it.endMinigame("chase_test") }
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { bots.forEach(BotManager::despawn) }, 40L)
    }

    private fun fmt(x: Double, y: Double, z: Double) = "%.1f,%.1f,%.1f".format(x, y, z)

    // ------------------------------------------------------------------ choosing spots

    /** Everywhere a fighter could stand: half the sample from planned cells, half from unplanned. */
    private fun survey(world: World, voidY: Int, spawns: List<Location>): List<Spot> {
        val terrain = ArenaTerrain(world, voidY, true)
        val roots = spawns.mapNotNull { terrain.at(it) }
        val planned = LinkedHashSet<RouteNode>(roots)
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty() && planned.size < 40_000) {
            for (edge in terrain.neighbours(queue.removeFirst())) if (planned.add(edge.node)) queue.addLast(edge.node)
        }
        val top = (spawns.maxOfOrNull { it.y } ?: 100.0) + 40
        val columns = HashMap<Pair<Int, Int>, List<RouteNode>>()
        fun column(x: Int, z: Int): List<RouteNode> = columns.getOrPut(x to z) {
            val found = ArrayList<RouteNode>()
            var y = top
            while (y > voidY) {
                val s = terrain.surface(x, z, y, 0.0, y - voidY) ?: break
                found += s
                y = s.y - 1.0
            }
            found
        }
        fun arc(a: RouteNode, b: RouteNode): Boolean {
            val apex = max(a.y, b.y) + 1.3
            var y = a.y + 0.5
            while (y <= apex) { if (!terrain.clear(a.x + .5, y, a.z + .5)) return false; y += 0.5 }
            val steps = ceil(hypot((b.x - a.x).toDouble(), (b.z - a.z).toDouble()) * 2).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val t = i.toDouble() / steps
                if (!terrain.clear(a.x + .5 + (b.x - a.x) * t, apex, a.z + .5 + (b.z - a.z) * t)) return false
            }
            y = apex
            while (y > b.y + 0.05) { if (!terrain.clear(b.x + .5, y, b.z + .5)) return false; y -= 0.5 }
            return true
        }
        // What a double-jumping player can get to: seven blocks across, four and a half up, any drop.
        val reachable = LinkedHashSet<RouteNode>(planned)
        queue.addAll(planned)
        while (queue.isNotEmpty() && reachable.size < 80_000) {
            val a = queue.removeFirst()
            for (dx in -7..7) for (dz in -7..7) {
                if ((dx == 0 && dz == 0) || dx * dx + dz * dz > 49) continue
                for (b in column(a.x + dx, a.z + dz)) {
                    if (b.y > a.y + 4.5 || b.y < a.y - 40 || b in reachable) continue
                    if (arc(a, b)) { reachable.add(b); queue.addLast(b) }
                }
            }
        }
        val unplanned = reachable.filter { it !in planned }
        log("SURVEY $label planned=${planned.size} reachableByPlayers=${reachable.size} unplanned=${unplanned.size}")
        val random = Random(seed)
        val total = perPair * pairs.size
        val wantUnplanned = if (unplanned.isEmpty()) 0 else total / 4
        fun spots(from: List<RouteNode>, count: Int, kind: String) = from.shuffled(random).take(count).map { n ->
            // One in three stands at the lip of its block, the way a hiding player hugs an edge.
            val (ox, oz) = if (random.nextInt(3) == 0) listOf(0.58 to 0.0, -0.58 to 0.0, 0.0 to 0.58, 0.0 to -0.58).random(random) else 0.0 to 0.0
            val x = n.x + .5 + ox
            val z = n.z + .5 + oz
            if (abs(ox) + abs(oz) > 0 && !terrain.clear(x, n.y, z)) Spot(n.x + .5, n.y, n.z + .5, kind) else Spot(x, n.y, z, kind)
        }
        return (spots(planned.toList(), total - wantUnplanned, "planned") + spots(unplanned, wantUnplanned, "unplanned")).shuffled(random)
    }
}
