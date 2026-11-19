package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.models.brawlData.GameMapDef
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.abs
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World

/**
 * Every long jump of one arena for one kind of fighter: for each block at the rim of something,
 * the landings its air jump really reaches from it ([ArenaTerrain.longJumps]). A slime's bounce
 * and a wolf's pounce are not a standard double jump, so each kind ([Mobility.home]) has its own.
 *
 * Working one block out means flying a few dozen jumps in the head, far too much to do in the
 * middle of a route search. So it is done here, a few milliseconds a tick, outward from the spawn
 * points until the whole arena is known, with whatever a search has just asked for first. A search
 * that meets a block not worked out yet says so ([want]) and carries on without; the bot looks
 * again a moment later, by which time it is.
 *
 * An arena is the same every time it is played, so the finished work is kept on disk, one file
 * for each map in each shape it is played in (mirrored, twice the size and so on) and each kind of
 * jump. From the second match on, every jump is known before the countdown ends.
 */
class ArenaJumps private constructor(private val world: World, private val voidY: Int, private val file: File?, private val kind: Mobility) {
    private val found = HashMap<RouteNode, List<RouteEdge>>()
    /** Asked for by a search: worked out before anything else. */
    private val asked = ArrayDeque<RouteNode>()
    private val wanted = HashSet<RouteNode>()
    /** The rest of the arena, outward from the spawn points. */
    private val sweep = ArrayDeque<RouteNode>()
    private val seen = HashSet<RouteNode>()
    private var seeded = false
    /** Spawn points whose ground has not been found yet (their chunks are still arriving). */
    private var unseeded: MutableList<Location>? = null
    private var seedSince = 0
    /** A spawn point was given up on: the sweep may have missed a part of the arena, so it is not for keeping. */
    private var seedMissed = false
    private var saved = false
    /** The arena has been changed since the match began: what is known now is not what the next match starts with. */
    private var changed = false
    /** Read from disk and not yet looked at against the arena itself (its chunks are still arriving). */
    private var pending: HashMap<RouteNode, List<RouteEdge>>? = null
    private var pendingSince = 0
    /** Blocks worked out with part of the arena round them missing: good enough for this match, not for keeping. */
    private val unsure = HashSet<RouteNode>()
    private var stalledSince = -1
    /** Reads the arena loading whatever chunk it needs: for working a whole map out in one go, never for a bot in a fight. */
    private var loader: ArenaTerrain? = null
    private var spent = 0L
    private var announced = false
    /** For [workTogether]: blocks whose jumps are next to be flown, and what the rules give from each. */
    private val batch = ArrayList<RouteNode>()
    private val based = HashMap<RouteNode, List<RouteEdge>>()

    /** Blocks worked out, and long jumps found from them. */
    val size: Int get() = found.size
    val jumps: Int get() = found.values.sumOf { list -> list.count { it.cost >= 0 } }
    /** Jumps the rules offered that turned out not to come off. */
    val struck: Int get() = found.values.sumOf { list -> list.count { it.cost < 0 } }
    /** Read from disk rather than worked out in this match. */
    var loaded = false
        private set
    /** Nothing left to work out. */
    val complete: Boolean get() = pending == null && seeded && unseeded?.isEmpty() == true && asked.isEmpty() && sweep.isEmpty() && batch.isEmpty()
    /** Blocks a search is waiting for. */
    val waiting: Int get() = asked.size
    /** Milliseconds of work this arena has taken in this match. */
    val millis: Long get() = spent / 1_000_000

    /** The long jumps from [node], or null if they have not been worked out yet. */
    fun of(node: RouteNode): List<RouteEdge>? = found[node]

    /** How many times a search has come to a block that was not worked out yet: a search during which this went up did not see everything. */
    var misses = 0L
        private set

    /** A search came to a block that has not been worked out: it goes to the front of the queue. */
    fun want(node: RouteNode) {
        if (node in found) return
        misses++
        if (wanted.add(node)) asked.addLast(node)
    }

    /** The long jumps from [node], worked out on the spot if need be. For surveys and debugging, not for a bot in a fight. */
    fun now(node: RouteNode): List<RouteEdge> {
        found[node]?.let { return it }
        val reader = reader()
        return reader.longJumps(node, reader.neighbours(node)).also { found[node] = it; seen.add(node) }
    }

    private fun reader(): ArenaTerrain = loader ?: ArenaTerrain(world, voidY, kind, loadChunks = true).also { loader = it }

