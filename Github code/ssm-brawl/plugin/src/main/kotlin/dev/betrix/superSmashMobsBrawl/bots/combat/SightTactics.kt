package dev.betrix.superSmashMobsBrawl.bots.combat

import dev.betrix.superSmashMobsBrawl.bots.BotDifficulty
import kotlin.math.abs
import kotlin.math.sin

/**
 * What a bot can know about where its opponents are. It used to know where every one of them was
 * all the time: behind its back, through a wall, across the map. It chose whom to fight from all
 * of them, turned on somebody creeping up behind it the moment that was the better target, and
 * never once looked round, because it had nothing to look for.
 *
 * Now an opponent is known to it only once it has seen them (in front of it, with nothing in
 * between), heard them (close by, or fighting a little further off) or been hit by them; a moment
 * passes before it does anything about somebody it has only just noticed; and whoever it has not
 * seen or heard for a while it loses track of. In between, what it knows of somebody it cannot see
 * is where they were when it last could: that is where it takes them to be when it chooses whom to
 * fight, and where it goes to look for them. With nobody in mind it looks round, and then at the
 * Player Compass every fighter carries, which points at the nearest opponent.
 *
 * (Knowing of people only once it had seen them was not enough by itself: on an open map eight
 * fighters all see each other within seconds, and over 30 matches a bot that then kept track of
 * where they WERE NOW turned on somebody out of its view exactly as often as one that knew
 * everything, 38% of the times it took somebody on.)
 */
object SightTactics {
    /** Seen or heard within this many ticks is seen or heard now: a bot looks every few ticks, not every one. */
    const val FRESH = 12
    /** Somebody who turns up this far from where the bot took them to be is as good as somebody new: it takes a moment to react. */
    const val SURPRISE = 8.0
    /** This near to where it last saw somebody, a bot that still cannot see them has lost them. */
    const val ARRIVED = 2.5
    /**
     * Half the width of what a bot has in view: degrees either side of where its head is turned.
     * A screen shows about fifty either side at the game's usual setting; a player's eyes and hand
     * are never quite still, which is worth some more.
     */
    const val VIEW = 70.0
    /** Up and down a screen shows less. */
    const val VIEW_UP = 50.0
    /** How far off somebody in view is made out. */
    const val FAR = 64.0
    /** Nearer than this somebody is heard wherever the bot is looking: footsteps, a jump landing. */
    const val HEARD = 6.0
    /** A fight carries further: somebody being hit is heard from here. */
    const val FIGHT_HEARD = 14.0
    /** A hit taken says where it came from when whoever threw it can be seen by turning round: within this, with nothing in between. */
    const val HIT_FROM = 40.0

    /** Is somebody [yawOff] degrees round from where the bot is looking, and [pitchOff] up or down from it, in view? */
    fun inView(yawOff: Double, pitchOff: Double) = abs(yawOff) <= VIEW && abs(pitchOff) <= VIEW_UP

    /**
     * Does the bot see or hear somebody [distance] blocks off this moment? [inView] and
     * [openLine] are whether they are in front of it and whether anything is in between; [loud]
     * whether they have just been hit (or are raining blows on somebody).
     */
    fun perceived(distance: Double, inView: Boolean, openLine: Boolean, loud: Boolean) =
        distance <= HEARD || (loud && distance <= FIGHT_HEARD) || (inView && openLine && distance <= FAR)

    /** How long somebody is kept track of after they were last seen or heard, in ticks: the better the player, the longer. */
    fun memoryTicks(d: BotDifficulty) = when (d) {
        BotDifficulty.NORMAL -> 80
        BotDifficulty.HARD -> 110
        BotDifficulty.HARDER -> 140
        BotDifficulty.EXPERT -> 170
    }

    /** How long after it first notices somebody a bot does anything about them. */
    fun readyAfter(d: BotDifficulty) = d.reactionTicks * 2

    /**
     * Whether somebody is known to the bot now: last seen or heard [sinceSign] ticks ago, and
     * first noticed (this time round) [sinceNoticed] ticks ago.
     */
    fun knows(sinceSign: Long, sinceNoticed: Long, d: BotDifficulty) =
        sinceSign <= memoryTicks(d) && sinceNoticed >= readyAfter(d)

    /** Whether anything stands between it and somebody in front of it is looked at this often, in ticks, not every time. */
    const val LOOK_EVERY = 8

    /** With nobody in mind for this many ticks, a bot has looked round, to one side and to the other, and found nobody: it looks at its compass. */
    const val COMPASS_AFTER = 36
    /** ... which it does not do again for this long. */
    const val COMPASS_REST = 40

    /**
     * Looking round: how far to either side of the way it is going the head swings (with what is
     * in view beyond that, all the way round behind it), and how long a swing to one side, back
     * and to the other takes.
     */
    const val SWEEP = 115.0
    const val SWEEP_TICKS = 48.0

    /** How far round from the way it is going a bot that is looking for somebody has its head, [ticks] into looking. */
    fun sweep(ticks: Long) = SWEEP * sin(2 * Math.PI * ticks / SWEEP_TICKS)
}
