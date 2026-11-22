package dev.betrix.superSmashMobsBrawl.bots.combat

import dev.betrix.superSmashMobsBrawl.bots.BotDifficulty
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * What a bot has to know about a kit to play it at all, as distinct from playing it well. The
 * kits themselves are not touched: they are meant to be Mineplex's, broken ones included. But a
 * kit a bot cannot work is the bot's fault, and over 500 recorded matches two stood out: Enderman
 * (1.9% of its matches won, 1% of its damage from abilities) and Phantom (0.5%).
 */
object KitPlay {
    /**
     * Abilities that begin with a right-click on a block, not on thin air. An Enderman's Block Toss
     * picks up the block that is clicked, is charged while the button is held and thrown when it is
     * let go. A bot's click was always on air, so it never picked one up.
     */
    val NEEDS_A_BLOCK = setOf("block_toss")
    /** How far a hand reaches for a block. */
    const val ARM = 4.5
}

/**
 * How a bot flies a Phantom. The kit flies at up to 0.52 blocks a tick for as long as its tank
 * lasts, and drops a bomb every half second while its player holds sneak; a bomb reaches 3.5
 * blocks from where it lands. A bot used to hover three blocks over its opponent's head, which is
 * within reach of a hit from the ground, at 0.35 blocks a tick, which is slower than they run: it
 * was hit by everybody, was seldom over anybody when it bombed, and stayed up until the tank ran
 * dry wherever that happened to be.
 */
object PhantomPlay {
    /** How far over an opponent's feet to hover: out of reach of a hit from the ground, a jump included. */
    const val HEIGHT = 6.0
    /** With a roof over them, as high as there is room for, but not lower than this (lower is in the fight, and better fought on foot). */
    const val LOWEST = 2.0
    /** Blocks a tick: just under the kit's own top speed. */
    const val SPEED = 0.5
    /** A bomb is about this many ticks coming down from that height: it is dropped over where they will be by then. */
    const val FALL_TICKS = 8
    /** With less than this in the tank a Phantom does not take off to attack: it would be down again before it got there. */
    const val TAKE_OFF = 0.5
    /** With less than this left it goes back to ground it knows is there. */
    const val RESERVE = 0.15

    /** Whether to let a bomb go: over them, or near enough that its blast reaches, with them underneath. */
    fun bombs(flatGap: Double, above: Double, fuel: Double) = flatGap < 3.0 && above > 1.5 && fuel >= RESERVE
    fun headsHome(fuel: Double) = fuel < RESERVE
    /** How high to hover with [room] blocks of open air over the opponent's head (null: open sky). */
    fun height(room: Double?) = (room ?: HEIGHT).coerceIn(LOWEST, HEIGHT)
}

/**
 * How a bot's head moves. It used to turn at one fixed speed and stop dead on the mark, with an
 * aim error rolled afresh at every decision: a head that snapped round like a turret and then
 * shivered on the spot ten times a second. A hand on a mouse does neither. It swings toward where
 * it wants to be, slows as it gets there and runs a little past; and when it is off, it is off
 * the same way for a moment, drifting, not jumping about.
 */
object LookTactics {
    /** How hard the look is pulled toward where it wants to be, each tick, as a share of how far off it is. */
    fun pull(d: BotDifficulty) = when (d) {
        BotDifficulty.NORMAL -> 0.25
        BotDifficulty.HARD -> 0.32
        BotDifficulty.HARDER -> 0.42
        BotDifficulty.EXPERT -> 0.5
    }

    /** How much of its swing a turn keeps from one tick to the next: what carries it past the mark. */
    fun carry(d: BotDifficulty) = when (d) {
        BotDifficulty.NORMAL -> 0.5
        BotDifficulty.HARD -> 0.42
        BotDifficulty.HARDER -> 0.35
        BotDifficulty.EXPERT -> 0.3
    }

    /**
     * One tick of a turn: how fast the look now turns (degrees a tick) when it was turning at
     * [speed] and is [off] degrees from where it wants to be. Never faster than [cap].
     */
    fun swing(speed: Double, off: Double, pull: Double, carry: Double, cap: Double) =
        (speed * carry + off * pull).coerceIn(-cap, cap)

    /**
     * How many ticks a look that eases like this trails behind something moving steadily across
     * it, once it has turned for the tick. A player who knows their own hand aims that far ahead
     * of somebody running past them.
     */
    fun trail(pull: Double, carry: Double) = ((1 - carry) / pull - 1).coerceAtLeast(0.0)

    /** How much of that trailing a player makes up for by aiming ahead: all of it for the best, little for the rest. */
    fun makesUp(d: BotDifficulty) = when (d) {
        BotDifficulty.NORMAL -> 0.25
        BotDifficulty.HARD -> 0.5
        BotDifficulty.HARDER -> 0.75
        BotDifficulty.EXPERT -> 1.0
    }

    /** Ticks ahead of a moving opponent a bot of this difficulty points, so that its easing look is on them and not behind. */
    fun ahead(d: BotDifficulty) = trail(pull(d), carry(d)) * makesUp(d)

    /** An aim error keeps this share of itself from one decision to the next, [reactionTicks] apart: it lasts about half a second. */
    fun keeps(reactionTicks: Int) = exp(-reactionTicks / DRIFT_TICKS)
    const val DRIFT_TICKS = 12.0

    /**
     * The aim error at the next decision: mostly the one before, pushed a little by [gaussian].
     * Over time it is as large as it ever was (a spread of [sd] degrees); it just gets there by
     * drifting. With [keep] 0 it is the old way: a fresh roll every time.
     */
    fun nextError(error: Double, gaussian: Double, sd: Double, keep: Double) =
        error * keep + gaussian * sd * sqrt(1 - keep * keep)
}
