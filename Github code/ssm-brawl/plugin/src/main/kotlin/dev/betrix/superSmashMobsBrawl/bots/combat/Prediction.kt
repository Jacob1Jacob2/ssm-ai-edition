package dev.betrix.superSmashMobsBrawl.bots.combat

import kotlin.math.hypot

/** Where a moving fighter will be, so Expert bots path to the interception point, not the old spot. */
object Prediction {
    data class Point(val x: Double, val y: Double, val z: Double)

    /** Blocks per tick a sprinting fighter covers on flat ground. */
    const val RUN_SPEED = 0.28
    private const val MAX_TICKS = 15

    /** How far ahead to look: roughly the time it takes to run [distance] blocks, capped. */
    fun leadTicks(distance: Double): Int = (distance / RUN_SPEED / 2).toInt().coerceIn(0, MAX_TICKS)

    /**
     * Position after [ticks] ticks. Walking speed carries on unchanged; in the air the fighter
     * follows the game's jump physics (gravity 0.08, drag 0.98 vertical and 0.91 horizontal) and
     * never sinks below [floorY], the ground it is expected to land on.
     */
    fun position(at: Point, vx: Double, vy: Double, vz: Double, onGround: Boolean, ticks: Int, floorY: Double = at.y): Point {
        if (onGround) return Point(at.x + vx * ticks, at.y, at.z + vz * ticks)
        var x = at.x; var y = at.y; var z = at.z
        var dx = vx; var dy = vy; var dz = vz
        repeat(ticks) {
            x += dx; y += dy; z += dz
            dy = (dy - 0.08) * 0.98; dx *= 0.91; dz *= 0.91
            if (y <= floorY) return Point(x, floorY, z)
        }
        return Point(x, y, z)
    }

    /** True when the prediction is too close to the present to be worth re-routing for. */
    fun negligible(from: Point, to: Point) = hypot(to.x - from.x, to.z - from.z) < 0.75
}
