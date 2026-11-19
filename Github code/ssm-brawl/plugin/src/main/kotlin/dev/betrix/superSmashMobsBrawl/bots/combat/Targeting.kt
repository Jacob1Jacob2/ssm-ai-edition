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
