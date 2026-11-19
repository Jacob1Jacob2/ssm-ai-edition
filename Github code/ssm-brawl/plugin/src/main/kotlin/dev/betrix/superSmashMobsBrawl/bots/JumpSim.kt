package dev.betrix.superSmashMobsBrawl.bots

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A fighter's jump, flown in the head tick by tick the way the game flies it.
 *
 * The route planner used to decide what could be jumped with a few rules of thumb (six blocks
 * across, a parabola with room over it). Those rules are short of what a double jump does, and
 * blind to what a player does without thinking: hop a hatch under a low ceiling, go up a shaft
 * and across at the top. A bot told "there is no way" stands and looks at a camper a player would
 * simply have jumped to. So the question is put to the physics instead: push off, steer, air jump
 * when the plain jump will not do, bump the head, slide up a wall, come down, and see where.
 *
 * It flies the jump exactly the way [BotBrain]'s gap jump does, because the bot aims its air jump
 * with [aim] as well: what the planner promised is what the bot then does.
 *
 * The game's own numbers, per tick: the controls push 0.02 (0.026 sprinting), then the move, up or
 * down first and across second, then gravity (vy = (vy - 0.08) * 0.98) and drag (x 0.91 in the air).
 *
 * A promise has to hold when the real jump is a little off the one flown here, so wherever a
 * centimetre decides, the answer is no: going across, whatever is underfoot has to be cleared by
 * [OVER]; a landing has to hold with the body [EDGE] further out any way ([firm]); and a jump is
 * only promised if it also comes off from a little to each side of the middle of its block
 * ([promised]).
 */
object JumpSim {
    /**
     * Is there room for a body standing with its feet at this point? [foot] is how much of the
     * very bottom of the body is left out of the question (so that standing on a block is not
     * being inside it); negative asks for that much room under the feet as well.
     */
    fun interface Space { fun clear(x: Double, y: Double, z: Double, foot: Double): Boolean }

    /** What an air jump gives when the fighter looks [pitch] degrees (negative is up): speed across, and speed up. */
    fun interface Launch { fun speeds(pitch: Double): Pair<Double, Double> }

    /**
     * The standard double jump. Every kit's own air jump goes at least as far and as high as
     * this one in every direction (a wolf's, a spider's and a goat's a good deal further), so a
     * jump that this makes, they all make.
     */
    val STANDARD = Launch { pitch ->
        val h = cos(Math.toRadians(pitch))
        val n = sqrt(h * h + 0.81)
        (0.9 * h / n) to min(0.9, 0.81 / n)
    }

    const val GRAVITY = 0.08
    const val FALL_DRAG = 0.98
    const val AIR_DRAG = 0.91
    /** The push of a running jump from a standing start: the sprint-jump boost and one tick of running. */
    const val TAKEOFF = 0.2 + 0.13 * 1.3274 * 0.98
    /** The tick it leaves the ground the fighter is still on it: the floor's drag, not the air's. */
    const val GROUND_DRAG = 0.546
    /** An air jump still covering this much a tick as it comes down is a flat, fast one. */
    const val FAST_ARC = 0.45
    /** How far short of the middle of its landing a fast jump may be a tick before it comes down. */
    const val LIP_REACH = 0.7
    /**
     * Air jumps tried in the head before settling for the best guess: the most promising first
     * (those that would come down nearest the landing in open air), then, if something was in the
     * way of all of them, whatever else could still get there (a steeper jump that goes over it).
     */
    private const val TRIES = 6
    private const val MORE = 10
    /** The game's own answer to "is this body inside that block": no slack to speak of. */
    const val EXACT = 0.001
    /** Going across, a block underfoot has to be cleared by this much (it is passed to [Space.clear] as a negative foot). */
    const val OVER = -0.05
    /** A landing has to hold with the body this much further out in any direction. */
    const val EDGE = 0.15
    /** A bot lines up for a long jump this near the middle of its block; the jump has to come off from anywhere that near. */
    const val LINE_UP = 0.2

    /** Ticks until, falling from [y0] with vertical speed [vy0], a body is below height [y] (60 at most). */
    fun ticksUntilBelow(y: Double, vy0: Double, y0: Double): Int {
        var py = y0
        var v = vy0
        for (t in 1..60) {
            py += v
            v = (v - GRAVITY) * FALL_DRAG
            if (v < 0 && py <= y) return t
        }
        return 60
    }

    /** The highest point reached from [y0] with vertical speed [vy0]. */
    fun peakHeight(vy0: Double, y0: Double): Double {
        var py = y0
        var v = vy0
        while (v > 0) {
            py += v
            v = (v - GRAVITY) * FALL_DRAG
        }
        return py
    }

