package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.bots.combat.SightTactics
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** What a bot can know about where its opponents are: what it sees, what it hears, and for how long it keeps track. */
class SightTacticsTest : StringSpec({
    "somebody in front with nothing in between is seen; behind, or behind a wall, they are not" {
        SightTactics.inView(0.0, 0.0) shouldBe true
        SightTactics.inView(65.0, 0.0) shouldBe true
        SightTactics.inView(-65.0, 20.0) shouldBe true
        // Out to the side, behind, straight overhead.
        SightTactics.inView(95.0, 0.0) shouldBe false
        SightTactics.inView(180.0, 0.0) shouldBe false
        SightTactics.inView(0.0, -80.0) shouldBe false
        SightTactics.perceived(distance = 30.0, inView = true, openLine = true, loud = false) shouldBe true
        SightTactics.perceived(distance = 30.0, inView = false, openLine = true, loud = false) shouldBe false
        SightTactics.perceived(distance = 30.0, inView = true, openLine = false, loud = false) shouldBe false
        // Too far off to make out.
        SightTactics.perceived(distance = SightTactics.FAR + 5, inView = true, openLine = true, loud = false) shouldBe false
    }
    "somebody close is heard whichever way the bot is looking, and a fight from further off" {
        SightTactics.perceived(distance = 5.0, inView = false, openLine = false, loud = false) shouldBe true
        SightTactics.perceived(distance = 9.0, inView = false, openLine = false, loud = false) shouldBe false
        SightTactics.perceived(distance = 9.0, inView = false, openLine = false, loud = true) shouldBe true
        SightTactics.perceived(distance = 20.0, inView = false, openLine = true, loud = true) shouldBe false
        // Creeping up from behind works until the last few steps, and no further.
        (SightTactics.HEARD > 4.0) shouldBe true
        (SightTactics.HEARD < SightTactics.FIGHT_HEARD) shouldBe true
    }
    "a moment passes before a bot acts on somebody it has only just noticed, and it loses track of whoever it has not seen for a while" {
        for (d in BotDifficulty.entries) {
            val ready = SightTactics.readyAfter(d).toLong()
            val memory = SightTactics.memoryTicks(d).toLong()
            // Just noticed: not yet.
            SightTactics.knows(sinceSign = 0, sinceNoticed = 0, d) shouldBe (ready == 0L)
            SightTactics.knows(sinceSign = 0, sinceNoticed = ready, d) shouldBe true
            // In mind for as long as they were seen not long ago...
            SightTactics.knows(sinceSign = memory, sinceNoticed = 10_000, d) shouldBe true
            // ...and then not.
            SightTactics.knows(sinceSign = memory + 1, sinceNoticed = 10_000, d) shouldBe false
        }
        // The better the player, the quicker they are onto somebody and the longer they keep track.
        BotDifficulty.entries.map { SightTactics.readyAfter(it) } shouldBe BotDifficulty.entries.map { SightTactics.readyAfter(it) }.sortedDescending()
        BotDifficulty.entries.map { SightTactics.memoryTicks(it) } shouldBe BotDifficulty.entries.map { SightTactics.memoryTicks(it) }.sorted()
        // An Expert is onto somebody in a fifth of a second; nobody takes more than a second.
        (SightTactics.readyAfter(BotDifficulty.EXPERT) <= 4) shouldBe true
        (SightTactics.readyAfter(BotDifficulty.NORMAL) <= 20) shouldBe true
        // Nobody forgets in under four seconds somebody who has stepped behind a wall.
        (SightTactics.memoryTicks(BotDifficulty.NORMAL) >= 80) shouldBe true
    }
    "with nobody in mind a bot looks round: to both sides of the way it is going, and behind it, before it looks at its compass" {
        val swing = (0L..SightTactics.COMPASS_AFTER).map { SightTactics.sweep(it) }
        // Out to one side past a right angle, which with what is in view beyond that takes in what is straight behind it...
        (swing.max() > 90.0) shouldBe true
        (swing.max() + SightTactics.VIEW >= 180.0) shouldBe true
        // ...and then to the other, all before the compass comes out.
        (swing.min() - SightTactics.VIEW <= -180.0) shouldBe true
        // It starts from the way it was already facing.
        SightTactics.sweep(0) shouldBe (0.0 plusOrMinus 1e-9)
        val whole = (0L..SightTactics.SWEEP_TICKS.toLong()).map { SightTactics.sweep(it) }
        // Never faster than a head turns (an Expert's 45 degrees a tick is the most there is).
        whole.zipWithNext { a, b -> abs(b - a) }.max().let { (it < 20.0) shouldBe true }
    }
})
