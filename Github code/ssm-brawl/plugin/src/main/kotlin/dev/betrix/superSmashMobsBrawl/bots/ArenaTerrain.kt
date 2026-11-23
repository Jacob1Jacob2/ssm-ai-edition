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

    /**
     * While set, nothing is read from the world: a column not read yet counts as empty air. The
     * world may only be read on the server's own thread; with what a flight can touch read first
     * ([prefetch]) and this set, the jumps of many blocks can be flown on other threads at once.
     */
    @Volatile var frozen = false

    /** Reads every column of a rectangle now (loading its chunks if this reader does that). */
    fun prefetch(x1: Int, z1: Int, x2: Int, z2: Int) { for (x in x1..x2) for (z in z1..z2) column(x, z) }

    private fun column(x: Int, z: Int): Column {
        val key = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
        columns[key]?.let { return it }
        if (frozen) return EMPTY
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

    /**
     * The block at ([x], [y], [z]) has changed. True if what there is to bump into or stand on
     * there is not what this reading has (its column is then forgotten, to be read again when
     * next asked about), or the reading had not looked at that column yet (it does now, so that
     * the next change there can be told from no change).
     */
    private fun differs(x: Int, y: Int, z: Int): Boolean {
        if (y < bottom || y >= world.maxHeight || !world.isChunkLoaded(x shr 4, z shr 4)) return false
        val key = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
        val chunk = ((x shr 4).toLong() shl 32) or ((z shr 4).toLong() and 0xffffffffL)
        val old = columns[key]
        if (old == null) {
            chunkTops.remove(chunk)
            // (Looked at now, unless a great many blocks are changing at once: an arena being put
            // back at the end of a match is not the moment to read it column by column.)
            val tick = org.bukkit.Bukkit.getCurrentTick()
            if (tick != looksTick) { looksTick = tick; looks = 0 }
            if (looks++ < LOOKS_A_TICK) column(x, z)
            return true
        }
        // (One shape per kind of block, and none at all for what there is nothing to bump into:
        // the same kind of block is the same object, and air, snow and a cobweb are all nothing.)
        val block = world.getBlockAt(x, y, z)
        val now = if (block.type.isAir) null else kindOf(block).takeIf { it !== OPEN }
        if (old.cells.getOrNull(y - old.base) === now) return false
        columns.remove(key)
        chunkTops.remove(chunk)
        return true
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

    /**
     * Is there room for a body standing with its feet at ([x], [y], [z])? [foot] is how much of
     * the very bottom of the body is left out of the question: a little, so that standing on a
     * block is not being inside it. A jump flown in the head ([JumpSim]) asks with next to none.
     * (Touching is not being inside, to the same ten millionth of a block the game goes by.)
     */
    fun clear(x: Double, y: Double, z: Double, foot: Double = .025): Boolean {
        val minX = x - .3 + JumpSim.TOUCH
        val maxX = x + .3 - JumpSim.TOUCH
        val minZ = z - .3 + JumpSim.TOUCH
        val maxZ = z + .3 - JumpSim.TOUCH
        val minY = y + foot
        val maxY = y + 1.8
        for (bx in floor(minX).toInt()..floor(maxX).toInt()) for (bz in floor(minZ).toInt()..floor(maxZ).toInt()) {
            // (From the block below up: a fence or a wall stands half a block taller than the block it is in.)
            for (by in floor(minY).toInt() - 1..floor(maxY).toInt()) {
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

    /** A plain hop from [a] onto the block next to it, flown in the head with no air jump: what a step up in a tunnel is. */
    private fun plainHop(a: RouteNode, b: RouteNode): Boolean =
        JumpSim.promised({ x, y, z, foot -> clear(x, y, z, foot) }, null, a.x+.5, a.y, a.z+.5, b.x+.5, b.y, b.z+.5)

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
                // (The swept arc is a double jump's, with that much room over it. Under a low
                // ceiling a hop onto the next block needs far less: there the hop itself is flown.)
                if (traversable(a,b,jump) || (jump && plainHop(a,b))) result.add(RouteEdge(b,jump,a.distance(b)+(if(jump) .8 else 0.0)))
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

    /**
     * Every other landing this kind of fighter's air jump really reaches from [a] (a slime's
     * bounce and a wolf's pounce go where a standard double jump does not): the ones the rules in
     * [neighbours] do not offer, because they stop at six blocks, look in eight directions, or
     * judge a jump by a parabola with room over it. Each is flown in the head first ([JumpSim]),
     * through whatever is really in the way: the long jump between two islands, the hop across a
     * hatch under a low ceiling, up a shaft and across at the top.
     *
     * [base] is what [neighbours] gave for [a]. Nearest landings first, and of several one behind
     * the other at about the same height only the nearest: the next one is jumped to from there.
     * From the rim of something that is everything within reach. In the middle of a floor there
     * is nothing to jump but a ledge overhead, and that only from close by: the balcony whose own
     * overhang is in the way from right under it, the wall half a block too high for the rules.
     * This is the expensive question, which is why the answers are kept ([ArenaJumps]).
     *
     * The jumps the rules do offer are flown as well. One that does not come down where the rules
     * say (the drop that clips a ledge on the way, the gap under a beam) comes back struck out: an
     * edge to the same landing with [STRUCK] for a cost, which takes the rule's own edge out of
     * the graph ([ArenaJumps.merge]) and leaves its direction open for a jump that does work.
     */
    fun longJumps(a: RouteNode, base: List<RouteEdge>): List<RouteEdge> {
        if (!doubleJump) return emptyList()
        var rim = false
        for (dx in -1..1) for (dz in -1..1) {
            if (rim || (dx == 0 && dz == 0)) continue
            if (surface(a.x+dx,a.z+dz,a.y,1.25,1.25) == null && clear(a.x+dx+.5,a.y,a.z+dz+.5)) rim = true
        }
        val known = HashSet<RouteNode>(base.size * 2 + 8)
        val taken = DoubleArray(SECTORS * LONG_PER_SECTOR)
        val count = IntArray(SECTORS)
        fun fresh(sector: Int, y: Double): Boolean {
            for (i in 0 until count[sector]) if (abs(taken[sector * LONG_PER_SECTOR + i] - y) < 2.0) return false
            return count[sector] < LONG_PER_SECTOR
        }
        fun take(sector: Int, y: Double) { if (count[sector] < LONG_PER_SECTOR) taken[sector * LONG_PER_SECTOR + count[sector]++] = y }
        val result = ArrayList<RouteEdge>(4)
        val space = JumpSim.Space { x, y, z, foot -> clear(x, y, z, foot) }
        val launch = mobility.jumps.launch
        val reach = JumpSim.reachOf(launch)
        val charged = mobility.jumps.charged
        // Wherever the fighter can already go from here is a direction that needs no long jump.
        for (e in base) {
            known += e.node
            val dx = e.node.x - a.x
            val dz = e.node.z - a.z
            if (dx == 0 && dz == 0) continue
            if (jumped(a, e) && !JumpSim.jump(space,launch,a.x+.5,a.y,a.z+.5,e.node.x+.5,e.node.y,e.node.z+.5)) {
                result.add(RouteEdge(e.node,true,STRUCK,long = true))
                continue
            }
            take(sectorOf(dx, dz), e.node.y)
        }
        var tries = 0
        // (So many in each direction, so that a crowd of ledges one way does not use up the looking for all the others.)
        val tried = IntArray(SECTORS)
        for (o in OFFSETS) {
            if (!rim && o.dist > LEDGE_FROM) break
            val beside = o.dist < 1.9
            for (y in column(a.x+o.dx,a.z+o.dz).stand) {
                val dy = y - a.y
                if (dy > LONG_RISE) continue
                if (dy < -MAX_DROP) break
                // A step or a hop onto the block next door is the rules' business. A ledge overhead is
                // theirs first (straight up its side), and flown here where they would not have it.
                if (beside && dy <= 1.25) break
                if (!rim && dy <= 1.25) break
                // With a block in hand: a jump at the very end of what the physics allows is not one to promise.
                var with = launch
                var charge = 0f
                if (o.dist > reach.at(dy) - LONG_MARGIN) {
                    // Out of reach of the jump as it comes. A goat that crouches first goes higher: the least
                    // crouching that gets there. (For a landing overhead. A charged leap goes further
                    // across as well, but three seconds of standing still buys nothing a walk round does not.)
                    if (dy <= 1.25) continue
                    val more = charged.firstOrNull { o.dist <= JumpSim.reachOf(it.second).at(dy) - LONG_MARGIN } ?: continue
                    charge = more.first
                    with = more.second
                }
                val b = RouteNode(a.x+o.dx,(y*16).roundToInt(),a.z+o.dz)
                if (b in known || !fresh(o.sector,y)) continue
                if (abs(dy) <= 1.25 && flooredBetween(a,b)) continue
                if (++tried[o.sector] > LONG_SECTOR_TRIES) continue
                if (++tries > (if (rim) LONG_TRIES else LEDGE_TRIES)) return result
                if (!JumpSim.promised(space,with,a.x+.5,a.y,a.z+.5,b.x+.5,y,b.z+.5)) {
                    // (Something in the way of it as it comes may not be in the way of a higher one.)
                    if (dy <= 1.25) continue
                    val more = charged.firstOrNull { it.first > charge && o.dist <= JumpSim.reachOf(it.second).at(dy) - LONG_MARGIN } ?: continue
                    if (!JumpSim.promised(space,more.second,a.x+.5,a.y,a.z+.5,b.x+.5,y,b.z+.5)) continue
                    charge = more.first
                }
                known += b
                take(o.sector,y)
                // Dearer than a jump the rules are sure of: where there is a safer way of about the same length, that is the one.
                // (And dearer again by the time spent crouching for it.)
                result.add(RouteEdge(b,true,a.distance(b)*LONG_COST+LONG_EXTRA+charge*CHARGE_COST,long = true,charge = charge))
            }
        }
        return result
    }

    /**
     * Is this step of the rules one a fighter takes with a real jump? Not a walk, a hop onto the
     * block next door, a climb, or a landing close by and well below, which is walked off the edge
     * to and not jumped at (the same sum as the bot's own: BotBrain.stepOffSpeed).
     */
    private fun jumped(a: RouteNode, e: RouteEdge): Boolean {
        if (!e.jump || e.climb) return false
        val across = hypot((e.node.x - a.x).toDouble(), (e.node.z - a.z).toDouble())
        val dy = e.node.y - a.y
        if (across < 1.9 && dy <= 1.25) return false
        if (dy <= -3.0) {
            val fall = JumpSim.ticksUntilBelow(e.node.y, 0.0, a.y)
            if ((across - 0.6).coerceAtLeast(0.1) * 0.09 / (1 - 0.91.pow(fall)) <= 0.28) return false
        }
        return true
    }

    /** One column a leap might land in, relative to where it starts. */
    private class Offset(val dx: Int, val dz: Int) {
        val dist = hypot(dx.toDouble(), dz.toDouble())
        val straight = dx == 0 || dz == 0 || abs(dx) == abs(dz)
        val sector = sectorOf(dx, dz)
    }

    companion object {
        /** The cost that marks an edge of [longJumps] as a jump of the rules struck out, not a jump to take. */
        const val STRUCK = -1.0
        /** Which of the [SECTORS] directions ([dx], [dz]) lies in. */
        private fun sectorOf(dx: Int, dz: Int) = Math.floorMod((atan2(dz.toDouble(), dx.toDouble()) / (2 * PI) * SECTORS).roundToInt(), SECTORS)
        /** Long jumps kept in each direction (at different heights), and how many may be flown in the head for one block. */
        private const val LONG_PER_SECTOR = 4
        private const val LONG_TRIES = 120
        private const val LONG_SECTOR_TRIES = 8
        /** Away from any rim: how far off a ledge overhead is still jumped for, and how many are flown in the head. */
        private const val LEDGE_FROM = 3.0
        private const val LEDGE_TRIES = 10
        /** What a block of a long jump costs a route, against a block walked, and what it costs just to take one. */
        private const val LONG_COST = 1.5
        private const val LONG_EXTRA = 5.0
        /** What a full charge of a goat's leap costs a route: three seconds of standing still. */
        private const val CHARGE_COST = 14.0
        /** The highest landing a long jump is offered for, and how far short of the furthest possible it stops. */
        private const val LONG_RISE = JumpSim.HIGHEST
        private const val LONG_MARGIN = 1.0
        /** The deepest fall a route will take on purpose. */
        private const val MAX_DROP = 32.0
        /** How much further out than a level jump a drop may land. */
        private const val DROP_EXTRA = 1.0
        /** Directions a leap is sorted into, and how many landings (at different heights) are kept in each. */
        private const val SECTORS = 16
        private const val PER_SECTOR = 3
        private val SIDES = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private val GRIP_HEIGHTS = doubleArrayOf(0.1, 0.9)
        /** Every column within fifteen blocks (as far as the strongest jump with a long way to fall gets), nearest first. */
        private val OFFSETS: Array<Offset> = (-15..15).flatMap { dx -> (-15..15).map { dz -> Offset(dx, dz) } }
            .filter { it.dist > 0.0 && it.dist <= 15.0 }.sortedBy { it.dist }.toTypedArray()

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

        /** How many columns nobody had looked at are read in one tick because a block changed in them. */
        private const val LOOKS_A_TICK = 48
        private var looks = 0
        private var looksTick = -1

        /**
         * The block at ([x], [y], [z]) changed ([ArenaChanges]). True if what there is to bump
         * into or to stand on there is not what a shared reading of the arena had: its column is
         * then read again when next asked about (and how high its chunk goes: a block put where
         * there was only air above may be the highest thing in it now). (Most of what changes in
         * an arena changes nothing of the kind: a layer of snow under a Snowman's feet, a cobweb,
         * a flower going. Those are no news to anybody's way.)
         */
        fun changed(world: World, x: Int, y: Int, z: Int): Boolean {
            var real = false
            // (The keys first: reading a column may begin a new reading, which tidies this map.)
            for (key in readings.keys.filter { it.first == world.uid }) {
                if (ArenaTerrain(world, key.second, Mobility.WALKER, shared = true).differs(x, y, z)) real = true
            }
            return real
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