    /** How far a body coasts in [ticks] ticks from speed [v], with nothing but the air's drag. */
    fun coastDistance(v: Double, ticks: Int): Double = v * (1 - AIR_DRAG.pow(ticks.toDouble())) / (1 - AIR_DRAG)

    /** How far the controls alone carry a body in [ticks] ticks of pushing the same way from a standstill. */
    private fun steerGain(ticks: Int): Double =
        0.026 * 0.98 / (1 - AIR_DRAG) * (ticks - AIR_DRAG * (1 - AIR_DRAG.pow(ticks.toDouble())) / (1 - AIR_DRAG))

    /** Debug: told how every whole jump flown in the head ended. Null (nobody listening) but in a test or a debug command. */
    @Volatile var trace: ((String) -> Unit)? = null

    /** A body in the air: where it is and how it is moving. */
    class Flight(var x: Double, var y: Double, var z: Double, var vx: Double = 0.0, var vy: Double = 0.0, var vz: Double = 0.0) {
        fun copy() = Flight(x, y, z, vx, vy, vz)
    }

    /** The air jump to make: the pitch to look, and whether it was seen to come down on the landing. */
    class Aim(val pitch: Double, val lands: Boolean)

    /**
     * The controls for one tick: the push that brings the speed across to "the speed that coasts
     * to a stop at the target". In the air that is all anybody can do.
     */
    private fun steer(f: Flight, tx: Double, ty: Double, tz: Double) {
        val dx = tx - f.x
        val dz = tz - f.z
        val r = hypot(dx, dz)
        val ticks = ticksUntilBelow(ty, f.vy, f.y).coerceAtLeast(1)
        val desired = min(0.7, r * (1 - AIR_DRAG) / (1 - AIR_DRAG.pow(ticks.toDouble())))
        val ux = if (r > 1e-3) dx / r else 0.0
        val uz = if (r > 1e-3) dz / r else 0.0
        val ex = ux * desired - f.vx
        val ez = uz * desired - f.vz
        val e = hypot(ex, ez)
        if (e < 0.02) return
        // Pushing the way it faces (at the landing) a fighter sprints; pulling back it does not.
        val push = (if ((ex * ux + ez * uz) / e > 0.8) 0.026 else 0.02) * 0.98
        f.vx += ex / e * push
        f.vz += ez / e * push
    }

    /** As far along a blocked move up or down as the body gets. */
    private fun settle(space: Space, f: Flight) {
        var free = 0.0
        var stuck = 1.0
        repeat(8) {
            val mid = (free + stuck) / 2
            if (space.clear(f.x, f.y + f.vy * mid, f.z, EXACT)) free = mid else stuck = mid
        }
        f.y += f.vy * free
    }

    /** One tick's move: up or down first, then across. True if it came down on something. */
    private fun move(space: Space, f: Flight): Boolean {
        var landed = false
        if (space.clear(f.x, f.y + f.vy, f.z, EXACT)) f.y += f.vy
        else {
            settle(space, f)
            // Something overhead only ends the rise. Something underfoot is a landing.
            if (f.vy <= 0) landed = true
            f.vy = 0.0
        }
        val nx = f.x + f.vx
        val nz = f.z + f.vz
        if (space.clear(nx, f.y, nz, OVER)) { f.x = nx; f.z = nz }
        else if (space.clear(nx, f.y, f.z, OVER)) { f.x = nx; f.vz = 0.0 }
        else if (space.clear(f.x, f.y, nz, OVER)) { f.z = nz; f.vx = 0.0 }
        else { f.vx = 0.0; f.vz = 0.0 }
        return landed
    }

    /** Is there something to stand on (or lean against) under a body at this point? */
    private fun held(space: Space, x: Double, y: Double, z: Double) = !space.clear(x, y - 0.06, z, EXACT)

    /** A landing that would still be one with the body a little further out in any direction: not the last sliver of an edge. */
    private fun firm(space: Space, f: Flight) =
        held(space, f.x + EDGE, f.y, f.z) && held(space, f.x - EDGE, f.y, f.z) && held(space, f.x, f.y, f.z + EDGE) && held(space, f.x, f.y, f.z - EDGE)

    /** (Within most of a block of its height: the low half of a stair is the stair, and the bot counts a landing the same way.) */
    private fun onTarget(space: Space, f: Flight, tx: Double, ty: Double, tz: Double) =
        abs(f.y - ty) < 0.8 && hypot(f.x - tx, f.z - tz) < 1.0 && firm(space, f)

