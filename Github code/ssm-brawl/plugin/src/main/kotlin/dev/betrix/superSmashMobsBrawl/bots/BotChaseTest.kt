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
import kotlin.math.roundToInt
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
 * Spots are "planned" (the route planner can find a way there with plain walking and jumping),
 * "leap" (planned, but only by way of the bigger moves: a long drop, a jump at an angle, a wall
 * climb, a chicken's flight) or "unplanned" (a double-jumping player could get there, but the
 * planner knows no route), to separate bad plans from bad execution.
 *
 * The map may be given as "ruins@big" (or "@small") to test the 2x bigger or smaller arena.
 */
object BotChaseTest : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val minigameService: MinigameService by inject()
    private val dataService: DataService by inject()

    /** [start] is where the chaser sets off from: a spawn point the spot can actually be got to from, when there is one. */
    private class Spot(val x: Double, val y: Double, val z: Double, val kind: String, val start: Location? = null)
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
        var firstReach: Int? = null
        val deathTicks = ArrayList<Int>()
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
    private var kitId = ""
    private var waited = 0
    /** The hider holds its spot and fights back, and the spot runs its full time: how often does the chaser die there? */
    private var ambush = false

    val running: Boolean get() = task != null

    private fun log(message: String) = plugin.logger.info("[chase] $message")

    fun start(mapId: String, kit: String, difficulty: BotDifficulty, pairCount: Int, samples: Int, seconds: Int,
              seed: Int, spot: Triple<Double, Double, Double>? = null, hiderKit: String = "skeleton", ambush: Boolean = false): String {
        if (running) return "A chase test is already running."
        val def = dataService.getMinigame("super_smash_mobs") ?: return "Minigame super_smash_mobs is missing."
        if (dataService.getKit(kit) == null) return "Unknown kit '$kit'."
        if (dataService.getKit(hiderKit) == null) return "Unknown kit '$hiderKit'."
        val map = mapId.substringBefore('@')
        // Nothing falling from the sky to wander off after: the test is about getting to the spot.
        // (The shape the map is played in is whatever follows its "@": see BotNavGen.shapeOf.)
        val setup = BotNavGen.shapeOf(mapId).apply(dev.betrix.superSmashMobsBrawl.services.GameSetupService.Setup(difficulty = difficulty, players = 2,
            items = false, crystals = false))
        val started = LobbyRouting.ids.take(pairCount.coerceIn(1, 5)).mapNotNull { lobby ->
            val bots = BotManager.startBotGame(def, 2, difficulty, map, listOf(kit, hiderKit), lobby, setup.copy())
            if (bots.size < 2) { bots.forEach(BotManager::despawn); null }
            else Chase(bots[0], bots[1]).also { BotManager.brainOf(bots[1])?.let { hider -> if (ambush) hider.rooted = true else hider.passive = true } }
        }
        if (started.isEmpty()) return "Could not spawn any bot pairs."
        pairs = started
        plan = null
        explicit = spot?.let { listOf(Spot(it.first, it.second, it.third, "explicit")) }
        this.ambush = ambush
        limitTicks = seconds.coerceIn(5, if (ambush) 900 else 120) * 20
        perPair = if (spot != null) 1 else samples.coerceIn(1, 200)
        this.seed = seed
        kitId = kit
        label = "map=$mapId kit=$kit diff=${difficulty.name.lowercase()}"
        waited = 0
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
        return "Chase test started: ${started.size} pairs, $perPair spots each, ${limitTicks / 20}s per spot."
    }

    private fun tick() {
        try {
            pairs.forEach(::step)
            if (pairs.any { it.game == null && !it.done } && ++waited > 1200) {
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
            val spawns = world.data.spawnPoints.map { Location(world.world, it.x, it.y, it.z) }
            val jumps = ArenaJumps.of(world.world, world.data.voidLevel.toInt(), world.data, Mobility.of(kitId, true))
            if (explicit == null && plan == null && !jumps.complete) {
                // The survey reads every long jump of the arena: have them all worked out first
                // (a slice each tick until they are, on every thread there is).
                jumps.workTogether(spawns, BotNavGen.pool)
                if (!jumps.complete) return
            }
            pair.game = found
            val all = explicit ?: plan ?: survey(world.world, world.data.voidLevel.toInt(), spawns, jumps).also { plan = it }
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
        if (dummy.location.distanceSquared(at) > 0.01) {
            // Put back facing the way it was facing: a hider that fights back keeps its aim.
            dummy.teleport(at.clone().also { it.yaw = dummy.location.yaw; it.pitch = dummy.location.pitch })
            dummy.velocity = org.bukkit.util.Vector()
        }
        val respawning = game.respawnManager.isRespawning(chaser)
        if (respawning && !pair.wasRespawning) {
            pair.deaths++
            pair.deathTicks += pair.ticks
            log("DEATH chaser ${chaser.name} going for ${fmt(spot.x, spot.y, spot.z)} after ${pair.ticks} ticks, last on ground at " +
                "${pair.lastGround?.let { fmt(it.x, it.y, it.z) }}")
        }
        pair.wasRespawning = respawning
        if (chaser.isOnGround && !respawning) pair.lastGround = chaser.location
        pair.ticks++
        val close = !respawning && chaser.world == at.world && chaser.location.distance(at) <= 3.3 && chaser.hasLineOfSight(dummy)
        if (close && pair.firstReach == null) pair.firstReach = pair.ticks
        // An ambush runs its full time: the question is how often the chaser dies, not how soon it arrives.
        val reached = close && !ambush
        if (!reached && pair.ticks < limitTicks) return
        val here = chaser.location
        val detail = if (reached) "" else
            "end=${fmt(here.x, here.y, here.z)} dist=${"%.1f".format(here.distance(at))} ${BotManager.brainOf(chaser)?.debugSummary()}"
        pair.results += Result(spot, pair.ticks.takeIf { reached }, pair.hitTick, pair.deaths, detail)
        log("RESULT kind=${spot.kind} spot=${fmt(spot.x, spot.y, spot.z)} start=${pair.start?.let { fmt(it.x, it.y, it.z) }} " +
            "ok=$reached ticks=${pair.ticks} hit=${pair.hitTick} deaths=${pair.deaths} " +
            (if (ambush) "diedAt=${pair.deathTicks.joinToString(",")} firstReach=${pair.firstReach} " else "") + detail)
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
        // (Every pair plays in its own copy of the arena: the same coordinates, in this pair's world.)
        val start = spot.start?.let { Location(world.world, it.x, it.y, it.z) }
            ?: world.data.spawnPoints.map { Location(world.world, it.x, it.y, it.z) }.maxByOrNull { it.distanceSquared(at) } ?: at
        game.teams.forEach { it.metadata["stocks"] = if (ambush) 50 else 4 }
        pair.dummy.teleport(at)
        pair.chaser.teleport(start)
        pair.chaser.velocity = org.bukkit.util.Vector()
        pair.chaser.health = pair.chaser.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        BotManager.brainOf(pair.chaser)?.resetNavigation()
        pair.start = start
        pair.ticks = 0
        pair.hitTick = null
        pair.deaths = 0
        pair.deathTicks.clear()
        pair.firstReach = null
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
    private fun survey(world: World, voidY: Int, spawns: List<Location>, jumps: ArenaJumps): List<Spot> {
        /**
         * Everything a graph gets to, and what it gets to from each spawn point on its own. A
         * route is one-way in places (a long drop does not go back up), and some arenas have a
         * spawn on a part the rest cannot get to: a spot is only a fair test of the chaser if it
         * starts somewhere the spot can be reached from.
         */
        fun graph(of: ArenaTerrain, long: ArenaJumps? = null, strike: Boolean = false): Pair<LinkedHashSet<RouteNode>, List<Set<RouteNode>>> {
            val edges = HashMap<RouteNode, List<RouteNode>>()
            val all = LinkedHashSet<RouteNode>()
            val each = spawns.map { spawn ->
                val root = of.at(spawn) ?: return@map emptySet<RouteNode>()
                val seen = LinkedHashSet<RouteNode>()
                seen += root
                val todo = ArrayDeque<RouteNode>()
                todo += root
                while (todo.isNotEmpty() && seen.size < 60_000) {
                    val at = todo.removeFirst()
                    for (n in edges.getOrPut(at) {
                        val base = of.neighbours(at)
                        (if (long == null) base else ArenaJumps.merge(base, long.now(at), strike)).map { it.node }
                    }) if (seen.add(n)) todo.addLast(n)
                }
                all += seen
                seen
            }
            return all to each
        }
        // What walking, hopping and a plain double jump get to, and what the chaser's kit gets to.
        var began = System.nanoTime()
        val (plain, plainFrom) = graph(ArenaTerrain(world, voidY, Mobility.JUMPER, leaping = false, loadChunks = true))
        val plainMs = (System.nanoTime() - began) / 1_000_000
        began = System.nanoTime()
        val mobility = Mobility.of(kitId, true)
        val terrain = ArenaTerrain(world, voidY, mobility, loadChunks = true)
        // The chaser's own graph: the rules, and the long jumps flown in the head for this arena.
        val (planned, plannedFrom) = graph(terrain, jumps.takeIf { mobility.airborne }, mobility.jumps.flown)
        val plannedMs = (System.nanoTime() - began) / 1_000_000
        val queue = ArrayDeque<RouteNode>()

        val top = (spawns.maxOfOrNull { it.y } ?: 100.0) + 80
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
        val leap = planned.filter { it !in plain }
        log("SURVEY $label planned=${planned.size} reachableByPlayers=${reachable.size} unplanned=${unplanned.size} leap=${leap.size} graphMs=$plainMs/$plannedMs " +
            "longJumps=${jumps.jumps} struck=${jumps.struck} from ${jumps.size} blocks${if (jumps.loaded) " (from disk)" else ", ${jumps.millis} ms"}")
        val random = Random(seed)
        // Why the planner has no way to what is left: the easiest hop a player could make there.
        if (unplanned.isNotEmpty()) {
            val why = HashMap<String, Int>()
            val example = HashMap<String, RouteNode>()
            for (b in unplanned.shuffled(Random(7)).take(250)) {
                var best: RouteNode? = null
                var bestDist = Double.MAX_VALUE
                for (dx in -7..7) for (dz in -7..7) {
                    val dist = hypot(dx.toDouble(), dz.toDouble())
                    if ((dx == 0 && dz == 0) || dist > 7.0 || dist >= bestDist) continue
                    for (a in column(b.x + dx, b.z + dz)) {
                        if (a !in planned || b.y > a.y + 4.5 || b.y < a.y - 40) continue
                        if (arc(a, b)) { best = a; bestDist = dist; break }
                    }
                }
                val from = best
                val key = if (from == null) "chain" else {
                    val dy = b.y - from.y
                    val across = when { bestDist <= 1.5 -> "beside"; bestDist <= 5.0 -> "to5"; bestDist <= 6.0 -> "to6"; else -> "to7" }
                    val height = when { dy < -3 -> "drop"; dy <= 1.25 -> "level"; dy <= 4.0 -> "up"; else -> "up4+" }
                    "$across/$height"
                }
                why.merge(key, 1, Int::plus)
                if (example.putIfAbsent(key, b) == null && from != null) {
                    // The hop itself, and what stands in the columns from one end of it to the other.
                    val n = max(abs(b.x - from.x), abs(b.z - from.z)).coerceAtLeast(1)
                    log("SURVEY-HOP $key from ${from.x},${"%.2f".format(from.y)},${from.z} to ${b.x},${"%.2f".format(b.y)},${b.z} edges=" +
                        terrain.neighbours(from).joinToString(";") { "${it.node.x},${"%.1f".format(it.node.y)},${it.node.z}${if (it.jump) "j" else ""}" })
                    for (i in 0..n) {
                        val x = from.x + ((b.x - from.x) * i.toDouble() / n).roundToInt()
                        val z = from.z + ((b.z - from.z) * i.toDouble() / n).roundToInt()
                        log("SURVEY-COL $x,$z: ${terrain.describe(x, z)}")
                    }
                }
            }
            log("SURVEY-WHY $label " + why.entries.sortedByDescending { it.value }.joinToString(" ") {
                "${it.key}=${it.value}@${example[it.key]?.let { n -> "${n.x},${"%.1f".format(n.y)},${n.z}" }}" })
        }
        // Everywhere else there is to stand: islands that neither the planner nor a string of
        // ordinary hops gets to. Most are scenery nobody can get onto. The ones worth a look are
        // those a single jump away, by the physics, from ground the planner does know.
        run {
            val minX = planned.minOf { it.x } - 12
            val maxX = planned.maxOf { it.x } + 12
            val minZ = planned.minOf { it.z } - 12
            val maxZ = planned.maxOf { it.z } + 12
            if ((maxX - minX).toLong() * (maxZ - minZ) > 250_000) return@run
            val loose = LinkedHashSet<RouteNode>()
            for (x in minX..maxX) for (z in minZ..maxZ) for (n in column(x, z)) if (n !in reachable) loose += n
            val counted = HashSet<RouteNode>()
            val reach = JumpSim.reachOf(mobility.jumps.launch)
            var islands = 0
            var within = 0
            for (first in loose) {
                if (!counted.add(first)) continue
                val island = ArrayList<RouteNode>()
                val todo = ArrayDeque<RouteNode>()
                todo += first
                while (todo.isNotEmpty()) {
                    val n = todo.removeFirst()
                    island += n
                    for (dx in -1..1) for (dz in -1..1) for (m in column(n.x + dx, n.z + dz))
                        if (abs(m.y - n.y) <= 1.25 && m in loose && counted.add(m)) todo.addLast(m)
                }
                if (island.size < 12) continue
                // The easiest jump onto it: the one with the most distance to spare in open air.
                var from: RouteNode? = null
                var onto: RouteNode? = null
                var spare = -Double.MAX_VALUE
                for (t in island.shuffled(Random(11)).take(300)) for (dx in -12..12) for (dz in -12..12) {
                    val across = hypot(dx.toDouble(), dz.toDouble())
                    if (across < 1.0 || across > 12.0) continue
                    for (p in column(t.x + dx, t.z + dz)) {
                        if (p !in planned) continue
                        val s = reach.at(t.y - p.y) - across
                        if (s > spare) { spare = s; from = p; onto = t }
                    }
                }
                islands++
                if (spare >= 0) within++
                val a = from
                val b = onto
                log("SURVEY-ISLAND $label size=${island.size} at ${first.x},${"%.1f".format(first.y)},${first.z}: " +
                    if (a == null || b == null) "nothing the planner knows within 12 blocks"
                    else "easiest jump from ${a.x},${"%.1f".format(a.y)},${a.z} to ${b.x},${"%.1f".format(b.y)},${b.z} " +
                        "(${"%.1f".format(hypot((b.x - a.x).toDouble(), (b.z - a.z).toDouble()))} across, ${"%.1f".format(b.y - a.y)} up, " +
                        "${"%.1f".format(spare)} to spare in open air)")
            }
            log("SURVEY-ISLANDS $label $islands of 12 blocks or more, $within within a jump in open air")
        }
        val total = perPair * pairs.size
        val wantUnplanned = if (unplanned.isEmpty()) 0 else total / 4
        val wantLeap = if (leap.isEmpty()) 0 else total / 4
        fun spots(from: List<RouteNode>, count: Int, kind: String) = from.shuffled(random).take(count).map { n ->
            // One in three stands at the lip of its block, the way a hiding player hugs an edge.
            val (ox, oz) = if (random.nextInt(3) == 0) listOf(0.58 to 0.0, -0.58 to 0.0, 0.0 to 0.58, 0.0 to -0.58).random(random) else 0.0 to 0.0
            val x = n.x + .5 + ox
            val z = n.z + .5 + oz
            // The far side of the map, among the spawn points this spot can be got to from.
            val reach = when (kind) { "planned" -> plainFrom; "leap" -> plannedFrom; else -> null }
            val start = reach?.let { sets -> spawns.filterIndexed { i, _ -> n in sets[i] }.maxByOrNull { hypot(it.x - x, it.z - z) } }
            if (abs(ox) + abs(oz) > 0 && !terrain.clear(x, n.y, z)) Spot(n.x + .5, n.y, n.z + .5, kind, start) else Spot(x, n.y, z, kind, start)
        }
        return (spots(planned.filter { it in plain }, total - wantUnplanned - wantLeap, "planned") + spots(leap, wantLeap, "leap") +
            spots(unplanned, wantUnplanned, "unplanned")).shuffled(random)
    }
}
