package dev.betrix.superSmashMobsBrawl.bots

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.util.BoundingBox
import kotlin.math.*

/** Reads the installed arena, so mirror/reflect/scale and destroyed terrain all use the same rules. */
class ArenaTerrain(private val world: World, private val voidY: Int, private val doubleJump: Boolean) {
    private data class Cell(val x: Int, val y: Int, val z: Int)
    private data class Shape(val hazard: Boolean, val boxes: List<BoundingBox>)
    private val shapes = HashMap<Cell, Shape>()
    private val hazards = setOf(Material.LAVA, Material.WATER, Material.FIRE, Material.SOUL_FIRE,
        Material.CACTUS, Material.MAGMA_BLOCK, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH)
    /**
     * Set whenever a lookup lands in a chunk that is not loaded. Such a block is treated as
     * impassable, which is right for a route being walked now but must not be remembered: the
     * chunk is usually just about to load (a match that has only begun).
     */
    var sawUnloaded = false
    private val unloaded = Shape(true, emptyList())
    /** Most of an arena is air; every empty block shares this one shape. */
    private val open = Shape(false, emptyList())
    private fun shape(x: Int, y: Int, z: Int): Shape {
        if (y !in world.minHeight until world.maxHeight) return unloaded
        shapes[Cell(x,y,z)]?.let { return it }
        // Not stored, so the block is looked at again once its chunk is there.
        if (!world.isChunkLoaded(x shr 4, z shr 4)) { sawUnloaded = true; return unloaded }
        val block = world.getBlockAt(x,y,z)
        val hazard = block.type in hazards
        val boxes = block.collisionShape.boundingBoxes
        val made = if (!hazard && boxes.isEmpty()) open
            else Shape(hazard, boxes.map { it.clone().shift(x.toDouble(),y.toDouble(),z.toDouble()) })
        shapes[Cell(x,y,z)] = made
        return made
    }
    fun clear(x: Double, y: Double, z: Double): Boolean {
        val body = BoundingBox(x-.3,y+.025,z-.3,x+.3,y+1.8,z+.3)
        for (bx in floor(body.minX).toInt()..floor(body.maxX).toInt())
            for (bz in floor(body.minZ).toInt()..floor(body.maxZ).toInt())
                for (by in floor(y).toInt()..floor(body.maxY).toInt()) {
                    val cell = shape(bx,by,bz)
                    if (cell.hazard || cell.boxes.any { it.overlaps(body) }) return false
                }
        return true
    }
    fun surface(x: Int, z: Int, nearY: Double, up: Double = 1.25, down: Double = 3.0): RouteNode? {
        for (by in floor(nearY+up).toInt() downTo max(voidY, floor(nearY-down-1).toInt())) {
            val s = shape(x,by,z)
            if (s.hazard) continue
            val heights = s.boxes.filter { it.minX <= x+.5 && it.maxX >= x+.5 && it.minZ <= z+.5 && it.maxZ >= z+.5 }
                .map { it.maxY }.distinct().sortedDescending()
            for (y in heights) if (y <= nearY+up+.01 && y >= nearY-down && clear(x+.5,y,z+.5))
                return RouteNode(x,(y*16).roundToInt(),z)
        }
        return null
    }
    fun at(loc: Location, down: Double = 5.0) = surface(loc.blockX,loc.blockZ,loc.y,1.25,down)
    fun location(node: RouteNode) = Location(world,node.x+.5,node.y,node.z+.5)
    private fun traversable(a: RouteNode, b: RouteNode, jumping: Boolean): Boolean {
        val dist = hypot((b.x-a.x).toDouble(),(b.z-a.z).toDouble())
        val steps = ceil((dist + abs(b.y-a.y))*8).toInt().coerceAtLeast(8)
        val highJump = jumping && b.y>a.y+1.25
        if(highJump) {
            // A high ledge needs a mostly vertical double jump before moving over
            // its lip. Check that takeoff column instead of drawing a diagonal
            // through the wall, or skipping the ceiling underneath a canopy.
            var y=a.y+.125
            while(y<=b.y+.3) {
                if(!clear(a.x+.5,y,a.z+.5))return false
                y+=.125
            }
        }
        for (i in 1 until steps) {
            val t = i.toDouble()/steps
            // Sweep upward from the actual takeoff height. Starting the whole arc at
            // the higher landing height let routes jump straight through low ceilings.
            val y = if (highJump) b.y + sin(PI*t)*1.8
                else if (jumping) a.y + (b.y-a.y)*t + sin(PI*t) * (if(doubleJump) 1.8 else 1.05)
                else max(a.y,b.y)
            if (!clear(a.x+.5+(b.x-a.x)*t,y,a.z+.5+(b.z-a.z)*t)) return false
        }
        return true
    }
    fun neighbours(a: RouteNode): List<RouteEdge> {
        val result = ArrayList<RouteEdge>(12)
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
                val reach = if(doubleJump) 5.0 else 3.0
                val maxSteps = floor(reach / hypot(dx.toDouble(),dz.toDouble())).toInt()
                for (distance in 2..maxSteps) {
                    val land = surface(a.x+dx*distance,a.z+dz*distance,a.y,if(doubleJump) 4.0 else 1.0,3.0) ?: continue
                    if (traversable(a,land,true)) result.add(RouteEdge(land,true,a.distance(land)+3.0))
                    break
                }
            }
            // A charged double jump also reaches ledges above a normal one-block step.
            // Keep the lower walking surface as an alternative, including under tree canopies.
            if(doubleJump) {
                val high=surface(a.x+dx,a.z+dz,a.y,4.0,0.0)
                if(high!=null && high!=b && high.y>a.y+1.25 && traversable(a,high,true))
                    result.add(RouteEdge(high,true,a.distance(high)+3.0))
            }
        }
        return result
    }
}
