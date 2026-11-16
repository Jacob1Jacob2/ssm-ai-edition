package dev.betrix.superSmashMobsBrawl.bots

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.util.BoundingBox
import kotlin.math.*

/**
 * Reads the installed arena, so mirror/reflect/scale and destroyed terrain all use the same rules.
 *
 * [mobility] is the kind of fighter the arena is being read for: what counts as a way from one
 * block to the next depends on whether it has a double jump, eight flaps or can climb walls.
 * [leaping] can be switched off to get the plain walking-and-hopping graph, which the chase test
 * uses to tell apart the spots that need the bigger moves. [loadChunks] makes a lookup in a chunk
 * that is not loaded load it instead of treating it as a wall (for the chase test's survey of a
 * whole map; a bot planning a route must never stall the server on that).
 *
 * The arena is read a column at a time, each once: every question a route asks (where can I stand
 * here, how much room is over my head, is this body-sized space free) is then answered from that
 * column instead of from the world again. With [shared] those columns are the ones every other
 * shared reader of this arena uses too (a spider's graph and a chicken's read the same blocks),
 * kept for a few seconds; without it this reader looks at the world as it is right now.
 */
class ArenaTerrain(private val world: World, private val voidY: Int, private val mobility: Mobility,
                   private val leaping: Boolean = true, private val loadChunks: Boolean = false,
                   shared: Boolean = false) {
    constructor(world: World, voidY: Int, doubleJump: Boolean) :
        this(world, voidY, if (doubleJump) Mobility.JUMPER else Mobility.WALKER)

    private val doubleJump = mobility.airborne

    /** A block's collision boxes in its own coordinates (0 to 1; a fence reaches 1.5). One per kind of block. */
    private class Shape(val hazard: Boolean, val boxes: Array<BoundingBox>)

    /**
     * One column of the arena. [cells] are its blocks from [base] up (null where there is nothing
     * to bump into), [stand] the heights a body centred in the column can stand at, highest
     * first, and [room] the underside of whatever is next above each of those (or no limit).
     */
    private class Column(val base: Int, val cells: Array<Shape?>, val stand: DoubleArray, val room: DoubleArray)
    /** What has been read of one arena, and when the reading began. */
    private class Reading(val born: Int) {
        val columns = HashMap<Long, Column>()
        val chunkTops = HashMap<Long, Int>()
    }
    private val bottom = max(voidY, world.minHeight)
    private val reading: Reading = if (shared && !loadChunks) sharedReading(world, bottom) else Reading(0)
    private val columns get() = reading.columns

    /**
     * Set whenever a lookup lands in a chunk that is not loaded. Such a block is treated as
     * impassable, which is right for a route being walked now but must not be remembered: the
     * chunk is usually just about to load (a match that has only begun).
     */
    var sawUnloaded = false

    private fun column(x: Int, z: Int): Column {
        val key = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
        columns[key]?.let { return it }
        if (!world.isChunkLoaded(x shr 4, z shr 4)) {
            // Not stored, so the column is looked at again once its chunk is there.
            if (!loadChunks) { sawUnloaded = true; return UNLOADED }
            world.getChunkAt(x shr 4, z shr 4)
        }
        val top = min(chunkTop(x shr 4, z shr 4), world.maxHeight - 1)
        if (top < bottom) return EMPTY.also { columns[key] = it }
        val cells = arrayOfNulls<Shape>(top - bottom + 1)
        for (by in top downTo bottom) {
            val block = world.getBlockAt(x, by, z)
            if (block.type.isAir) continue
            val shape = kindOf(block)
            if (shape !== OPEN) cells[by - bottom] = shape
        }
        /** Would a body centred in this column, feet at [y], be inside something? */
        fun blocked(y: Double): Boolean {
            for (by in floor(y).toInt()..floor(y + 1.8).toInt()) {
                val s = cells.getOrNull(by - bottom) ?: continue
                if (s.hazard) return true
                for (b in s.boxes) {
                    if (b.minX < .8 && b.maxX > .2 && b.minZ < .8 && b.maxZ > .2 && b.minY + by < y + 1.8 && b.maxY + by > y + .025) return true
                }
            }
            return false
        }
        val stand = ArrayList<Double>(4)
        val room = ArrayList<Double>(4)
        var ceiling = Double.MAX_VALUE
        for (by in top downTo bottom) {
            val s = cells[by - bottom] ?: continue
            if (!s.hazard) {
                var last = Double.NaN
                // The tops of whatever is under the middle of the column, highest first.
                for (h in s.boxes.filter { it.minX <= .5 && it.maxX >= .5 && it.minZ <= .5 && it.maxZ >= .5 }.map { it.maxY }.sortedDescending()) {
                    if (h == last) continue
                    last = h
                    if (!blocked(h + by)) { stand += h + by; room += ceiling }
                }
            }
            // Whatever is in this block is over the head of everything below it.
            if (s.hazard) ceiling = min(ceiling, by.toDouble())
            for (b in s.boxes) if (b.minX < .8 && b.maxX > .2 && b.minZ < .8 && b.maxZ > .2) ceiling = min(ceiling, b.minY + by)
        }
        return Column(bottom, cells, stand.toDoubleArray(), room.toDoubleArray()).also { columns[key] = it }
    }

    private val chunkTops get() = reading.chunkTops

    /**
     * The highest block in a chunk that could be anything but air: the top of its highest section
     * with something in it. (Not the height map: arenas copied in from a build keep whatever
     * height map the build was saved with, and that is often wrong.)
     */
    private fun chunkTop(cx: Int, cz: Int): Int = chunkTops.getOrPut((cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)) {
        val chunk = (world as org.bukkit.craftbukkit.CraftWorld).handle.getChunk(cx, cz)
        val sections = chunk.sections
        var top = world.minHeight - 1
        for (i in sections.indices.reversed()) {
            if (!sections[i].hasOnlyAir()) { top = (chunk.getSectionYFromSectionIndex(i) shl 4) + 15; break }
        }
        top
    }

    private fun shape(x: Int, y: Int, z: Int): Shape {
        if (y !in world.minHeight until world.maxHeight) return SOLID
        val c = column(x, z)
        if (c === UNLOADED) return SOLID
        val i = y - c.base
        return if (i < 0 || i >= c.cells.size) OPEN else c.cells[i] ?: OPEN
    }

    /** Is there room for a body standing with its feet at ([x], [y], [z])? */
    fun clear(x: Double, y: Double, z: Double): Boolean {
        val minX = x - .3
        val maxX = x + .3
        val minZ = z - .3
        val maxZ = z + .3
        val minY = y + .025
        val maxY = y + 1.8
        for (bx in floor(minX).toInt()..floor(maxX).toInt()) for (bz in floor(minZ).toInt()..floor(maxZ).toInt()) {
            for (by in floor(y).toInt()..floor(maxY).toInt()) {
                val s = shape(bx, by, bz)
                if (s === OPEN) continue
                if (s.hazard) return false
                for (b in s.boxes) {
                    if (b.minX + bx < maxX && b.maxX + bx > minX && b.minZ + bz < maxZ && b.maxZ + bz > minZ &&
                        b.minY + by < maxY && b.maxY + by > minY) return false
                }
            }
        }
        return true
    }

    /** The highest place to stand in column ([x], [z]) that is no more than [up] above [nearY] and [down] below it. */
    fun surface(x: Int, z: Int, nearY: Double, up: Double = 1.25, down: Double = 3.0): RouteNode? {
        val highest = nearY + up + .01
        val lowest = nearY - down
        for (y in column(x, z).stand) {
            if (y > highest) continue
            if (y < lowest) return null
            return RouteNode(x, (y * 16).roundToInt(), z)
        }
        return null
    }

    /** The underside of whatever is next over the head of a fighter standing on [node]: how far up its column is open. */
    private fun roomOver(node: RouteNode): Double {
        val c = column(node.x, node.z)
        for (i in c.stand.indices) if (abs(c.stand[i] - node.y) < .04) return c.room[i]
        return node.y + 1.8
    }


    /** Debug: where one can stand in a column and the room over each spot, for reading a survey. */
    fun describe(x: Int, z: Int): String = column(x, z).let { c ->
        c.stand.indices.joinToString(" ") { "${"%.2f".format(c.stand[it])}^${if (c.room[it] > 1e6) "sky" else "%.2f".format(c.room[it])}" }
    }

    fun at(loc: Location, down: Double = 5.0) = surface(loc.blockX,loc.blockZ,loc.y,1.25,down)
    fun location(node: RouteNode) = Location(world,node.x+.5,node.y,node.z+.5)
    private fun traversable(a: RouteNode, b: RouteNode, jumping: Boolean): Boolean {
        val dist = hypot((b.x-a.x).toDouble(),(b.z-a.z).toDouble())
        val steps = ceil((dist + abs(b.y-a.y))*8).toInt().coerceAtLeast(8)
        val highJump = jumping && Mobility.straightUp(b.y-a.y,dist)
        if(highJump) {
            // A high ledge needs a mostly vertical double jump before moving over
            // its lip. Check that takeoff column instead of drawing a diagonal
            // through the wall, or skipping the ceiling underneath a canopy.
            if (roomOver(a) < b.y+2.1) return false
        }
        for (i in 1 until steps) {
            val t = i.toDouble()/steps
            // Sweep upward from the actual takeoff height. Starting the whole arc at
            // the higher landing height let routes jump straight through low ceilings.
            val y = if (highJump) b.y + sin(PI*t)*1.8
                else if (jumping) a.y + (b.y-a.y)*t + sin(PI*t) * (if(doubleJump) 1.8 else 1.05)
                else max(a.y,b.y)
            if (!clear(a.x+.5+(b.x-a.x)*t,y,a.z+.5+(b.z-a.z)*t)) return false
            // A jump this long is not made without the air jump, and that goes a good deal higher
            // than a hop: it needs the room for it, or it ends against the ceiling and in the gap.
            if (jumping && !highJump && doubleJump && dist > AIR_JUMP_FROM &&
                !clear(a.x+.5+(b.x-a.x)*t,a.y+(b.y-a.y)*t+sin(PI*t)*AIR_JUMP_LIFT,a.z+.5+(b.z-a.z)*t)) return false
        }
        return true
    }

    /**
     * Can a fighter hop off [a] and come down on [b], which is well below it? The hop goes out
     * over [b]'s column at about the height it left from, then it is a straight fall.
     */
    private fun dropClear(a: RouteNode, b: RouteNode): Boolean {
        // The fall first: it is the cheap half, and under a roof it settles the matter.
        if (roomOver(b) < a.y+1.8) return false
        val dist = hypot((b.x-a.x).toDouble(),(b.z-a.z).toDouble())
        val steps = ceil(dist*2.5).toInt().coerceAtLeast(3)
        for (i in 1..steps) {
            val t = i.toDouble()/steps
            if (!clear(a.x+.5+(b.x-a.x)*t, a.y+sin(PI*t)*1.1+.05, a.z+.5+(b.z-a.z)*t)) return false
            // Far enough out that it takes the air jump to get there: room for that too.
            if (doubleJump && dist > AIR_JUMP_FROM+.5 && !clear(a.x+.5+(b.x-a.x)*t, a.y+sin(PI*t)*AIR_JUMP_LIFT, a.z+.5+(b.z-a.z)*t)) return false
        }
        return true
    }

    /** How far above the take-off a jump across [dist] blocks can still land: a long jump has little height to spare. */
    private fun rise(dist: Double): Double = if (!doubleJump) 1.0 else if (dist <= 5.0) 4.0 else 2.0

    /** Is there floor to walk on all the way from [a] to [b]? Then nobody needs to jump it. */
    private fun flooredBetween(a: RouteNode, b: RouteNode): Boolean {
        val steps = max(abs(b.x-a.x),abs(b.z-a.z))
        for (i in 1 until steps) {
            val t = i.toDouble()/steps
            if (surface((a.x+(b.x-a.x)*t).roundToInt(),(a.z+(b.z-a.z)*t).roundToInt(),a.y+(b.y-a.y)*t,1.25,1.5)==null) return false
        }
        return true
    }

    /**
     * What a player does at the edge of a platform that the eight straight directions do not
     * cover: jump a gap at an angle, and drop onto something a long way down (a ledge under a roof,
     * the floor below a balcony). Without these, a fighter standing somewhere that is only reached
     * that way looked unreachable, and a bot hunting them had nowhere to go.
     *
     * Landings are tried nearest first, and of several one behind the other at about the same
     * height only the nearest is kept: from a cliff over a wide floor that is one way down in each
     * direction, not a hundred.
     */
    private fun leaps(a: RouteNode, result: MutableList<RouteEdge>) {
        val reach = mobility.across
        val known = result.mapTo(HashSet()) { it.node }
        val taken = DoubleArray(SECTORS * PER_SECTOR)
        val count = IntArray(SECTORS)
        fun fresh(sector: Int, y: Double): Boolean {
            for (i in 0 until count[sector]) if (abs(taken[sector * PER_SECTOR + i] - y) < 2.0) return false
            return count[sector] < PER_SECTOR
        }
        fun take(sector: Int, y: Double) { taken[sector * PER_SECTOR + count[sector]++] = y }
        for (o in OFFSETS) {
            if (o.dist > reach+DROP_EXTRA) break
            // A gap jumped at an angle, onto ground at about this height or a ledge above.
            if (!o.straight && o.dist <= reach) {
                val land = surface(a.x+o.dx,a.z+o.dz,a.y,rise(o.dist),3.0)
                if (land != null && land !in known && fresh(o.sector,land.y) && !flooredBetween(a,land) && traversable(a,land,true)) {
                    known += land
                    take(o.sector,land.y)
                    result.add(RouteEdge(land,true,a.distance(land)+3.0))
                }
            }
            // A drop: falling takes time, which carries a jump a little further out.
            val low = surface(a.x+o.dx,a.z+o.dz,a.y-3.05,0.0,MAX_DROP-3.0) ?: continue
            if (low in known || !fresh(o.sector,low.y) || !dropClear(a,low)) continue
            known += low
            take(o.sector,low.y)
            result.add(RouteEdge(low,true,a.distance(low)+3.0))
        }
    }

    /**
     * Is there something solid right beside a body standing in column ([x], [z]) with its feet at
     * [y]? That is what a wall climber holds on to (the same test the spider's climb makes).
     */
    private fun grip(x: Int, y: Double, z: Int): Boolean {
        for (h in GRIP_HEIGHTS) {
            val by = floor(y+h).toInt()
            for (side in SIDES) {
                val s = shape(x+side.first,by,z+side.second)
                if (!s.hazard && s.boxes.isNotEmpty()) return true
            }
        }
        return false
    }

    /**
     * Room to get from over [a] onto [ledge] once the fighter is level with it: either straight
     * across just over the lip (all there is room for under a low ceiling), or in an arc over it
     * (which clears a corner block that stands a little higher than the ledge).
     */
    private fun hop(a: RouteNode, ledge: RouteNode): Boolean {
        var low = true
        for (i in 1..3) {
            val t = i/3.0
            if (!clear(a.x+.5+(ledge.x-a.x)*t,ledge.y+.3,a.z+.5+(ledge.z-a.z)*t)) { low = false; break }
        }
        return low || traversable(a,ledge,true)
    }

    /**
     * Ledges right beside [a] that are too high to step or hop onto, but that this kind of fighter
     * gets up to all the same: straight up the column over [a], then across onto the ledge.
     * A double jump does it for a ledge a few blocks up, a chicken flaps its way up a tower, and a
     * spider walks up the wall as long as there is wall to hold.
     */
    private fun ascents(a: RouteNode, result: MutableList<RouteEdge>) {
        val flight = if (doubleJump) mobility.up else 0.0
        val limit = max(flight, mobility.climb)
        if (limit <= 1.25) return
        // How far up the column over [a] is open settles how high any of this can go.
        val open = roomOver(a)-2.1
        if (open <= a.y+1.25) return
        val reachable = min(a.y+limit, open)
        var ledges: ArrayList<RouteNode>? = null
        for (dx in -1..1) for (dz in -1..1) {
            if (dx==0 && dz==0) continue
            for (y in column(a.x+dx,a.z+dz).stand) {
                if (y > reachable+.01) continue
                if (y <= a.y+1.25) break
                (ledges ?: ArrayList<RouteNode>().also { ledges = it }).add(RouteNode(a.x+dx,(y*16).roundToInt(),a.z+dz))
            }
        }
        val found = ledges ?: return
        found.sortBy { it.y16 }
        var checked = a.y
        var gripping = mobility.climb > 0 && (grip(a.x,a.y,a.z) || grip(a.x,a.y+.5,a.z))
        var gripTop = a.y
        for (ledge in found) {
            while (gripping && checked+1.0 <= ledge.y+.5) {
                checked += 1.0
                if (grip(a.x,checked,a.z) || grip(a.x,checked-.5,a.z)) gripTop = checked else gripping = false
            }
            if (!hop(a,ledge)) continue
            val rise = ledge.y-a.y
            // The last stretch of a climb is carried by the speed of the climb itself.
            val climbable = mobility.climb >= rise && gripTop >= ledge.y-.55
            if (climbable && (rise > 3.0 || flight < rise)) result.add(RouteEdge(ledge,false,a.distance(ledge)+2.0,climb = true))
            else if (flight >= rise) result.add(RouteEdge(ledge,true,a.distance(ledge)+3.0))
        }
    }

    fun neighbours(a: RouteNode): List<RouteEdge> {
        val result = ArrayList<RouteEdge>(12)
        var atEdge = false
        val reach = mobility.across
        for (dx in -1..1) for (dz in -1..1) {
            if (dx==0 && dz==0) continue
            val b = surface(a.x+dx,a.z+dz,a.y)
            if (b != null) {
                // Missing side floors require a real jump, not a diagonal walking step.
                // Ruins has diagonal stepping stones; banning these disconnected islands
                // that a normal player can reach. The swept jump still checks solid walls.
                val diagonalGap = dx!=0 && dz!=0 &&
                    (surface(a.x+dx,a.z,a.y)==null || surface(a.x,a.z+dz,a.y)==null)
                val jump = b.y > a.y+.6 || diagonalGap
                if (traversable(a,b,jump)) result.add(RouteEdge(b,jump,a.distance(b)+(if(jump) .8 else 0.0)))
            } else {
                // Open air beside the block with no floor under it is an edge. A wall is not.
                if (!atEdge && clear(a.x+dx+.5,a.y,a.z+dz+.5)) atEdge = true
                val maxSteps = floor(reach / hypot(dx.toDouble(),dz.toDouble())).toInt()
                for (distance in 2..maxSteps) {
                    val land = surface(a.x+dx*distance,a.z+dz*distance,a.y,rise(distance*hypot(dx.toDouble(),dz.toDouble())),3.0) ?: continue
                    if (traversable(a,land,true)) result.add(RouteEdge(land,true,a.distance(land)+3.0))
                    break
                }
            }
        }
        ascents(a,result)
        // Only where the floor ends: in the middle of a platform there is nothing to leap off.
        if (atEdge && leaping) leaps(a,result)
        return result
    }

    /** One column a leap might land in, relative to where it starts. */
    private class Offset(val dx: Int, val dz: Int) {
        val dist = hypot(dx.toDouble(), dz.toDouble())
        val straight = dx == 0 || dz == 0 || abs(dx) == abs(dz)
        val sector = Math.floorMod((atan2(dz.toDouble(), dx.toDouble()) / (2 * PI) * SECTORS).roundToInt(), SECTORS)
    }

    companion object {
        /** The deepest fall a route will take on purpose. */
        private const val MAX_DROP = 32.0
        /** How much further out than a level jump a drop may land. */
        private const val DROP_EXTRA = 1.0
        /** Directions a leap is sorted into, and how many landings (at different heights) are kept in each. */
        private const val SECTORS = 16
        private const val PER_SECTOR = 3
        private val SIDES = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private val GRIP_HEIGHTS = doubleArrayOf(0.1, 0.9)
        /** Every column within ten blocks, nearest first. */
        private val OFFSETS: Array<Offset> = (-10..10).flatMap { dx -> (-10..10).map { dz -> Offset(dx, dz) } }
            .filter { it.dist > 0.0 && it.dist <= 10.0 }.sortedBy { it.dist }.toTypedArray()

        /**
         * Blocks that are water (or lava) to a fighter. Touching the liquid itself is death in the
         * arena; a block of seagrass is water with a plant in it, and whoever wades into that is
         * swimming and about to drift into the real thing.
         */
        private val LIQUIDS = setOf(Material.LAVA, Material.WATER, Material.BUBBLE_COLUMN,
            Material.SEAGRASS, Material.TALL_SEAGRASS, Material.KELP, Material.KELP_PLANT)
        fun watery(type: Material): Boolean = type in LIQUIDS
        private val HAZARDS = LIQUIDS + setOf(Material.FIRE, Material.SOUL_FIRE,
            Material.CACTUS, Material.MAGMA_BLOCK, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH)
        /** From this far across, a jump needs the air jump, which lifts the fighter this much over the take-off. */
        private const val AIR_JUMP_FROM = 4.5
        private const val AIR_JUMP_LIFT = 3.6
        /** Most of an arena is air; every block with nothing to bump into shares this one shape. */
        private val OPEN = Shape(false, emptyArray())
        /** Outside the world, or in a chunk that is not there yet: no way through. */
        private val SOLID = Shape(true, emptyArray())
        private val EMPTY = Column(0, emptyArray(), DoubleArray(0), DoubleArray(0))
        private val UNLOADED = Column(0, emptyArray(), DoubleArray(0), DoubleArray(0))
        /** How long a shared reading of an arena is trusted before the blocks are looked at again. */
        private const val READING_TICKS = 200
        private val readings = HashMap<Pair<java.util.UUID, Int>, Reading>()
        private fun sharedReading(world: World, bottom: Int): Reading {
            val now = org.bukkit.Bukkit.getCurrentTick()
            val key = world.uid to bottom
            readings[key]?.takeIf { now - it.born in 0..READING_TICKS }?.let { return it }
            // A new reading is a good moment to drop those of arenas that are gone.
            readings.keys.removeIf { org.bukkit.Bukkit.getWorld(it.first) == null }
            return Reading(now).also { readings[key] = it }
        }

        /** The arena changed around ([x], [z]): shared readings of it look at those columns again. */
        fun forget(world: World, x: Int, z: Int, radius: Int) {
            for ((key, reading) in readings) {
                if (key.first != world.uid) continue
                reading.columns.keys.removeIf { abs((it shr 32).toInt() - x) <= radius && abs(it.toInt() - z) <= radius }
            }
        }

        /** What each kind of block is to walk into. A block's shape depends only on its kind and state. */
        private val kinds = HashMap<BlockData, Shape>()
        private fun kindOf(block: Block): Shape = kinds.getOrPut(block.blockData) {
            val boxes = block.collisionShape.boundingBoxes
            // (Water with something in it that does not hold a fighter up, such as coral, is still water.)
            val hazard = block.type in HAZARDS ||
                (boxes.isEmpty() && (block.blockData as? org.bukkit.block.data.Waterlogged)?.isWaterlogged == true)
            if (!hazard && boxes.isEmpty()) OPEN else Shape(hazard, boxes.toTypedArray())
        }
    }
}
