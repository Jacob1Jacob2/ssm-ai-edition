package dev.betrix.superSmashMobsBrawl.bots

import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World

/**
 * The walking graph of one arena, shared by every bot in the match and remembered between route
 * searches.
 *
 * Working out where a fighter can step or jump to from one block is the expensive part of finding
 * a route. Doing it once per block, instead of once per search per bot, is what lets a bot plan a
 * route across the whole map in a tick or two rather than standing still for seconds.
 *
 * The arena changes during a match (abilities and items break and place blocks). The graph does
 * not watch for that; instead a bot that finds a step missing or blocked calls [invalidate], and
 * that part of the graph is worked out again from the world as it now is.
 */
class ArenaNav private constructor(private val world: World, private val voidY: Int, private val doubleJump: Boolean) {
    private class Cached(val edges: List<RouteEdge>, val expires: Int)

    private var terrain = ArenaTerrain(world, voidY, doubleJump)
    private var terrainBorn = Bukkit.getCurrentTick()
    private val edges = HashMap<RouteNode, Cached>()
    private val warmSeen = HashSet<RouteNode>()
    private val warmQueue = ArrayDeque<RouteNode>()

    /** The block shapes behind the graph are reread now and then, so new work sees the current world. */
    private fun terrain(): ArenaTerrain {
        val now = Bukkit.getCurrentTick()
        if (now - terrainBorn > TERRAIN_TICKS) {
            terrain = ArenaTerrain(world, voidY, doubleJump)
            terrainBorn = now
        }
        return terrain
    }

    /** True when the last [neighbours] answer was worked out with part of the world not loaded. */
    private var incomplete = false

    fun neighbours(node: RouteNode): List<RouteEdge> {
        val now = Bukkit.getCurrentTick()
        incomplete = false
        edges[node]?.takeIf { it.expires > now }?.let { return it.edges }
        val terrain = terrain()
        terrain.sawUnloaded = false
        val fresh = terrain.neighbours(node)
        incomplete = terrain.sawUnloaded
        // An answer that ran into an unloaded chunk is good for this search only. Remembering
        // it would leave the graph ending at a chunk border long after the chunk arrived.
        // Expiry is staggered, so the whole graph is never recomputed in one tick.
        if (!incomplete) edges[node] = Cached(fresh, now + EDGE_TICKS + (node.hashCode() and 255))
        return fresh
    }

    /** Forgets what was worked out around a block that turned out to be missing or in the way. */
    fun invalidate(x: Int, z: Int, radius: Int = 7) {
        edges.keys.removeIf { abs(it.x - x) <= radius && abs(it.z - z) <= radius }
        terrain = ArenaTerrain(world, voidY, doubleJump)
        terrainBorn = Bukkit.getCurrentTick()
    }

    /**
     * Works out the graph outward from [seeds] for up to [nanos] nanoseconds, so that the first
     * route of a match does not have to. Call it each tick until [warmed].
     */
    fun warm(seeds: List<Location>, nanos: Long) {
        if (warmSeen.isEmpty()) seeds.mapNotNull { nearest(it, 5.0) }.forEach { if (warmSeen.add(it)) warmQueue.addLast(it) }
        val deadline = System.nanoTime() + nanos
        var waiting = 0
        while (warmQueue.isNotEmpty() && System.nanoTime() < deadline) {
            val node = warmQueue.removeFirst()
            val found = neighbours(node)
            if (incomplete) {
                // Its surroundings have not loaded yet: come back to it, and stop for this tick
                // once everything left is waiting on chunks.
                warmQueue.addLast(node)
                if (++waiting > warmQueue.size) break
                continue
            }
            for (edge in found) {
                if (warmSeen.size < WARM_LIMIT && warmSeen.add(edge.node)) warmQueue.addLast(edge.node)
            }
        }
    }
    val warmed: Boolean get() = warmSeen.isNotEmpty() && warmQueue.isEmpty()

    /**
     * The standable block at or beside [loc]. A fighter hugging the lip of a block has its centre
     * over thin air; the block it is really standing on is the one next to that column.
     * [up] is how far above [loc] a surface may be and still count; keep it small for a fighter
     * in the air, who is about to land on what is below it, not on the ledge it is falling past.
     */
    fun nearest(loc: Location, down: Double, up: Double = 1.25): RouteNode? {
        val terrain = terrain()
        val x = floor(loc.x).toInt()
        val z = floor(loc.z).toInt()
        terrain.surface(x, z, loc.y, up, down)?.let { return it }
        var best: RouteNode? = null
        var bestScore = Double.MAX_VALUE
        for (dx in -2..2) for (dz in -2..2) {
            if (dx == 0 && dz == 0) continue
            val node = terrain.surface(x + dx, z + dz, loc.y, up, down) ?: continue
            val score = hypot(node.x + .5 - loc.x, node.z + .5 - loc.z) + abs(node.y - loc.y) * 0.5
            if (score < bestScore) { bestScore = score; best = node }
        }
        return best
    }

    companion object {
        private const val TERRAIN_TICKS = 100
        private const val EDGE_TICKS = 1200
        private const val WARM_LIMIT = 20_000
        private val navs = HashMap<UUID, HashMap<Boolean, ArenaNav>>()

        fun of(world: World, voidY: Int, doubleJump: Boolean): ArenaNav {
            val perWorld = navs.getOrPut(world.uid) {
                // A new arena is a good moment to forget the ones that have been unloaded.
                navs.keys.removeIf { Bukkit.getWorld(it) == null }
                HashMap()
            }
            return perWorld.getOrPut(doubleJump) { ArenaNav(world, voidY, doubleJump) }
        }
    }
}
