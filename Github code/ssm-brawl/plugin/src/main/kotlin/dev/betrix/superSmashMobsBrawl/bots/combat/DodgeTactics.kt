package dev.betrix.superSmashMobsBrawl.bots.combat

import dev.betrix.superSmashMobsBrawl.bots.BotDifficulty
import kotlin.math.hypot

/**
 * Getting out of the way of what kit abilities throw: a fireball, a skull, a block, a stream of
 * eggs. A bot used to take no notice of any of it: it walked straight down the line of a Magma
 * Blast it had a full second to step out of, which nobody does.
 *
 * It goes by what a player goes by. It has to see the thing coming (in front of it, nothing in
 * between, or right on top of it), it takes a moment to react, and then it steps to the side:
 * across the thing's path, to whichever side it is already nearer, as long as there is floor there.
 * Whether that is in time is a matter of how fast the thing is and how far off it was when it was
 * let go. Something slow from across the arena is stepped out of; an arrow from ten blocks is not.
 */
object DodgeTactics {
    /** How far off something in flight is made out. */
    const val SEEN = 24.0
    /** This near, it is noticed whichever way the bot is looking: it is heard coming. */
    const val FELT = 3.0
    /** How many ticks ahead a bot reckons where something is going. */
    const val HORIZON = 30.0
    /** Half a body's width, with a little to spare, and the gap wanted between a body and what goes past it. */
    const val BODY = 0.45
    const val MARGIN = 0.5
    const val HEIGHT = 1.8
    /** How far to the side it goes. */
    const val SIDESTEP = 2.5
    /** With nothing coming for this many ticks, the next thing is a new thing to react to. */
    const val LULL = 6

    /** How long after it first sees something coming a bot starts to move. */
    fun readyAfter(d: BotDifficulty) = d.reactionTicks * 2

    /**
     * How something passes a body: in how many [ticks] it is nearest, how near that is across the
     * ground ([miss]), and which [side] of its line the body is on (1 its left, -1 its right).
     */
    class Pass(val ticks: Double, val miss: Double, val side: Int)

    /**
     * Something at ([px], [pz]) moving ([vx], [vz]) a tick, and a body at ([cx], [cz]). Null when
     * it is standing still or going away.
     */
    fun pass(px: Double, pz: Double, vx: Double, vz: Double, cx: Double, cz: Double): Pass? {
        val speed = vx * vx + vz * vz
        if (speed < 1e-4) return null
        val ticks = ((cx - px) * vx + (cz - pz) * vz) / speed
        if (ticks <= 0.0) return null
        val miss = hypot(px + vx * ticks - cx, pz + vz * ticks - cz)
        val cross = vx * (cz - pz) - vz * (cx - px)
        return Pass(ticks, miss, if (cross >= 0.0) 1 else -1)
    }

    /**
     * Would it hit? It has to come by soon, near enough across the ground for something [radius]
     * wide, and at a height that meets a body ([above]: how far over the body's feet it is as it passes).
     */
    fun threatens(pass: Pass, radius: Double, above: Double): Boolean =
        pass.ticks <= HORIZON && pass.miss <= radius + BODY + MARGIN &&
            above >= -(radius + MARGIN) && above <= HEIGHT + radius + MARGIN

    /** The way out of its path: a step of one block across the way it is going, on the body's own side of it. */
    fun aside(vx: Double, vz: Double, side: Int): Pair<Double, Double> {
        val speed = hypot(vx, vz)
        if (speed < 1e-6) return 0.0 to 0.0
        return (-vz / speed * side) to (vx / speed * side)
    }

    /** How far to the side a body still has to get to be clear of it. */
    fun needs(pass: Pass, radius: Double) = (radius + BODY + MARGIN - pass.miss).coerceAtLeast(0.0)

    /** How far a spot at ([x], [z]) is from the line something at ([px], [pz]) is travelling along. */
    fun offLine(px: Double, pz: Double, vx: Double, vz: Double, x: Double, z: Double): Double {
        val speed = hypot(vx, vz)
        if (speed < 1e-6) return hypot(x - px, z - pz)
        return kotlin.math.abs(vx * (z - pz) - vz * (x - px)) / speed
    }

