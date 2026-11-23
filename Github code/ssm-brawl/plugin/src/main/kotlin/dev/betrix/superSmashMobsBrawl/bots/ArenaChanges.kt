package dev.betrix.superSmashMobsBrawl.bots

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.Block

/**
 * Telling the bots' picture of an arena that a block has changed.
 *
 * An arena does not stay as it was built. A Creeper takes a bite out of the floor and the floor
 * grows back ten seconds later; a Snowman lays ice across a gap; a Mooshroom stands a wall up. The
 * bots' picture ([ArenaTerrain]'s readings, [ArenaNav]'s steps) used to hear of none of it: it
 * looked at the blocks again every ten or fifteen seconds and kept the steps it had worked out for
 * a minute, so a hole that lasted ten seconds could go unseen from start to finish, or go on
 * being walked round for a minute after it had closed. A bot found out when it got there: 78 times
 * in ten matches the next step of its way was gone when it reached it.
 *
 * Now a change is told here, from two places. The arena's own code says so as it does it
 * ([changed]: explosions, temporary blocks, everything put back). And every bot is told what a
 * player's game is told, a block at a time, by the server ([sighted], from [nms.FakeConnection]):
 * that covers whatever a kit or an item does to a block in its own way, without the kit having to
 * know that bots exist.
 *
 * Most of what changes in an arena is nothing to anybody's way (a layer of snow under a Snowman,
 * a cobweb, a plant: 72,000 of 97,000 changes in ten matches), so a change counts only if what
 * there is to bump into or stand on at that block is not what the picture had. Then the column is
 * read again when next asked about, and once a tick ([settle]) the steps worked out from the
 * blocks within a few columns of it are forgotten, and every bot can ask whether its own way
 * went past there ([stamp], [touched], [within]).
 */
object ArenaChanges {
    /** Debug: nothing is told, as before, so that the difference can be measured. */
    @Volatile var off = false

    private data class Spot(val world: UUID, val x: Int, val y: Int, val z: Int)
    /** What bots' connections were told since the last [settle]: filled from whichever thread sends packets. */
    private val sighted = ConcurrentLinkedQueue<Spot>()
    /** The columns (x and z packed) that really changed in each arena since the last [settle]. */
    private val pending = HashMap<UUID, HashSet<Long>>()

    /** What changed in an arena the last time anything did: the columns, everything within reach of them, and when. */
    private class Recent {
        var stamp = 0
        var columns: Set<Long> = emptySet()
        var area: Set<Long> = emptySet()
        var tick = -1000
    }
    private val recent = HashMap<UUID, Recent>()
    private var settled = -1
    /** Spots told by the arena's own code this tick and the last: what a bot is sent about them a moment later is no news. */
    private var toldNow = HashSet<Spot>()
    private var toldBefore = HashSet<Spot>()

    /**
     * For the log: block changes told by the arena's own code, seen by bots (and not told), those
     * of either kind that changed nothing a body could bump into or stand on, and the blocks
     * whose steps were thrown away because of the rest.
     */
    var told = 0L; private set
    var seen = 0L; private set
    var idle = 0L; private set
    var forgotten = 0L; private set
    /** For the log: how long all of this has taken since the server started (not counting the steps worked out again afterwards). */
    var nanos = 0L; private set

    /** [block] has just been changed (or put back). Called by whatever did it, on the server's own thread. */
    fun changed(block: Block) {
        if (off || !ArenaNav.known(block.world)) return
        val began = System.nanoTime()
        toldNow.add(Spot(block.world.uid, block.x, block.y, block.z))
        changed(block.world, block.x, block.y, block.z, false)
        nanos += System.nanoTime() - began
    }

    private fun changed(world: World, x: Int, y: Int, z: Int, bySight: Boolean) {
        // Nothing there that can be bumped into or stood on is different (snow settling, a cobweb, a plant): no news.
        if (!ArenaTerrain.changed(world, x, y, z)) { idle++; return }
        if (bySight) seen++ else told++
        // The steps worked out around it are seen to once a tick ([settle]).
        ArenaNav.rereads(world)
        pending.getOrPut(world.uid) { HashSet() }.add(ChangedCells.pack(x, z))
    }

