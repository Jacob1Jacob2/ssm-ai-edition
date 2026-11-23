package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.bots.combat.ExpertTargeting
import dev.betrix.superSmashMobsBrawl.bots.combat.ReachTactics
import dev.betrix.superSmashMobsBrawl.bots.combat.TargetCandidate
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/** Who a bot keeps after: somebody in the air with no floor under them is not yet somebody out of reach. */
class ReachTacticsTest : StringSpec({
    "somebody knocked through the air is only passing; somebody who stays out there is out of reach" {
        // Standing (no air time at all): whatever the routes say about where they stand holds.
        ReachTactics.passing(-1) shouldBe false
        // Just knocked off, and all the way through a long fall or a climb back: still passing.
        ReachTactics.passing(0) shouldBe true
        ReachTactics.passing(20) shouldBe true
        ReachTactics.passing((ReachTactics.HANGS - 1).toLong()) shouldBe true
        // Out there for three seconds and more is hovering, not falling.
        ReachTactics.passing(ReachTactics.HANGS.toLong()) shouldBe false
        ReachTactics.passing(400) shouldBe false
        // A fall from the top of an arena to the void and a double jump back both fit inside it.
        (ReachTactics.HANGS >= 40) shouldBe true
    }
    "an opponent beside it is not left for one across the map while both can be gone for" {
        val near = TargetCandidate(UUID.randomUUID(), distance = 5.0, health = 20.0)
        val far = TargetCandidate(UUID.randomUUID(), distance = 50.0, health = 20.0)
        // Whichever of the two it is after now, it ends up on the near one, and stays there.
        ExpertTargeting.choose(listOf(near, far), near.id) shouldBe near.id
        ExpertTargeting.choose(listOf(near, far), far.id) shouldBe near.id
        ExpertTargeting.choose(listOf(near, far), null) shouldBe near.id
        // Only when the near one is not there to be chosen at all does the far one get it: which
        // is why somebody in the air must not drop out of the list for the moment they are up there.
        ExpertTargeting.choose(listOf(far), near.id) shouldBe far.id
    }
})