    /** Is a spot that far from its line out of its way? (Where a bot is already stepping to is kept to if it is: no dithering between two sides.) */
    fun clear(offLine: Double, radius: Double) = offLine >= radius + BODY + MARGIN
}

/**
 * Getting away from something that is going to go off where it is, and says so first: a Creeper
 * that has begun to hiss hangs in the air for a second and a half and then takes fifteen points
 * off whoever is beside it, less the further off they are, out to eight blocks. That is time
 * enough to be somewhere else, which is what everybody who hears it does. A bot used to stand
 * there hitting it.
 */
object BlastTactics {
    /** A wind-up this near is heard whichever way the bot is looking. */
    const val HEARD = 16.0
    /** How far outside its reach to get, and how far outside it a bot waits for it to go off rather than walk back in. */
    const val CLEAR = 1.5
    const val WAIT = 3.0
    /** The ways to run, in degrees off straight away from it: straight first, then round to either side. */
    val TURNS = doubleArrayOf(0.0, -30.0, 30.0, -60.0, 60.0)
    /** Where there is not floor enough to get clear: as far as there is. Every block further is less of it. */
    val SHORTER = doubleArrayOf(6.0, 4.0, 2.5)
    /** With fewer ticks than this left, running gets nowhere. */
    const val TOO_LATE = 4

    fun readyAfter(d: BotDifficulty) = d.reactionTicks * 2

    /** Is a body [gap] blocks from the middle of something that reaches [radius] in it? */
    fun inReach(gap: Double, radius: Double) = gap <= radius + 0.5

    /** Just outside it: where a bot stays put until it has gone off. */
    fun waitsOutside(gap: Double, radius: Double) = gap > radius + 0.5 && gap <= radius + WAIT

    /** How far it has to run from where it is to be clear: never less than a few steps. */
    fun run(gap: Double, radius: Double) = (radius + CLEAR - gap).coerceAtLeast(SHORTER.last())

    /**
     * A place to run to: [far] blocks from where the body is, [turn] degrees off straight away from
     * the middle of it. ([dx], [dz]) is where the body is from the middle; the answer is where to
     * go from the body.
     */
    fun way(dx: Double, dz: Double, turn: Double, far: Double): Pair<Double, Double> {
        val gap = hypot(dx, dz)
        val ux = if (gap > 1e-6) dx / gap else 1.0
        val uz = if (gap > 1e-6) dz / gap else 0.0
        val rad = Math.toRadians(turn)
        val cos = kotlin.math.cos(rad)
        val sin = kotlin.math.sin(rad)
        return (ux * cos - uz * sin) * far to (ux * sin + uz * cos) * far
    }
}

/**
 * Keeping out of something that stands in the arena and hurts whoever touches it: a Mooshroom's
 * Spore Wall, three blocks wide and three high, there for eight seconds and good for four points
 * and a shove every half second. A bot used to go on pushing against it for as long as it stood
 * (it was pressed in bot hands for 23 points a time, more than anything else that kit has), which
 * nobody does twice: they go round the end of it, or wait for it to come down.
 *
 * So once a bot has seen one (or walked into it), it does not walk into what it reaches. The way
 * it was going is turned aside by as little as will clear it, round the end the way already leans
 * to, and the routes are planned round it too. A wall right across the only way (a bridge) is
 * waited out in front of.
 */
object WallTactics {
    /** How far off a wall is made out when it is in front of the bot. */
    const val SEEN = 16.0
    /** This near it is noticed whichever way the bot is looking: it has walked into it, or all but. */
    const val FELT = 2.5
    /** Half a body's width, and the room a bot keeps beyond what the wall reaches. */
    const val BODY = 0.3
    const val MARGIN = 0.3
    const val HEIGHT = 1.8
    /** How far ahead of its feet a bot looks along the way it is about to walk. */
    const val AHEAD = 1.2
    /** How far off the way it was going it turns to get past, in degrees: as little as will do. */
    val TURNS = doubleArrayOf(30.0, 60.0, 90.0, 120.0)
    /** Ticks it keeps to the end it has started round by: no going back and forth in front of it. */
    const val KEEP = 15

    /** How long after it first knows of a wall a bot keeps out of it. (Long enough to have walked into it once.) */
    fun readyAfter(d: BotDifficulty) = d.reactionTicks * 2

