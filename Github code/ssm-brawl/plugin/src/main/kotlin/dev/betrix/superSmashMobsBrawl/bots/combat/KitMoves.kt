package dev.betrix.superSmashMobsBrawl.bots.combat

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Abilities that carry, throw or swap the player who uses them, and what a bot has to know about
 * each to still be on the map afterwards. The kits are not touched: this is only how a bot presses
 * their buttons.
 *
 * Over 1,000 recorded matches (30,889 deaths) 716 bots died with nobody to blame. A kit lost a
 * life that way once in fifty to a hundred deaths, except four: Wither Skeleton (one in nine),
 * Cow, Magma Cube and Enderman (one in twenty to twenty-five). Each of the four has an ability
 * that takes its player further than a bot allowed for, and none of them was ever ended, aimed or
 * held back the way its player would:
 *  - a Guided Wither Skull's blast reaches its own player, and a bot ran toward the opponent it
 *    was guiding the skull at: a third of all the damage its skulls did was to itself, and 44 of
 *    its lives went off the map on its own blast;
 *  - a second press of Wither Image swaps places with the image wherever that is, in mid-air over
 *    the void included, and a bot pressed it whenever it was ready;
 *  - Milk Spiral carries a Cow 16 blocks and Flame Dash a Magma Cube 27 unless they are ended by
 *    pressing again, which a bot never did (it took them for 8 and 10);
 *  - Magma Blast throws its player back a block a tick, which in the air is ten blocks, not three;
 *  - Blink goes 16 blocks along the look whatever is there, and a bot looked at the opponent, so
 *    it came out as far past them as they were near.
 */
object SkullPlay {
    /** How far the blast reaches from where the skull goes off, along each axis (a box, not a ball). The one who sent it is hit like anybody. */
    const val BLAST = 6.0
    /** Half a body, and a step to spare. */
    const val MARGIN = 0.8
    /** Blocks a tick while it is guided, and once it is let go. */
    const val GUIDED = 0.45
    const val LOOSE = 1.6
    /** The kit lets no skull go in its first three ticks. */
    const val SHORTEST = 4
    /** While one is in the air, its player keeps this much outside what it would reach from where the opponent is. */
    const val KEEP = 0.5
    /** Blocks a tick somebody may come at it from the moment it leaves, whatever they are doing now: a run. */
    const val RUN = 0.28

    /**
     * How far from its player a skull meets somebody who is [distance] blocks off and coming
     * nearer by [closing] blocks a tick (or at a run, if that is more): it crawls while it is
     * guided, they keep coming, and it is let go from where it has got to.
     */
    fun meets(distance: Double, closing: Double): Double {
        val c = closing.coerceAtLeast(RUN)
        val hold = SHORTEST + 1
        val out = 1.0 + GUIDED * hold
        val then = distance - c * hold
        if (then <= out) return then.coerceAtLeast(0.0)
        return then - c * (then - out) / (LOOSE + c)
    }

    /**
     * Is somebody ([dx], [dy], [dz]) from the bot's feet, coming nearer by [closing] blocks a
     * tick, far enough off to send a skull at? Where it would meet them has to be outside its
     * blast. Not the same in every direction: the blast is a box, and reaches furthest toward its
     * corners (somebody seven and a half blocks off on the diagonal is still inside it).
     */
    fun clearToSend(dx: Double, dy: Double, dz: Double, closing: Double = 0.0): Boolean {
        val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (distance < 1e-6) return false
        val share = meets(distance, closing) / distance
        return !inBlast(dx * share, dy * share, dz * share, MARGIN + KEEP)
    }
    /** With a skull in the air: is the bot far enough from where the opponent is? */
    fun keepsClear(dx: Double, dy: Double, dz: Double) = !inBlast(dx, dy, dz, MARGIN + KEEP)
    /** Nobody else this near in its way either, nor anything of theirs: a skull goes off on the first body it meets. */
    const val CROWD = 8.0
    /** In its way: within this of the line it flies (a body is half a block wide, the skull nearly as much, and both are moving). */
    const val BESIDE = 2.0

    /** Would a skull going off ([dx], [dy], [dz]) from a player's feet hit that player? */
    fun inBlast(dx: Double, dy: Double, dz: Double, margin: Double = MARGIN) =
        abs(dx) <= BLAST + margin && abs(dz) <= BLAST + margin && dy <= BLAST + 1.8 + margin && dy >= -(BLAST + margin)

    /**
     * How long to guide it before letting it go. Guided it crawls; let go it is three and a half
     * times as fast and flies straight. Guiding is for steering round a corner, which a bot with
     * the opponent in plain sight has no need of.
     */
    fun holdTicks(roll: Int) = SHORTEST + roll.coerceIn(0, 2)

    /**
     * Whether to point a skull at the floor under an opponent [distance] blocks off, from an eye
     * [height] blocks above that floor, with a hand that is unsteady by [spread] degrees up and
     * down. Pointed at the floor it goes off where they were even if they step aside; but a hand
     * that dips sends it into the floor short of them, and from near enough that is inside its own
     * blast. So only from where a dip of twice the spread still lands it clear.
     */
    fun aimsAtFloor(distance: Double, height: Double, spread: Double): Boolean {
        if (height < 0.8 || distance < 1.0) return false
        val down = Math.toDegrees(kotlin.math.atan2(height, distance)) + 2 * spread
        if (down >= 89.0) return false
        return height / kotlin.math.tan(Math.toRadians(down)) >= BLAST + MARGIN + 1.0
    }

    /** The speed to lead a moving opponent by: the distance over the whole time, the crawl included. */
    fun speed(distance: Double, hold: Int): Double {
        val guided = 1.0 + GUIDED * hold
        val ticks = hold + ((distance - guided).coerceAtLeast(0.0)) / LOOSE
        return if (ticks <= 0) LOOSE else (distance / ticks).coerceIn(GUIDED, LOOSE)
    }
}