    /**
     * Works on the arena for up to [nanos] nanoseconds. [seeds] are where to start from (the
     * spawn points). With [load] it loads whatever part of the arena it needs instead of waiting
     * for it (a survey or working a map out ahead of time: that stalls the server, so never for a
     * match somebody is playing). Returns how many blocks it got through.
     */
    fun work(nanos: Long, seeds: List<Location>, load: Boolean = false): Int {
        if (nanos <= 0) return 0
        val began = System.nanoTime()
        val nav = ArenaNav.of(world, voidY, kind)
        val reader = if (load) reader() else null
        pending?.let { read -> if (!check(read, reader ?: nav.terrainNow(), load)) return 0 }
        if (complete) return 0
        val tick = Bukkit.getCurrentTick()
        // The sweep starts from every spawn point, so that a part of the arena only some of them
        // lead to is not left out. A spawn point nobody stands on may not have its chunk yet.
        val left = unseeded ?: seeds.toMutableList().also { unseeded = it; seedSince = tick }
        val each = left.iterator()
        while (each.hasNext()) {
            val seed = each.next()
            val root = nav.nearest(seed, 5.0) ?: reader?.at(seed)
            if (root != null) {
                if (seen.add(root)) sweep.addLast(root)
                seeded = true
                each.remove()
            } else if (reader != null || world.isChunkLoaded(seed.blockX shr 4, seed.blockZ shr 4)) {
                each.remove() // Nothing to stand on there at all.
            } else if (tick - seedSince > SEED_TICKS) {
                seedMissed = true
                each.remove()
            }
        }
        if (!seeded) { if (left.isEmpty()) seeded = true else return 0 }
        val deadline = if (nanos == Long.MAX_VALUE) Long.MAX_VALUE else began + nanos
        // A chunk nobody is near never comes. After a good while of waiting, go with what is there.
        val patient = stalledSince < 0 || tick - stalledSince < STALL_TICKS
        var done = 0
        var stalled = 0
        while (System.nanoTime() < deadline) {
            val urgent = asked.isNotEmpty()
            val node = if (urgent) asked.removeFirst() else sweep.removeFirstOrNull() ?: break
            if (urgent) wanted.remove(node)
            val base: List<RouteEdge>
            var long = found[node]
            var incomplete = false
            if (reader != null) {
                base = reader.neighbours(node)
                if (long == null) long = reader.longJumps(node, base)
            } else {
                base = nav.base(node)
                incomplete = nav.lastIncomplete
                if (long == null && (!incomplete || !patient)) {
                    val terrain = nav.terrainNow()
                    terrain.sawUnloaded = false
                    long = terrain.longJumps(node, base)
                    incomplete = incomplete || terrain.sawUnloaded
                }
            }
            if (incomplete && patient) {
                // Part of what is round it has not loaded: come back to it, and stop for this
                // tick once everything left is waiting on chunks.
                stalled++
                if (urgent) { if (wanted.add(node)) asked.addLast(node) } else sweep.addLast(node)
                if (stalled > asked.size + sweep.size) break
                continue
            }
            val jumps = long ?: emptyList()
            if (incomplete) unsure += node
            if (found.put(node, jumps) == null) done++
            for (e in merge(base, jumps, kind.jumps.flown)) if (seen.add(e.node)) sweep.addLast(e.node)
        }
        stalledSince = if (stalled == 0) -1 else if (stalledSince < 0) tick else stalledSince
        spent += System.nanoTime() - began
        finished()
        return done
    }

    /** Once everything is worked out: says so, and keeps it. */
    private fun finished() {
        if (!complete || saved) return
        if (!announced && !loaded) {
            announced = true
            log("${file?.name ?: world.name}: ${found.size} blocks looked at, ${this.jumps} long jumps from ${found.values.count { it.isNotEmpty() }} of them, " +
                "$struck jumps of the rules struck out, " +
                "$millis ms of work" + (if (unsure.isEmpty()) "" else ", ${unsure.size} with part of the arena not loaded"))
        }
        save()
    }

