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
 * down first and then across one axis at a time, then gravity (vy = (vy - 0.08) * 0.98) and drag
 * (x 0.91 in the air). Flown that way a jump in the head is the jump in the game to the millimetre
 * (bots debug with bots flights puts the two side by side, tick for tick), and that is the
 * standard to keep it to: every time the two were a few centimetres apart, some bot somewhere
 * clipped a corner its jump had been seen to clear.
 *
 * What a promise still has to allow for is where the bot stands when it jumps: a landing has to
 * hold with the body [EDGE] further out any way ([firm]), and a jump is only promised if it also
 * comes off from a little to each side of the middle of its block ([promised]).
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
     * The standard double jump (DoubleJumpPassive with 0.9 across and 0.9 up): the look is
     * flattened onto a fixed lift, so flatter is further and steeper is higher, and even looking
     * level it goes up at 0.6.
     */
    val STANDARD = Launch { pitch ->
        val h = cos(Math.toRadians(pitch))
        val n = sqrt(h * h + 0.81)
        (0.9 * h / n) to min(0.9, 0.81 / n)
    }

    /**
     * The slime's and the magma cube's (the same passive with 1.2 across and 1.0 up). Further and
     * higher than the standard one, and never low: looking level it still goes up at 0.92, five
     * blocks of rise, so under a ceiling it is the worse jump of the two.
     */
    val BOUNCY = Launch { pitch ->
        val h = cos(Math.toRadians(pitch))
        val n = sqrt(h * h + 1.44)
        (1.2 * h / n) to min(1.0, 1.44 / n)
    }

    /** The wolf's pounce (WolfJumpPassive): exactly where it looks, at 1.1. Level is all speed across and no lift. */
    val WOLF = Launch { pitch ->
        val r = Math.toRadians(pitch)
        (1.1 * cos(r)) to (-1.1 * kotlin.math.sin(r))
    }

    /** The spider's leap (SpiderLeapPassive): exactly where it looks, at 1.0. */
    val SPIDER = Launch { pitch ->
        val r = Math.toRadians(pitch)
        cos(r) to -kotlin.math.sin(r)
    }

    /**
     * The goat's leap with nothing charged (GoatedLeapPassive): always the full 0.9 up, whatever
     * it looks at, and 0.9 across when it looks level. (Charge only adds lift.)
     */
    val GOAT = Launch { pitch -> (0.9 * cos(Math.toRadians(pitch))) to 0.9 }

    /**
     * The goat's leap half charged and fully charged (a second and a half, and three seconds, of
     * crouching on the ground): 1.45 and 2.0 up, which is eleven and twenty blocks of rise.
     */
    val GOAT_HALF = Launch { pitch -> (0.9 * cos(Math.toRadians(pitch))) to (0.9 + 0.5 * 1.1) }
    val GOAT_FULL = Launch { pitch -> (0.9 * cos(Math.toRadians(pitch))) to (0.9 + 1.1) }

    const val GRAVITY = 0.08
    const val FALL_DRAG = 0.98
    const val AIR_DRAG = 0.91
    /** A fighter's speed on the ground as the game has it, walking and sprinting. */
    const val WALK = 0.1
    const val SPRINT = 0.13
    /**
     * The push of a running jump: the sprint-jump boost, and the tick of running it leaves the
     * ground on. [run] is the speed the game moves it on the ground with that tick, which is still
     * its walking speed if it was not sprinting the tick before (the game is a tick late with that).
     * [boost] is whether the game gives the sprint-jump boost at all: this server gives a player
     * none within a quarter of a second of its last jump (a bot waits that out: BotBrain.startGapJump).
     */
    fun takeoff(run: Double, boost: Boolean = true): Double = (if (boost) 0.2 else 0.0) + run * 0.98
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
    /**
     * The game's own answer to "is this body inside that block": no slack to speak of. (A jump
     * clears the block it is jumping onto by a millimetre and a third, and that is a jump every
     * player makes without a thought: asking for more room than the game does is as wrong as
     * asking for less.)
     */
    const val EXACT = 0.001
    /** What the game itself lets two things overlap by before it calls it touching. */
    const val TOUCH = 1.0e-7
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
    private fun steer(f: Flight, tx: Double, ty: Double, tz: Double, faceX: Double = tx, faceZ: Double = tz) {
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
        // (The fighter decides on the speed it sees. The game then forgets a speed too small to
        // matter before it moves anything: under 0.003 across, and the same up or down.)
        if (f.vx * f.vx + f.vz * f.vz < 9.0e-6) { f.vx = 0.0; f.vz = 0.0 }
        if (abs(f.vy) < 0.003) f.vy = 0.0
        if (e < 0.02) return
        // Pushing the way it faces a fighter sprints; any other way it does not. And it faces its
        // landing all the way ([faceX], [faceZ]), also while it is holding the column it took off in.
        val fx = faceX - f.x
        val fz = faceZ - f.z
        val fr = hypot(fx, fz)
        val forward = if (fr > 1e-3) (ex * fx + ez * fz) / (e * fr) else 0.0
        val push = (if (forward > 0.8) 0.026 else 0.02) * 0.98
        f.vx += ex / e * push
        f.vz += ez / e * push
    }

    /**
     * Moves the body along one axis by ([dx], [dy], [dz]) as far as there is room for, the way
     * the game does: up to what is in the way, and no further. True if the whole of it was made.
     */
    private fun go(space: Space, f: Flight, dx: Double, dy: Double, dz: Double): Boolean {
        if (space.clear(f.x + dx, f.y + dy, f.z + dz, EXACT)) {
            f.x += dx; f.y += dy; f.z += dz
            return true
        }
        // As far as it gets. (Up against it already, that is nowhere: one question settles that.)
        val whole = abs(dx) + abs(dy) + abs(dz)
        var free = 0.001 / whole
        if (free < 1.0 && space.clear(f.x + dx * free, f.y + dy * free, f.z + dz * free, EXACT)) {
            var stuck = 1.0
            repeat(12) {
                val mid = (free + stuck) / 2
                if (space.clear(f.x + dx * mid, f.y + dy * mid, f.z + dz * mid, EXACT)) free = mid else stuck = mid
            }
            f.x += dx * free; f.y += dy * free; f.z += dz * free
        }
        return false
    }

    /**
     * One tick's move, made the way the game makes it: up or down first, then across one axis at
     * a time, the one it is going faster along first, each as far as there is room for. Whatever
     * stops it takes its speed along that axis and no other. True if it came down on something.
     *
     * (It used to go across both ways at once, and so slipped diagonally past the corner of a
     * block the game runs it into: a wolf promised a ledge that way lost its speed across on a
     * corner at head height, and fell.)
     */
    private fun move(space: Space, f: Flight): Boolean {
        var landed = false
        if (f.vy != 0.0 && !go(space, f, 0.0, f.vy, 0.0)) {
            // Something overhead only ends the rise. Something underfoot is a landing.
            if (f.vy <= 0) landed = true
            f.vy = 0.0
        }
        across(space, f)
        return landed
    }

    /** The part of a tick's move that goes across. */
    private fun across(space: Space, f: Flight) {
        val xFirst = abs(f.vx) >= abs(f.vz)
        if (!xFirst && f.vz != 0.0 && !go(space, f, 0.0, 0.0, f.vz)) f.vz = 0.0
        if (f.vx != 0.0 && !go(space, f, f.vx, 0.0, 0.0)) f.vx = 0.0
        if (xFirst && f.vz != 0.0 && !go(space, f, 0.0, 0.0, f.vz)) f.vz = 0.0
    }

    /**
     * The move of the tick a fighter leaves the ground. As any other ([move]), but for one thing
     * the game does for whoever was standing: run into something across, it tries the same move
     * from each height up to 0.6 that a block's top can be at, and if one of those gets it
     * further, that is the move it makes. So a jump at the foot of a slab or a stair is a step up
     * onto it and no jump at all, and a jump into a doorway two blocks high goes through along
     * the floor. (The rise is lost with it: it stands on the step the tick after.) True if that
     * is what became of the jump.
     */
    private fun pushOff(space: Space, f: Flight): Boolean {
        val x0 = f.x
        val y0 = f.y
        val z0 = f.z
        val vx = f.vx
        val vz = f.vz
        move(space, f)
        if (f.vx == vx && f.vz == vz) return false
        val reached = (f.x - x0) * (f.x - x0) + (f.z - z0) * (f.z - z0)
        // (Every block's top is a sixteenth of something.)
        for (sixteenths in 0..9) {
            val step = Flight(x0, y0, z0, vx, 0.0, vz)
            if (sixteenths > 0) go(space, step, 0.0, sixteenths / 16.0, 0.0)
            across(space, step)
            if ((step.x - x0) * (step.x - x0) + (step.z - z0) * (step.z - z0) > reached + 1e-9) {
                f.x = step.x; f.y = step.y; f.z = step.z
                f.vx = step.vx; f.vz = step.vz; f.vy = 0.0
                return true
            }
        }
        return false
    }

    /**
     * Would the game make a step of a running jump from ([sx], [ay], [sz]) at ([bx], [by], [bz]),
     * and not a jump at all ([pushOff])? A bot that knows walks onto the step instead: that is
     * all the game was going to let it do, and a walk does not send it on over the far side.
     */
    fun stepsUp(space: Space, sx: Double, ay: Double, sz: Double, bx: Double, by: Double, bz: Double,
                vx0: Double = 0.0, vz0: Double = 0.0, run: Double = WALK, boost: Boolean = true): Boolean {
        val r0 = hypot(bx - sx, bz - sz)
        if (r0 < 0.01) return false
        val push = takeoff(run, boost)
        return pushOff(space, Flight(sx, ay, sz, vx0 + (bx - sx) / r0 * push, 0.42, vz0 + (bz - sz) / r0 * push))
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
    private fun fly(space: Space, f: Flight, tx: Double, ty: Double, tz: Double, high: Boolean, holdX: Double, holdZ: Double,
                    seen: MutableList<Flight>? = null): Boolean {
        for (t in 1..70) {
            if (high && f.y < ty - 0.15) steer(f, holdX, f.y - 1.0, holdZ, tx, tz) else steer(f, tx, ty, tz)
            val landed = move(space, f)
            seen?.add(f.copy())
            if (landed) return onTarget(space, f, tx, ty, tz)
            f.vy = (f.vy - GRAVITY) * FALL_DRAG
            f.vx *= AIR_DRAG
            f.vz *= AIR_DRAG
            if (f.vy < -0.1 && f.y < ty - 2.5) return false
        }
        return false
    }

    /**
     * Does a body in the air at [from] come down on ([tx], [ty], [tz]) as it is going, steered at
     * it all the way and with no air jump? This is what decides whether a jump takes its air
     * jump: a guess at it (so far to go, so fast, so long to fall) left out what steering makes
     * up, and spent the air jump on hops the plain jump was making anyway, under a low roof for one.
     */
    fun lands(space: Space, from: Flight, tx: Double, ty: Double, tz: Double,
              high: Boolean = false, holdX: Double = from.x, holdZ: Double = from.z): Boolean =
        fly(space, from.copy(), tx, ty, tz, high, holdX, holdZ)

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
            if (!high && h > 1e-6 && space.clear(from.x, from.y + v, from.z, EXACT)) {
                val first = Flight(from.x, from.y + v, from.z, ux * h, v, uz * h)
                across(space, first)
                val kept = hypot(first.vx, first.vz) / h
                if (kept < 1.0 - 1e-9 && kept * dists[i] + steerGain(falls[i] - 1) < r - 0.8) continue
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

    /**
     * Debug: the air jump at [pitch] from where [from] is, as it is flown in the head toward
     * ([tx], [ty], [tz]): where it is after each tick, to hold up against what the fighter then does.
     */
    fun path(space: Space, launch: Launch, from: Flight, pitch: Double, tx: Double, ty: Double, tz: Double,
             high: Boolean = false, holdX: Double = from.x, holdZ: Double = from.z): List<Flight> {
        val r = hypot(tx - from.x, tz - from.z)
        val (h, v) = launch.speeds(pitch)
        val f = from.copy()
        f.vx = if (r > 1e-6) (tx - from.x) / r * h else 0.0
        f.vz = if (r > 1e-6) (tz - from.z) / r * h else 0.0
        f.vy = v
        val seen = ArrayList<Flight>()
        fly(space, f, tx, ty, tz, high, holdX, holdZ, seen)
        return seen
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
     * the speed the fighter already has along the ground as it pushes off, and [run] and [boost]
     * what the game gives it to push off with (see [takeoff]).
     */
    fun jump(space: Space, launch: Launch?, sx: Double, ay: Double, sz: Double, bx: Double, by: Double, bz: Double,
             high: Boolean = highLedge(sx, ay, sz, bx, by, bz), runOut: Boolean = false, vx0: Double = 0.0, vz0: Double = 0.0,
             seen: MutableList<Flight>? = null, run: Double = WALK, steps: Int = 0, boost: Boolean = true): Boolean {
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
        if (!high) { val push = takeoff(run, boost); f.vx += ux * push; f.vz += uz * push }
        if (pushOff(space, f)) {
            seen?.add(f.copy())
            // The game made a step of it. The fighter is standing on what it ran into, and goes
            // on from there the same way: another step (that is a flight of stairs), or the jump.
            if (onTarget(space, f, bx, by, bz)) return end(true, "stepped up")
            if (steps >= 2) return end(false, "stepped up, and no nearer")
            return jump(space, launch, f.x, f.y, f.z, bx, by, bz, runOut = runOut,
                vx0 = f.vx * GROUND_DRAG * AIR_DRAG, vz0 = f.vz * GROUND_DRAG * AIR_DRAG, run = run, steps = steps + 1)
        }
        seen?.add(f.copy())
        f.vy = (f.vy - GRAVITY) * FALL_DRAG
        f.vx *= GROUND_DRAG
        f.vz *= GROUND_DRAG
        var jumped = false
        for (age in 2..90) {
            // The air jump only if the plain jump will not make it. From the top of the arc on,
            // the jump as it is going is flown on to its end: if that comes down on the landing
            // there is nothing more to decide, and if not, this is the moment for the air jump.
            // (The bot decides it the same way, tick by tick: see [lands].)
            if (launch != null && !jumped && age >= 3 && f.vy < 0.05) {
                val rest = f.copy()
                val more = if (seen != null) ArrayList<Flight>() else null
                if (fly(space, rest, bx, by, bz, high, sx, sz, more)) {
                    if (more != null) seen?.addAll(more)
                    f.x = rest.x; f.y = rest.y; f.z = rest.z
                    return end(true, "came down")
                }
                val remaining = hypot(bx - f.x, bz - f.z)
                pitch = aim(space, launch, f, bx, by, bz, runOut, high, sx, sz).pitch
                val (h, v) = launch.speeds(pitch)
                f.vx = if (remaining > 1e-6) (bx - f.x) / remaining * h else 0.0
                f.vz = if (remaining > 1e-6) (bz - f.z) / remaining * h else 0.0
                f.vy = v
                jumped = true
            }
            if (high && f.y < by - 0.15) steer(f, sx, f.y - 1.0, sz, bx, bz) else steer(f, bx, by, bz)
            val landed = move(space, f)
            // (Debug: the jump as far as its air jump. From there on the bot aims again, from where it really is.)
            if (!jumped) seen?.add(f.copy())
            if (landed) return end(onTarget(space, f, bx, by, bz), "came down")
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
    const val HIGHEST = 21.5
    /** Further than a jump is ever looked for. */
    const val FURTHEST = 16.0

    /**
     * How far one kind of air jump goes: in open air with nothing in the way, the furthest a jump
     * gets and still comes down on a block so much above (or below) where it started. Worked out
     * once for each kind of jump, by flying it.
     */
    class Reach internal constructor(private val launch: Launch) {
        private val table: DoubleArray by lazy {
            DoubleArray(((HIGHEST - LOWEST) * 2).toInt() + 1) { i ->
                val dy = LOWEST + i * 0.5
                var r = FURTHEST
                while (r >= 1.0 && !promised(twoBlocks(r, dy), launch, 0.5, 0.0, 0.5, r + 0.5, dy, 0.5)) r -= 0.25
                r
            }
        }

        /** The furthest across it lands on something [dy] higher (negative: lower) than its take-off. */
        fun at(dy: Double): Double {
            if (dy > HIGHEST || dy < LOWEST) return 0.0
            // The next height up on the table: the harder of the two jumps either side.
            return table[kotlin.math.ceil((dy - LOWEST) * 2 - 1e-9).toInt().coerceIn(0, table.size - 1)]
        }

        /** The highest landing it gets onto at all, from at least a block away. */
        val rise: Double by lazy {
            var top = 0.0
            var dy = 0.5
            while (dy <= HIGHEST) { if (at(dy) >= 1.0) top = dy; dy += 0.5 }
            top
        }
    }

    private val reaches = java.util.concurrent.ConcurrentHashMap<Launch, Reach>()

    /** The reach of the air jump [launch] (one of the kinds above: they are looked up by which one it is). */
    fun reachOf(launch: Launch): Reach = reaches.getOrPut(launch) { Reach(launch) }

    /** Nothing in the world but the block taken off from and the block to land on, [r] away and [dy] up. */
    private fun twoBlocks(r: Double, dy: Double) = Space { x, y, z, foot ->
        fun hits(bx: Double, top: Double) =
            x + 0.3 - TOUCH > bx && x - 0.3 + TOUCH < bx + 1.0 && z + 0.3 - TOUCH > 0.0 && z - 0.3 + TOUCH < 1.0 && y + foot < top && y + 1.8 > top - 1.0
        !hits(0.0, 0.0) && !hits(r, dy)
    }

    /** The furthest across a standard double jump lands on something [dy] higher (negative: lower) than its take-off. */
    fun reach(dy: Double): Double = reachOf(STANDARD).at(dy)
}