    /**
     * The rest of a jump from where [f] is now, steered at the landing. Below the lip of a high
     * ledge ([high]) the fighter holds the column it took off in ([holdX], [holdZ]) and only goes
     * across once it is over it. True if it comes down on the landing; [f] is where it ended.
     */
    private fun fly(space: Space, f: Flight, tx: Double, ty: Double, tz: Double, high: Boolean, holdX: Double, holdZ: Double): Boolean {
        for (t in 1..70) {
            if (high && f.y < ty - 0.15) steer(f, holdX, f.y - 1.0, holdZ) else steer(f, tx, ty, tz)
            if (move(space, f)) return onTarget(space, f, tx, ty, tz)
            f.vy = (f.vy - GRAVITY) * FALL_DRAG
            f.vx *= AIR_DRAG
            f.vz *= AIR_DRAG
            if (f.vy < -0.1 && f.y < ty - 2.5) return false
        }
        return false
    }

    /** The old test of an air jump's arc: does it get to [tx], [tz] without coming up underneath something? */
    private fun arcClear(space: Space, from: Flight, tx: Double, ty: Double, tz: Double, horizontal: Double, vertical: Double): Boolean {
        val r = hypot(tx - from.x, tz - from.z)
        if (r < 0.5) return true
        val ux = (tx - from.x) / r
        val uz = (tz - from.z) / r
        var along = 0.0
        var y = from.y
        var vh = horizontal
        var vy = vertical
        for (t in 1..50) {
            if (!space.clear(from.x + ux * along, y + vy, from.z + uz * along, EXACT)) return vy <= 0
            y += vy
            if (space.clear(from.x + ux * (along + vh), y, from.z + uz * (along + vh), EXACT)) along += vh else vh = 0.0
            vh = vh * AIR_DRAG + 0.02
            vy = (vy - GRAVITY) * FALL_DRAG
            if ((vy < 0 && y <= ty + 0.3) || along >= r - 0.3) return true
        }
        return true
    }

    /**
     * The air jump to make from where [from] is, to come down on ([tx], [ty], [tz]).
     *
     * Every pitch is first judged by where its arc would come down in open air (the nearer the
     * landing the better, a little long rather than short). Then the most promising ones are
     * flown in the head, with everything that is really in the way, and the first that comes
     * down on the landing is the one. If none does, the best guess stands, as it always did.
     *
     * [runOut] says there is floor to run on past the landing, which is what a fast flat jump
     * (a wolf's, a spider's) needs to be allowed to come down a little long.
     */
    fun aim(space: Space, launch: Launch, from: Flight, tx: Double, ty: Double, tz: Double, runOut: Boolean = false,
            high: Boolean = false, holdX: Double = from.x, holdZ: Double = from.z): Aim {
        val r = hypot(tx - from.x, tz - from.z)
        val pitches = ArrayList<Double>(28)
        val speeds = ArrayList<Pair<Double, Double>>(28)
        val errors = ArrayList<Double>(28)
        // How far each coasts in open air, how long it is in the air, and the furthest it could
        // get with the controls pushed at the landing all the way.
        val dists = ArrayList<Double>(28)
        val falls = ArrayList<Int>(28)
        val most = ArrayList<Double>(28)
        var highest = -Double.MAX_VALUE
        var highestPitch = -85.0
        var pitch = 20.0
        while (pitch >= -88.0) {
            val launched = launch.speeds(pitch)
            val (horizontal, vertical) = launched
            val peak = peakHeight(vertical, from.y)
            // Of jumps that go equally high (every flap of a chicken does), the steepest.
            if (peak >= highest - 0.02) { highest = maxOf(highest, peak); highestPitch = pitch }
            if (peak >= ty + 0.3) {
                val ticks = ticksUntilBelow(ty, vertical, from.y)
                val dist = coastDistance(horizontal, ticks)
                var err = if (dist >= r) dist - r else (r - dist) * 1.5
                // A flat, fast jump has to be over the block a tick before it comes down, or it
                // drops past the lip and into the side of it.
                val before = coastDistance(horizontal, ticks - 1)
                if (dist - before > FAST_ARC) err = when {
                    before < r - LIP_REACH -> 2.0 + (r - LIP_REACH - before)
                    dist <= r -> (r - dist) * 0.3
                    else -> (dist - r) * (if (runOut && dist - r <= 1.0) 0.35 else 2.5)
                }
                pitches += pitch; speeds += launched; errors += err
                dists += dist; falls += ticks; most += dist + steerGain(ticks)
            }
            pitch -= 4.0
        }
        val ux = if (r > 1e-6) (tx - from.x) / r else 0.0
        val uz = if (r > 1e-6) (tz - from.z) / r else 0.0
        var flown = 0
        val tried = ArrayList<Pair<Double, Double>>(TRIES + MORE)
        for (i in pitches.indices.sortedBy { errors[it] }) {
            val (h, v) = speeds[i]
            // The same jump under another pitch (looking down gives a standard double jump what looking up does).
            if (tried.any { abs(it.first - h) < 1e-6 && abs(it.second - v) < 1e-6 }) continue
            if (flown >= TRIES && (flown >= TRIES + MORE || most[i] < r - 0.5)) continue
            tried += speeds[i]
            // Straight into something on the very first tick, the jump loses its speed across and
            // what is left is what the controls alone can do. Where that is nowhere near enough
            // there is no need to fly it to know: the way over is a steeper jump.
            if (!high && space.clear(from.x, from.y + v, from.z, EXACT)) {
                val y1 = from.y + v
                val nx = from.x + ux * h
                val nz = from.z + uz * h
                val kept = when {
                    space.clear(nx, y1, nz, OVER) -> 1.0
                    space.clear(nx, y1, from.z, OVER) -> abs(ux)
                    space.clear(from.x, y1, nz, OVER) -> abs(uz)
                    else -> 0.0
                }
                if (kept < 1.0 && kept * dists[i] + steerGain(falls[i] - 1) < r - 0.8) continue
            }
            flown++
            val f = from.copy()
            f.vx = ux * speeds[i].first
            f.vz = uz * speeds[i].first
            f.vy = speeds[i].second
            if (fly(space, f, tx, ty, tz, high, holdX, holdZ)) return Aim(pitches[i], true)
        }
        var bestErr = Double.MAX_VALUE
        var bestPitch = 0.0
        for (i in pitches.indices) {
            var err = errors[i]
            if (err < bestErr && !arcClear(space, from, tx, ty, tz, speeds[i].first, speeds[i].second)) err += 5.0
            if (err < bestErr) { bestErr = err; bestPitch = pitches[i] }
        }
        return Aim(if (bestErr == Double.MAX_VALUE) highestPitch else bestPitch, false)
    }