    /** A bot in [world] has been sent word of a block change at ([x], [y], [z]). May be called from any thread. */
    fun sighted(world: UUID, x: Int, y: Int, z: Int) { if (!off) sighted.add(Spot(world, x, y, z)) }

    /**
     * Once a tick, before bots plan anything: what they were sent is taken in, and every graph of
     * an arena forgets the steps it had worked out around what changed there.
     */
    fun settle() {
        val now = Bukkit.getCurrentTick()
        if (now == settled) return
        settled = now
        if (sighted.isEmpty() && pending.isEmpty() && toldNow.isEmpty() && toldBefore.isEmpty()) return
        val began = System.nanoTime()
        try { settleNow(now) } finally { nanos += System.nanoTime() - began }
    }

    private fun settleNow(now: Int) {
        if (!sighted.isEmpty()) {
            // (Every bot in the match is sent the same word: once is enough. So is what the arena's own code already said.)
            val once = HashSet<Spot>()
            while (true) {
                val s = sighted.poll() ?: break
                if (!once.add(s) || s in toldNow || s in toldBefore) continue
                val world = Bukkit.getWorld(s.world) ?: continue
                if (ArenaNav.known(world)) changed(world, s.x, s.y, s.z, true)
            }
        }
        toldBefore = toldNow
        toldNow = HashSet()
        if (pending.isEmpty()) return
        for ((id, columns) in pending) {
            val world = Bukkit.getWorld(id) ?: continue
            val area = ChangedCells.area(columns, ChangedCells.REACH)
            forgotten += ArenaNav.changed(world, area)
            val r = recent.getOrPut(id) { Recent() }
            r.stamp++
            r.columns = columns
            r.area = area
            r.tick = now
        }
        pending.clear()
        if (recent.size > 16) recent.keys.removeIf { Bukkit.getWorld(it) == null }
    }

    /** Goes up every time something has really changed in [world]: a bot that has seen this number has nothing new to look at. */
    fun stamp(world: World): Int = recent[world.uid]?.stamp ?: 0

    /** Did a block change within [radius] columns of ([x], [z]) of [world] the last time anything there did, this tick or the one before? */
    fun touched(world: World, x: Int, z: Int, radius: Int): Boolean {
        val r = recent[world.uid] ?: return false
        if (Bukkit.getCurrentTick() - r.tick > 1) return false
        for (dx in -radius..radius) for (dz in -radius..radius) if (ChangedCells.pack(x + dx, z + dz) in r.columns) return true
        return false
    }

    /** Is column ([x], [z]) of [world] within reach of what changed there this tick or the one before (near enough for the steps from it to have been forgotten)? */
    fun within(world: World, x: Int, z: Int): Boolean {
        val r = recent[world.uid] ?: return false
        return Bukkit.getCurrentTick() - r.tick <= 1 && ChangedCells.pack(x, z) in r.area
    }
}

/**
 * The arithmetic of [ArenaChanges], apart from any arena. A column is kept as one number (x and z
 * packed). The steps the walking graph worked out from a block are thrown away when a block
 * changes within [REACH] columns of it: a step, a hop or a climb looks no further than that. (A
 * jump or a drop from further off that passes over the change is still caught the old way, when
 * the bot gets there and looks.)
 */
object ChangedCells {
    /** How far from a changed column the steps worked out from a block are thrown away. */
    const val REACH = 5

    fun pack(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)

    /** Every column within [reach] of one of these (packed) columns, themselves included. */
    fun area(columns: Collection<Long>, reach: Int): HashSet<Long> {
        val area = HashSet<Long>(columns.size * (2 * reach + 1) * (2 * reach + 1))
        for (c in columns) {
            val x = (c shr 32).toInt()
            val z = c.toInt()
            for (dx in -reach..reach) for (dz in -reach..reach) area.add(pack(x + dx, z + dz))
        }
        return area
    }
}