/** Wither Skeleton: the image, and the second press that swaps places with it. */
object ImagePlay {
    /** The image is swapped with only while it stands on something, or is this close to landing on it. */
    const val FLOOR = 2
    /** ... and is not on its way down fast (thrown off a ledge, it is past the edge before it is past the floor). */
    const val FALLING = -0.5
    /** To get at an opponent: worth it when the image is on them and its player is nowhere near. */
    const val ON_THEM = 4.0
    const val FAR = 8.0

    fun swapsToFight(mine: Double, images: Double) = mine >= FAR && images <= ON_THEM
    fun stands(onGround: Boolean, floorNear: Boolean, fallSpeed: Double) = (onGround || floorNear) && fallSpeed > FALLING
}

/**
 * Milk Spiral and Flame Dash: rides that go on until the ride is over or the button is pressed
 * again. A player gets off where they meant to be.
 */
object RidePlay {
    /** Milk Spiral: blocks a tick, for 36 ticks; pressed again its rider stops dead. */
    const val SPIRAL_SPEED = 0.45
    const val SPIRAL_REACH = 16.0
    /** Flame Dash: blocks a tick, for 25 ticks, level; ended, its rider keeps the speed (a slide of a block and a half on the ground, ten blocks in the air). */
    const val DASH_SPEED = 1.1
    const val DASH_REACH = 27.5
    /**
     * How far ahead the floor has to last for a rider to stay on. A Milk Spiral's stops where it
     * gets off. A Flame Dash's is looked at once a tick (1.1 blocks), goes on for the tick it is
     * ended in and then slides: three and a half blocks in all, and three more in the air.
     */
    const val SPIRAL_STOP = 1.0
    const val DASH_STOP = 3.6
    const val DASH_AIR = 3.0
    /** A Flame Dash goes off where it ends and reaches four blocks: it is ended on them, not past them. */
    const val DASH_ON_THEM = 2.2
    /** A Milk Spiral is ridden up to an opponent, to hit them when it gets there. */
    const val SPIRAL_ON_THEM = 2.0

    /**
     * Whether to get off a ride taken at an opponent. [gap] is how far they are now (flat),
     * [onThem] the distance that counts as there, [ahead] whether they are still in front of the
     * rider (a ride goes straight: once it is level with them it can only carry its rider away),
     * and [floorAhead] whether there is still floor under where the rider would stop.
     */
    fun getsOff(gap: Double, ahead: Boolean, onThem: Double, floorAhead: Boolean): Boolean =
        !floorAhead || gap <= onThem || !ahead

    /** A Flame Dash cannot climb, and sags two blocks over its length: it is no way back to ground that is not below its rider. */
    fun dashReaches(above: Double, flat: Double) = flat <= DASH_REACH && above >= 2.0 * flat / DASH_REACH + 0.5

    /** How far up to look when riding a Milk Spiral home: at a point over the ground, since the spiral goes where its rider looked. */
    const val SPIRAL_OVER = 2.5
}

/** Magma Blast throws its player back along their look at a block a tick: it is fired only with floor to be thrown back onto. */
object RecoilPlay {
    const val THROW = 1.0

    /**
     * How far back the throw carries: [down] is how far below level the player is looking
     * (degrees; looking down, the throw is up and back, and in the air nothing slows it but the
     * air), [height] how far their feet are off the floor when it goes off.
     */
    fun carried(down: Double, height: Double): Double {
        val rad = Math.toRadians(down.coerceIn(-90.0, 90.0))
        var vx = cos(rad) * THROW
        var vy = sin(rad) * THROW
        // On the floor and thrown along it (or into it): a slide, which the floor stops quickly.
        if (height <= 0.05 && vy <= 0.0) return vx / (1 - GROUND)
        var x = 0.0
        var y = height.coerceAtLeast(0.0)
        for (tick in 1..80) {
            x += vx
            y += vy
            vy = (vy - 0.08) * 0.98
            vx *= AIR
            if (y <= 0.0) break
        }
        return x + vx * GROUND / (1 - GROUND)
    }

    /** What a tick of air, and of floor, leaves of a player's speed across the ground. */
    private const val AIR = 0.91
    private const val GROUND = 0.546

    /** A step to spare beyond where the throw ends. */
    const val SPARE = 1.0
    /** How far behind a bot looks for floor: further than any throw goes. */
    const val LOOK_BACK = 13
}

/** Enderman: Blink goes 16 blocks along the look, or to just short of the first block in the way. */
object BlinkPlay {
    const val REACH = 16.0
    /** Coming out of a Blink, floor this close underneath is somewhere to stand. */
    const val DROP = 4
    /** Blinked at an opponent, it should come out within this of them, or it was not worth pressing. */
    const val NEAR = 5.0
    /** Falling, floor this far under where a Blink comes out is still a landing. */
    const val FALL = 14
    /** Where a falling Enderman looks for a way back: to either side of where it is heading, and up and down. */
    val YAWS = doubleArrayOf(0.0, -20.0, 20.0, -40.0, 40.0)
    val PITCHES = doubleArrayOf(-70.0, -55.0, -40.0, -25.0, -10.0, 5.0, 20.0, 35.0, 50.0)

    /**
     * How good a place to come out of a Blink is, falling: [drop] blocks of air under it (null:
     * nothing under it at all), [toGoal] blocks from where the bot was heading. Lower is better;
     * null is no good.
     */
    fun landing(drop: Int?, toGoal: Double): Double? = if (drop == null || drop > FALL) null else drop * 1.5 + toGoal
}