    /** Is ([x], [y], [z]) somewhere to stand: room for the body, and something under its feet? */
    private fun standable(space: Space, x: Double, y: Double, z: Double) = space.clear(x, y, z, EXACT) && !space.clear(x, y - 0.1, z, EXACT)

    /** A ledge taken straight up and then across (see [Mobility.straightUp]), going by the middles of the two blocks. */
    fun highLedge(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double) =
        by > ay + 1.3 && Mobility.straightUp(by - ay, hypot(bx - ax, bz - az))

    /**
     * A whole jump, from standing at ([sx], [ay], [sz]) to coming down on ([bx], [by], [bz]):
     * a running jump at it (or straight up first, for a ledge well overhead and close by: [high]),
     * the air jump from the top of the arc if the plain jump will not get there, steering all the way.
     * [launch] is the air jump in hand (null: none, the jump has to do it alone); [vx0], [vz0]
     * the speed the fighter already has along the ground as it pushes off.
     */
    fun jump(space: Space, launch: Launch?, sx: Double, ay: Double, sz: Double, bx: Double, by: Double, bz: Double,
             high: Boolean = highLedge(sx, ay, sz, bx, by, bz), runOut: Boolean = false, vx0: Double = 0.0, vz0: Double = 0.0): Boolean {
        val r0 = hypot(bx - sx, bz - sz)
        if (r0 < 0.01) return false
        val ux = (bx - sx) / r0
        val uz = (bz - sz) / r0
        val f = Flight(sx, ay, sz)
        var pitch = Double.NaN
        fun end(result: Boolean, how: String): Boolean {
            trace?.invoke("%.2f,%.2f,%.2f -> %.2f,%.2f,%.2f%s: %s at %.2f,%.2f,%.2f%s = %s".format(sx, ay, sz, bx, by, bz, if (high) " (straight up first)" else "",
                how, f.x, f.y, f.z, if (pitch.isNaN()) "" else ", air jump at pitch %.0f".format(pitch), result))
            return result
        }
        // Off the ground: straight up for a high ledge, at a run for everything else.
        f.vy = 0.42
        f.vx = vx0
        f.vz = vz0
        if (!high) { f.vx += ux * TAKEOFF; f.vz += uz * TAKEOFF }
        move(space, f)
        f.vy = (f.vy - GRAVITY) * FALL_DRAG
        f.vx *= GROUND_DRAG
        f.vz *= GROUND_DRAG
        var jumped = false
        for (age in 2..90) {
            // The air jump only if the plain jump will not make it, from the top of the arc on.
            if (launch != null && !jumped && age >= 3 && f.vy < 0.05) {
                val remaining = hypot(bx - f.x, bz - f.z)
                val fall = ticksUntilBelow(by, f.vy, f.y)
                val reach = coastDistance(hypot(f.vx, f.vz), fall)
                val steering = if (fall >= 12) min(1.5, 0.006 * fall * fall) else 0.0
                if (reach + steering < remaining - 0.3 || f.y < by - 0.3) {
                    pitch = aim(space, launch, f, bx, by, bz, runOut, high, sx, sz).pitch
                    val (h, v) = launch.speeds(pitch)
                    f.vx = if (remaining > 1e-6) (bx - f.x) / remaining * h else 0.0
                    f.vz = if (remaining > 1e-6) (bz - f.z) / remaining * h else 0.0
                    f.vy = v
                    jumped = true
                }
            }
            if (high && f.y < by - 0.15) steer(f, sx, f.y - 1.0, sz) else steer(f, bx, by, bz)
            if (move(space, f)) return end(onTarget(space, f, bx, by, bz), "came down")
            f.vy = (f.vy - GRAVITY) * FALL_DRAG
            f.vx *= AIR_DRAG
            f.vz *= AIR_DRAG
            if (jumped && f.vy < -0.1 && f.y < by - 2.5) return end(false, "fell past")
            if (f.y < by - 40.0) return end(false, "fell")
        }
        return end(false, "still in the air")
    }

