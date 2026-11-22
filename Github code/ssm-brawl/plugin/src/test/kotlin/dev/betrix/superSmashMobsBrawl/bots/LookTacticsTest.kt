package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.bots.combat.LookTactics
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/** How a bot's head moves: a turn that eases onto its mark and runs a little past, and an aim error that drifts. */
class LookTacticsTest : StringSpec({
    /** A look [off] degrees from where it wants to be, turned for [ticks] ticks: how far off it is after each. */
    fun turn(d: BotDifficulty, off: Double, ticks: Int, cap: Double = d.turnSpeed.toDouble()): List<Double> {
        var left = off
        var speed = 0.0
        return (1..ticks).map {
            speed = LookTactics.swing(speed, left, LookTactics.pull(d), LookTactics.carry(d), cap)
            left -= speed
            left
        }
    }

    "a turn eases onto its mark: fast at first, slower as it arrives, and a little past before it settles" {
        val off = turn(BotDifficulty.EXPERT, 30.0, 14)
        // Half of it in the first tick, most of it in two: an Expert still gets onto somebody at once.
        off[0] shouldBe (15.0 plusOrMinus 1e-9)
        (off[1] < 4.0) shouldBe true
        // Past the mark, but not by much, and back on it within half a second.
        val past = -off.min()
        (past > 0.5) shouldBe true
        (past < 30.0 * 0.12) shouldBe true
        (abs(off[9]) < 0.3) shouldBe true
        (abs(off.last()) < 0.05) shouldBe true
    }
    "the slower the player, the longer the turn takes; and no turn is faster than the head can go" {
        fun settled(d: BotDifficulty) = turn(d, 30.0, 40).indexOfLast { abs(it) > 1.5 } + 2
        val ticks = BotDifficulty.entries.map(::settled)
        ticks shouldBe ticks.sortedDescending()
        (ticks.first() > ticks.last()) shouldBe true
        // A half turn at an Expert's 45 degrees a tick: capped, so three ticks at the least.
        val flick = turn(BotDifficulty.EXPERT, 180.0, 6)
        flick[0] shouldBe (135.0 plusOrMinus 1e-9)
        (flick[2] > 0.0) shouldBe true
        // Every turn does arrive.
        for (d in BotDifficulty.entries) (abs(turn(d, 120.0, 60).last()) < 0.1) shouldBe true
    }
    "somebody running past is aimed ahead of, by as much as the easing look would trail them" {
        // Tracking something that moves 5 degrees a tick, with and without aiming ahead of it.
        fun trailing(d: BotDifficulty, ahead: Double): Double {
            var look = 0.0
            var speed = 0.0
            var mark = 0.0
            repeat(80) {
                mark += 5.0
                speed = LookTactics.swing(speed, mark + 5.0 * ahead - look, LookTactics.pull(d), LookTactics.carry(d), d.turnSpeed.toDouble())
                look += speed
            }
            return mark - look
        }
        val expert = BotDifficulty.EXPERT
        // Left to itself an Expert's look is under half a tick behind (two degrees here); aimed that far ahead, it is on them.
        trailing(expert, 0.0) shouldBe (5.0 * LookTactics.trail(LookTactics.pull(expert), LookTactics.carry(expert)) plusOrMinus 0.01)
        trailing(expert, 0.0) shouldBe (2.0 plusOrMinus 0.01)
        (abs(trailing(expert, LookTactics.ahead(expert))) < 0.01) shouldBe true
        // A Normal player's is a whole tick behind, and they make up a quarter of it.
        trailing(BotDifficulty.NORMAL, 0.0) shouldBe (5.0 plusOrMinus 0.01)
        trailing(BotDifficulty.NORMAL, LookTactics.ahead(BotDifficulty.NORMAL)) shouldBe (3.75 plusOrMinus 0.01)
        BotDifficulty.entries.map { LookTactics.makesUp(it) } shouldBe listOf(0.25, 0.5, 0.75, 1.0)
    }
    "an aim error drifts instead of jumping about, and is as large as it ever was" {
        val rng = Random(7)
        val sd = 2.5
        for (d in listOf(BotDifficulty.EXPERT, BotDifficulty.NORMAL)) {
            val keep = LookTactics.keeps(d.reactionTicks)
            var error = 0.0
            var sum = 0.0
            var steps = 0.0
            val n = 40_000
            repeat(n) {
                val next = LookTactics.nextError(error, rng.nextGaussian(), sd, keep)
                steps += abs(next - error)
                error = next
                sum += error * error
            }
            // The same spread over time...
            sqrt(sum / n) shouldBe (sd plusOrMinus 0.12)
            // ...but one decision to the next it moves less than a fresh roll would (about 1.13 of the spread on average).
            (steps / n < sd * 1.13 * 0.95) shouldBe true
        }
        // An Expert decides every two ticks and keeps most of its error; a Normal bot decides so seldom that little is left.
        (LookTactics.keeps(2) > 0.8) shouldBe true
        (LookTactics.keeps(9) < 0.5) shouldBe true
        // Keeping nothing is the old way: whatever is rolled.
        LookTactics.nextError(9.0, 1.0, sd, 0.0) shouldBe (sd plusOrMinus 1e-9)
    }
})
