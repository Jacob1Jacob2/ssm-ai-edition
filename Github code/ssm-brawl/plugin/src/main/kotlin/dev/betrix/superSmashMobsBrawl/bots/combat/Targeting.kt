package dev.betrix.superSmashMobsBrawl.bots.combat

import dev.betrix.superSmashMobsBrawl.bots.BotDifficulty
import java.util.UUID

/** One opponent a bot could fight, reduced to the facts target selection uses. */
data class TargetCandidate(
    val id: UUID,
    val distance: Double,
    val health: Double,
    /** Standing at or over an edge, so a hit is likely to knock them off. */
    val nearEdge: Boolean = false,
    /** Hit this bot within the last few seconds. */
    val attackedMe: Boolean = false,
    /** This bot told the lobby it is going after them (the chill bots teaming on a salty one), or a teammate sent it after them. */
    val wanted: Boolean = false,
    /**
     * Two teams: how much going after them helps the bot's side, in blocks of distance (somebody
     * a teammate is fighting, or who is fighting a teammate, is worth a longer walk than somebody
     * merely closer). Negative for somebody far from where the bot was asked to stay.
     */
    val assist: Double = 0.0,
)

/** Picks who to fight. Lower [score] is a better target. */
abstract class TargetSelector {
    protected abstract fun score(c: TargetCandidate): Double

    /**
     * A route needs a stable destination, so the current target is kept unless another one is
     * clearly better; similarly distant fighters must not be swapped every reaction tick.
     */
    fun choose(candidates: List<TargetCandidate>, current: UUID?): UUID? {
        if (candidates.isEmpty()) return null
        // A bot that said "everyone get him" means it: that fighter outranks whoever is merely closer.
        fun sticky(c: TargetCandidate) = score(c) - (if (c.id == current) 3.0 else 0.0) - (if (c.wanted) GRUDGE_PULL else 0.0) - c.assist
        val best = candidates.minBy(::sticky)
        val kept = candidates.find { it.id == current }
        return if (kept != null && sticky(kept) <= sticky(best) + 8.0) kept.id else best.id
    }

    companion object {
        /**
         * How much a wanted fighter is favoured, in blocks of distance. After the stickiness of the
         * current target, that is someone up to about 35 blocks further away than the nearest fighter.
         */
        const val GRUDGE_PULL = 45.0

        fun of(difficulty: BotDifficulty): TargetSelector = if (difficulty.smartTargeting) ExpertTargeting else NormalTargeting
    }
}

/**
 * Whether "there is no floor anywhere near them" says anything about an opponent. Somebody knocked
 * out over the void, or at the top of a jump over a gap, has nothing under them for the second it
 * takes them to get back or to fall. A bot used to write them off as out of reach on the spot
 * (844 times in ten matches), turn to whoever else it knew of, however far off, and turn back as
 * soon as they had moved on: half of all the times a bot left an opponent who was still there to
 * be fought, and all but five of the 114 times it left one beside it for one across the map.
 *
 * Only somebody who stays in the air where there is no floor (hovering, flying about out there)
 * is out of reach. Until then the bot keeps them: it goes as far as the ground goes, watches, and
 * asks again.
 */
object ReachTactics {
    /** Ticks off the ground before somebody with no floor under them is taken to be staying out there. */
    const val HANGS = 60
    /** Not watched for longer than this, and how long they have been up there is counted from scratch. */
    const val WATCHED = 5

    /** Is somebody who has been off the ground for [airTicks] (below zero: they are standing) only passing through the air? */
    fun passing(airTicks: Long) = airTicks in 0 until HANGS
}

/** Fights whoever is closest. */
object NormalTargeting : TargetSelector() {
    override fun score(c: TargetCandidate) = c.distance
}

/** Finishes weak fighters, punishes anyone near an edge, and answers whoever is hitting it. */
object ExpertTargeting : TargetSelector() {
    override fun score(c: TargetCandidate): Double {
        var s = c.distance - (20.0 - c.health) * 0.5
        if (c.nearEdge) s -= 4.0
        if (c.attackedMe) s -= 2.0
        return s
    }
}