    /**
     * A jump to promise a bot: from the middle of the block at ([ax], [ay], [az]) onto
     * ([bx], [by], [bz]), and just as well from a little forward, back and to either side of the
     * middle (wherever of those there is something to stand on). A bot lines up for a long jump
     * within [LINE_UP] of the middle of its block; a jump that only comes off from one exact spot
     * is not one to send it on.
     */
    fun promised(space: Space, launch: Launch?, ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Boolean {
        val r0 = hypot(bx - ax, bz - az)
        if (r0 < 0.01) return false
        val high = highLedge(ax, ay, az, bx, by, bz)
        if (!jump(space, launch, ax, ay, az, bx, by, bz, high)) return false
        val ux = (bx - ax) / r0 * LINE_UP
        val uz = (bz - az) / r0 * LINE_UP
        for (side in 0..3) {
            val x = ax + when (side) { 0 -> -ux; 1 -> ux; 2 -> -uz; else -> uz }
            val z = az + when (side) { 0 -> -uz; 1 -> uz; 2 -> ux; else -> -ux }
            if (!standable(space, x, ay, z)) continue
            if (!jump(space, launch, x, ay, z, bx, by, bz, high)) return false
        }
        return true
    }

    // ------------------------------------------------------------------ how far it goes at all

    /** Heights a landing may be at relative to the take-off, in half blocks from [LOWEST] up to [HIGHEST]. */
    const val LOWEST = -32.0
    const val HIGHEST = 6.5
    /** Further than a jump is ever looked for. */
    private const val FURTHEST = 14.0

    /**
     * In open air with nothing in the way: the furthest a jump gets and still comes down on a
     * block [dy] above (or below) where it started. Worked out once, by flying it.
     */
    private val reaches: DoubleArray by lazy {
        DoubleArray(((HIGHEST - LOWEST) * 2).toInt() + 1) { i ->
            val dy = LOWEST + i * 0.5
            var r = FURTHEST
            while (r >= 1.0 && !promised(twoBlocks(r, dy), STANDARD, 0.5, 0.0, 0.5, r + 0.5, dy, 0.5)) r -= 0.25
            r
        }
    }

    /** Nothing in the world but the block taken off from and the block to land on, [r] away and [dy] up. */
    private fun twoBlocks(r: Double, dy: Double) = Space { x, y, z, foot ->
        fun hits(bx: Double, top: Double) =
            x + 0.3 > bx && x - 0.3 < bx + 1.0 && z + 0.3 > 0.0 && z - 0.3 < 1.0 && y + foot < top && y + 1.8 > top - 1.0
        !hits(0.0, 0.0) && !hits(r, dy)
    }

    /** The furthest across a standard double jump lands on something [dy] higher (negative: lower) than its take-off. */
    fun reach(dy: Double): Double {
        if (dy > HIGHEST || dy < LOWEST) return 0.0
        // The next height up on the table: the harder of the two jumps either side.
        return reaches[kotlin.math.ceil((dy - LOWEST) * 2 - 1e-9).toInt().coerceIn(0, reaches.size - 1)]
    }
}