    /**
     * The same work as [work] with loading, shared out over the threads of [pool]: for working
     * whole maps out ahead of time, never for a match somebody is playing (it holds the server
     * up while a slice is flown). The arena may only be read on the server's thread, so each call
     * first reads, on this thread, everything the rules reach from what is known and everything a
     * flight from there can touch; then [slice] blocks have their jumps flown on all the threads
     * at once from what was read. Call it every tick until [complete].
     */
    fun workTogether(seeds: List<Location>, pool: java.util.concurrent.ForkJoinPool, slice: Int = 800) {
        val began = System.nanoTime()
        val reader = reader()
        pending?.let { read -> if (!check(read, reader, true)) return }
        if (unseeded == null) {
            unseeded = ArrayList()
            for (seed in seeds) reader.at(seed)?.let { if (seen.add(it)) sweep.addLast(it) }
            seeded = true
        }
        // (A bot of the match may have asked for blocks meanwhile: they are part of the sweep here.)
        while (asked.isNotEmpty()) asked.removeFirst().let { wanted.remove(it); seen.add(it); sweep.addLast(it) }
        if (batch.isEmpty()) {
            while (true) {
                val node = sweep.removeFirstOrNull() ?: break
                if (node in found || node in based) continue
                val base = reader.neighbours(node)
                based[node] = base
                batch += node
                for (e in base) if (seen.add(e.node)) sweep.addLast(e.node)
            }
            if (batch.isNotEmpty()) reader.prefetch(batch.minOf { it.x } - REACH, batch.minOf { it.z } - REACH, batch.maxOf { it.x } + REACH, batch.maxOf { it.z } + REACH)
        }
        if (batch.isNotEmpty()) {
            val from = (batch.size - slice).coerceAtLeast(0)
            val part = ArrayList(batch.subList(from, batch.size))
            batch.subList(from, batch.size).clear()
            reader.frozen = true
            val flown = try {
                pool.submit(java.util.concurrent.Callable {
                    part.parallelStream().map { it to reader.longJumps(it, based.getValue(it)) }.collect(java.util.stream.Collectors.toList())
                }).get()
            } finally { reader.frozen = false }
            for ((node, long) in flown) {
                found[node] = long
                for (e in merge(based.getValue(node), long, kind.jumps.flown)) if (seen.add(e.node)) sweep.addLast(e.node)
            }
        }
        spent += System.nanoTime() - began
        if (complete) { based.clear(); loader = null }
        finished()
    }

    /** Works the whole arena out now, loading whatever it needs. Only where a stalled server does not matter. */
    fun finish(seeds: List<Location>) {
        work(Long.MAX_VALUE, seeds, load = true)
        loader = null
    }

    /** The arena changed around ([x], [z]): the jumps from there are looked at again when next wanted. */
    fun forget(x: Int, z: Int, radius: Int) {
        changed = true
        loader = null
        found.keys.removeIf { abs(it.x - x) <= radius && abs(it.z - z) <= radius }
    }