    /** How far across the ground a spot is from the blocks of a wall (0: over them). */
    fun gap(x: Double, z: Double, minX: Double, minZ: Double, maxX: Double, maxZ: Double): Double =
        hypot(maxOf(minX - x, 0.0, x - maxX), maxOf(minZ - z, 0.0, z - maxZ))

    /**
     * Would a body standing at ([x], [z]) be touched by it, with [margin] to spare? (Square, not
     * round: what the wall hits is whatever overlaps its blocks made [reach] bigger all round.)
     */
    fun touches(x: Double, z: Double, minX: Double, minZ: Double, maxX: Double, maxZ: Double, reach: Double, margin: Double = MARGIN): Boolean {
        val e = reach + BODY + margin
        return x > minX - e && x < maxX + e && z > minZ - e && z < maxZ + e
    }

    /** Is a body with its feet at [y] at the height of it at all? */
    fun level(y: Double, minY: Double, maxY: Double, reach: Double) = y + HEIGHT > minY - reach && y < maxY + reach

    /**
     * The ways out for a body that is in it, the shortest first: where to step to from where it
     * is, a little beyond each of the four sides.
     */
    fun waysOut(x: Double, z: Double, minX: Double, minZ: Double, maxX: Double, maxZ: Double, reach: Double): List<Pair<Double, Double>> {
        val e = reach + BODY + MARGIN
        return listOf(
            Triple(x - (minX - e), -1.0, 0.0), Triple(maxX + e - x, 1.0, 0.0),
            Triple(z - (minZ - e), 0.0, -1.0), Triple(maxZ + e - z, 0.0, 1.0),
        ).sortedBy { it.first }.map { (far, ux, uz) -> ux * (far + 0.4) to uz * (far + 0.4) }
    }
}

/**
 * Not walking at somebody in a straight line. Most of what a kit throws is in the air for a third
 * of a second, which is about what it takes to see it and start moving: stepping out of its way
 * after it has left is mostly too late (measured: a bot that had reacted was one or two ticks from
 * being hit, nine times in ten). What makes a shot miss a player is that they were already moving
 * somewhere the shooter did not expect. So a bot coming at somebody who is facing it and has been
 * throwing things at it, from further off than arm's length, comes in a zigzag: a little off the
 * straight line to one side, then the other, turning at no regular time. A shot led by where it
 * was going lands where it is not. (Only on open floor, and never in the last steps before a jump.)
 */
object WeaveTactics {
    /** Nearer than this it is a fight at arm's length, and circling is what it does there. */
    const val FROM = 5.0
    /** Further than this nobody is throwing anything at it yet. */
    const val TO = 22.0
    /** They are facing it: it is within this many degrees of where they look. */
    const val FACING = 30.0
    /** How far off the straight line it walks, in degrees: still mostly forwards, and still at a run. */
    const val ANGLE = 35.0
    /** Ticks on one side before it turns to the other: never the same twice running. */
    const val SHORTEST = 5
    const val LONGEST = 13
    /** Floor this far out to the side it is turning to, or it does not turn that way. */
    const val ROOM = 2.0
    /**
     * Only at somebody who has had something in the air coming at it within this many ticks:
     * nobody jinks at an opponent who is only looking at them.
     */
    const val WARY = 100
    /** With a jump or a climb among this many next steps of its way, it walks straight: those are lined up for. */
    const val STRAIGHT = 4

    /** Only players who move well do it. */
    fun weaves(d: BotDifficulty) = d.strafes

    /** Are they looking its way? [off] is how many degrees round from where they look the bot is. */
    fun facing(off: Double) = kotlin.math.abs(off) <= FACING

    /** How long to keep to one side, from a roll between 0 and 1. */
    fun stretch(roll: Double) = SHORTEST + (roll.coerceIn(0.0, 0.999) * (LONGEST - SHORTEST + 1)).toInt()

    /** The movement keys turned [degrees] to one side: as fast as before, in a different direction. */
    fun turned(forward: Float, strafe: Float, degrees: Double): Pair<Float, Float> {
        val rad = Math.toRadians(degrees)
        val cos = kotlin.math.cos(rad)
        val sin = kotlin.math.sin(rad)
        return (forward * cos - strafe * sin).toFloat() to (forward * sin + strafe * cos).toFloat()
    }
}