    /**
     * Once, when the sweep of the arena is done. What a search asks for after that (a ledge only a
     * chicken gets to, wherever a bot was knocked) is worked out in a tick and not worth a file write.
     */
    private fun save() {
        saved = true
        val target = file ?: return
        // (Or another match on the same map has written it meanwhile: it is the same file.)
        if (changed || seedMissed || loaded || target.isFile) return
        try {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            val keep = found.entries.filter { it.key !in unsure }
            DataOutputStream(BufferedOutputStream(temp.outputStream())).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(keep.size)
                for ((node, edges) in keep) {
                    out.writeInt(node.x); out.writeInt(node.y16); out.writeInt(node.z)
                    out.writeShort(edges.size)
                    for (e in edges) {
                        out.writeInt(e.node.x); out.writeInt(e.node.y16); out.writeInt(e.node.z)
                        out.writeFloat(e.cost.toFloat())
                        out.writeByte((e.charge * 100).toInt())
                    }
                }
            }
            if (target.exists()) target.delete()
            temp.renameTo(target)
            log("saved ${keep.size} blocks to ${target.name}")
        } catch (e: Exception) {
            log("could not save ${target.name}: $e")
        }
    }

    /** Reads what an earlier match worked out. It is not believed until [check] has looked at the arena itself. */
    private fun load() {
        val source = file?.takeIf { it.isFile } ?: return
        try {
            val read = HashMap<RouteNode, List<RouteEdge>>()
            DataInputStream(BufferedInputStream(source.inputStream())).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) { source.delete(); return }
                repeat(input.readInt()) {
                    val node = RouteNode(input.readInt(), input.readInt(), input.readInt())
                    val count = input.readShort().toInt()
                    read[node] = if (count == 0) emptyList() else List(count) {
                        RouteEdge(RouteNode(input.readInt(), input.readInt(), input.readInt()), true, input.readFloat().toDouble(), long = true,
                            charge = input.readByte() / 100f)
                    }
                }
            }
            pending = read
            pendingSince = Bukkit.getCurrentTick()
        } catch (e: Exception) {
            log("could not read ${source.name}: $e")
            source.delete()
        }
    }

    /**
     * Is the arena still the arena the file was worked out for? A few of the blocks it speaks of,
     * spread over the file, are looked for: are they still there to stand on? False while that
     * cannot be told yet (their chunks have not arrived), true once the file is taken or thrown away.
     */
    private fun check(read: HashMap<RouteNode, List<RouteEdge>>, terrain: ArenaTerrain, load: Boolean): Boolean {
        val sample = read.entries.filter { it.value.isNotEmpty() }.ifEmpty { read.entries.toList() }
        val step = (sample.size / CHECKS).coerceAtLeast(1)
        var unknown = 0
        for (i in sample.indices step step) {
            val (node, edges) = sample[i]
            for (n in listOfNotNull(node, edges.firstOrNull()?.node)) {
                if (!load && !world.isChunkLoaded(n.x shr 4, n.z shr 4)) { unknown++; continue }
                if (terrain.surface(n.x, n.z, n.y, 0.01, 0.01)?.y16 != n.y16) {
                    log("${file?.name} is for a different arena (nothing to stand on at ${n.x},${n.y},${n.z}): working it out again")
                    file?.delete()
                    pending = null
                    return true
                }
            }
        }
        if (unknown > 0 && Bukkit.getCurrentTick() - pendingSince < CHECK_TICKS) return false
        found.putAll(read)
        seen.addAll(read.keys)
        asked.removeAll { it in found }
        wanted.removeAll(found.keys)
        seeded = true
        unseeded = ArrayList()
        saved = true
        loaded = true
        pending = null
        return true
    }

    companion object {
        private const val MAGIC = 0x53534D4A
        /** Goes up whenever what counts as a long jump changes: files written before are worked out again. */
        private const val VERSION = 12
        private const val CHECKS = 16
        /** How long a file waits for the chunks it is checked against, and a block for the chunks round it. */
        private const val CHECK_TICKS = 100
        private const val STALL_TICKS = 600
        private const val SEED_TICKS = 200
        /** How far round a block a flight from it can touch anything: what is read before its jumps are flown on other threads. */
        private const val REACH = 36
        /** Where the files are kept (plugins/<plugin>/nav). Null: nothing is read or written. */
        @Volatile var folder: File? = null
        private val stores = HashMap<Pair<UUID, String>, ArenaJumps>()

        private fun log(message: String) = Bukkit.getLogger().info("[super-smash-mobs-brawl] [jumps] $message")

        /** The long jumps of [world], which is [map] as it is being played, for a fighter that gets around like [mobility]. */
        fun of(world: World, voidY: Int, map: GameMapDef?, mobility: Mobility): ArenaJumps {
            // (Somebody with no air jump has no long jumps of their own. Asked on their behalf,
            // these are the standard ones: never a set worked out with no jump at all, which
            // would be an empty one filed under the standard name.)
            val kind = mobility.home.takeIf { it.airborne } ?: Mobility.JUMPER
            return stores.getOrPut(world.uid to kind.jumps.id) {
                stores.keys.removeIf { Bukkit.getWorld(it.first) == null }
                val file = if (map != null) folder?.let { File(it, fileName(map, kind)) } else null
                ArenaJumps(world, voidY, file, kind).also { it.load() }
            }
        }

        /** The file a map's long jumps are kept in for one kind of jump (the standard one has the plain name). */
        fun fileName(map: GameMapDef, kind: Mobility): String =
            keyOf(map) + (if (kind.home.jumps.id == JumpKind.STANDARD.id) "" else "-" + kind.home.jumps.id) + ".jumps"

        /** How many sets of jumps of [world] are still being worked out. */
        fun unfinished(world: World): Int = stores.count { it.key.first == world.uid && !it.value.complete }

        /** The arena changed around ([x], [z]): every kind of jump there is looked at again when next wanted. */
        fun forgetAll(world: World, x: Int, z: Int, radius: Int) {
            for ((key, store) in stores) if (key.first == world.uid) store.forget(x, z, radius)
        }

        /**
         * What a fighter can do from a block: what the rules give ([base]) and the long jumps
         * flown for it ([long]). With [strike], less the jumps of the rules that were flown and
         * did not come off (for a kit that jumps the ordinary way: a chicken's flight to the same
         * landing is another matter).
         */
        fun merge(base: List<RouteEdge>, long: List<RouteEdge>, strike: Boolean): List<RouteEdge> = withLongJumps(base, long, strike)

        /** The long jumps of [world] for a fighter that gets around like [mobility], if anybody has asked for them yet. */
        fun find(world: World, mobility: Mobility): ArenaJumps? = stores[world.uid to (mobility.home.takeIf { it.airborne } ?: Mobility.JUMPER).jumps.id]

        /**
         * One name for each map in each shape it is played in. Mirroring, reflecting and
         * resizing all move the spawn points and the border, so those tell the shapes apart.
         */
        fun keyOf(map: GameMapDef): String {
            val shape = buildString {
                append(map.voidLevel).append('|').append(map.worldBorderSize).append('|')
                append(map.worldBorderCenterX).append('|').append(map.worldBorderCenterZ)
                for (p in map.spawnPoints) append('|').append(p.x).append(',').append(p.y).append(',').append(p.z)
            }
            return "${map.id}-${"%08x".format(shape.hashCode())}"
        }
    }
}
