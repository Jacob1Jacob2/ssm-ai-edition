package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.bots.combat.BlinkPlay
import dev.betrix.superSmashMobsBrawl.bots.combat.ImagePlay
import dev.betrix.superSmashMobsBrawl.bots.combat.RecoilPlay
import dev.betrix.superSmashMobsBrawl.bots.combat.RidePlay
import dev.betrix.superSmashMobsBrawl.bots.combat.SkullPlay
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/** Abilities that carry, throw or swap the bot that uses them: what it has to know to still be on the map afterwards. */
class KitMovesTest : StringSpec({
    "a Guided Wither Skull's blast is a box, and the one who sent it is in it like anybody" {
        // Straight ahead: six blocks and a body.
        SkullPlay.inBlast(6.5, 0.0, 0.0) shouldBe true
        SkullPlay.inBlast(7.0, 0.0, 0.0) shouldBe false
        // A box, not a ball: eight and a half blocks off along the diagonal is still inside.
        SkullPlay.inBlast(6.0, 0.0, 6.0) shouldBe true
        SkullPlay.inBlast(-6.0, 0.0, 7.0) shouldBe false
        // Going off overhead, or under a player standing on a ledge above it.
        SkullPlay.inBlast(0.0, 7.0, 0.0) shouldBe true
        SkullPlay.inBlast(0.0, 9.0, 0.0) shouldBe false
        SkullPlay.inBlast(0.0, -6.5, 0.0) shouldBe true
        SkullPlay.inBlast(0.0, -7.0, 0.0) shouldBe false
    }
    "a skull is sent only at somebody its blast would not reach its own player from, and that gap is kept while it flies" {
        // Somebody nine blocks off who runs at it meets it at seven: it crawls for five ticks first.
        SkullPlay.meets(9.0, 0.0) shouldBe (6.95 plusOrMinus 0.01)
        (SkullPlay.meets(9.0, 0.6) < SkullPlay.meets(9.0, 0.0)) shouldBe true
        (SkullPlay.meets(20.0, 0.0) > 15.0) shouldBe true
        // Straight ahead: nine and a half blocks is enough, nine is not.
        SkullPlay.clearToSend(9.5, 0.0, 0.0) shouldBe true
        SkullPlay.clearToSend(9.0, 0.0, 0.0) shouldBe false
        // On the diagonal the box reaches further: eleven blocks off is inside it, thirteen is not.
        SkullPlay.clearToSend(8.0, 0.0, 8.0) shouldBe false
        SkullPlay.clearToSend(9.4, 0.0, 9.4) shouldBe true
        // Far enough along one axis is far enough, whatever the other.
        SkullPlay.clearToSend(9.8, 0.0, 3.0) shouldBe true
        // Somebody on a ledge well overhead, or far below.
        SkullPlay.clearToSend(3.0, 12.5, 3.0) shouldBe true
        SkullPlay.clearToSend(3.0, 4.0, 3.0) shouldBe false
        SkullPlay.clearToSend(3.0, -10.5, 3.0) shouldBe true
        // Somebody in the middle of a dash needs more room than somebody standing.
        SkullPlay.clearToSend(11.0, 0.0, 0.0, closing = 0.0) shouldBe true
        SkullPlay.clearToSend(11.0, 0.0, 0.0, closing = 0.6) shouldBe false
        SkullPlay.clearToSend(12.5, 0.0, 0.0, closing = 0.6) shouldBe true
        // Somebody going away is counted as about to turn round.
        SkullPlay.clearToSend(9.0, 0.0, 0.0, closing = -0.5) shouldBe false
        // With one in the air its player stands off by less than it needed to send it, but always outside the blast.
        SkullPlay.keepsClear(7.5, 0.0, 0.0) shouldBe true
        SkullPlay.keepsClear(7.0, 0.0, 0.0) shouldBe false
        SkullPlay.keepsClear(7.0, 0.0, 7.0) shouldBe false
        for (x in listOf(0.0, 3.0, 6.0, 7.5, 9.0)) for (z in listOf(0.0, 3.0, 6.0, 7.5, 9.0)) {
            if (SkullPlay.clearToSend(x, 0.0, z)) SkullPlay.keepsClear(x, 0.0, z) shouldBe true
            if (SkullPlay.keepsClear(x, 0.0, z)) SkullPlay.inBlast(x, 0.0, z) shouldBe false
        }
        // What is in its way counts from further off than the blast reaches.
        (SkullPlay.CROWD > SkullPlay.BLAST + SkullPlay.MARGIN) shouldBe true
    }
    "a skull is let go as soon as the kit allows, and led by how fast it gets there in all" {
        (0..9).map { SkullPlay.holdTicks(it) }.toSet() shouldBe setOf(4, 5, 6)
        // Nine blocks: four ticks crawling to 2.8, then 6.2 more at 1.6 a tick.
        SkullPlay.speed(9.0, 4) shouldBe (9.0 / (4 + 6.2 / 1.6) plusOrMinus 1e-9)
        // The further, the nearer to its speed once let go; never outside the two.
        (SkullPlay.speed(22.0, 4) > SkullPlay.speed(9.0, 4)) shouldBe true
        for (d in listOf(1.0, 3.0, 9.0, 15.0, 22.0, 60.0)) for (h in 4..6) {
            (SkullPlay.speed(d, h) in SkullPlay.GUIDED..SkullPlay.LOOSE) shouldBe true
        }
    }
    "a skull is pointed at the floor under somebody only from where an unsteady hand cannot put it into the floor inside its own blast" {
        val eye = 1.62
        // An Expert's hand (1.25 degrees up and down): from ten blocks and more, not from nine.
        SkullPlay.aimsAtFloor(distance = 9.0, height = eye, spread = 1.25) shouldBe false
        SkullPlay.aimsAtFloor(distance = 10.5, height = eye, spread = 1.25) shouldBe true
        SkullPlay.aimsAtFloor(distance = 20.0, height = eye, spread = 1.25) shouldBe true
        // A steadier hand may from nearer; a shakier one only from further, or not at all.
        SkullPlay.aimsAtFloor(distance = 9.0, height = eye, spread = 0.0) shouldBe true
        SkullPlay.aimsAtFloor(distance = 12.0, height = eye, spread = 2.5) shouldBe false
        SkullPlay.aimsAtFloor(distance = 16.0, height = eye, spread = 2.5) shouldBe true
        SkullPlay.aimsAtFloor(distance = 22.0, height = eye, spread = 4.5) shouldBe false
        // Looking down from a height the floor is met at a steeper angle, and a dip moves it less.
        SkullPlay.aimsAtFloor(distance = 9.0, height = 6.0, spread = 1.25) shouldBe true
        // From below their floor there is no floor to point at.
        SkullPlay.aimsAtFloor(distance = 12.0, height = 0.2, spread = 1.25) shouldBe false
    }
    "a Wither Image is swapped with where it stands on something, and to get at somebody only when it is on them" {
        ImagePlay.stands(onGround = true, floorNear = false, fallSpeed = 0.0) shouldBe true
        ImagePlay.stands(onGround = false, floorNear = true, fallSpeed = -0.2) shouldBe true
        // In the air over nothing: the swap that used to cost a life.
        ImagePlay.stands(onGround = false, floorNear = false, fallSpeed = -0.1) shouldBe false
        // Going past a ledge on its way down.
        ImagePlay.stands(onGround = false, floorNear = true, fallSpeed = -0.9) shouldBe false
        ImagePlay.swapsToFight(mine = 12.0, images = 2.5) shouldBe true
        ImagePlay.swapsToFight(mine = 5.0, images = 2.5) shouldBe false
        ImagePlay.swapsToFight(mine = 12.0, images = 7.0) shouldBe false
    }
    "a ride is got off where the floor ends, on the opponent, or once past them" {
        // They are still in front, with floor ahead: stay on (knocked further off by the herd, they are ridden after).
        RidePlay.getsOff(gap = 6.0, ahead = true, onThem = RidePlay.DASH_ON_THEM, floorAhead = true) shouldBe false
        RidePlay.getsOff(gap = 6.0, ahead = true, onThem = RidePlay.DASH_ON_THEM, floorAhead = false) shouldBe true
        RidePlay.getsOff(gap = 2.0, ahead = true, onThem = RidePlay.DASH_ON_THEM, floorAhead = true) shouldBe true
        // They stepped aside and the ride is level with them: from here it only carries its rider away.
        RidePlay.getsOff(gap = 4.5, ahead = false, onThem = RidePlay.DASH_ON_THEM, floorAhead = true) shouldBe true
        // The rides go much further than a bot used to allow for (8 and 10 blocks).
        (RidePlay.SPIRAL_REACH > 15.0) shouldBe true
        (RidePlay.DASH_REACH > 25.0) shouldBe true
        // A Flame Dash ended on the ground slides on: the floor has to last further than a Milk Spiral's, which stops dead.
        (RidePlay.DASH_STOP > RidePlay.SPIRAL_STOP) shouldBe true
    }
    "a Flame Dash is no way back to ground that is not below its rider" {
        RidePlay.dashReaches(above = 0.0, flat = 10.0) shouldBe false
        RidePlay.dashReaches(above = -3.0, flat = 4.0) shouldBe false
        RidePlay.dashReaches(above = 3.0, flat = 10.0) shouldBe true
        RidePlay.dashReaches(above = 1.0, flat = 5.0) shouldBe true
        RidePlay.dashReaches(above = 10.0, flat = 30.0) shouldBe false
    }
    "a Magma Blast throws its player back a couple of blocks along the floor, and three times as far through the air" {
        // Standing, looking level (or up at somebody: thrown into the floor): a slide.
        RecoilPlay.carried(down = 0.0, height = 0.0) shouldBe (1.0 / (1 - 0.546) plusOrMinus 1e-6)
        (RecoilPlay.carried(down = -20.0, height = 0.0) < 2.2) shouldBe true
        // Looking down at somebody below: thrown up and back.
        (RecoilPlay.carried(down = 30.0, height = 0.0) in 6.5..8.0) shouldBe true
        // In the middle of a jump.
        (RecoilPlay.carried(down = 0.0, height = 1.2) in 5.5..6.5) shouldBe true
        (RecoilPlay.carried(down = 0.0, height = 3.0) > RecoilPlay.carried(down = 0.0, height = 1.2)) shouldBe true
        // Never further than a bot looks behind itself for floor.
        for (down in listOf(-60.0, -20.0, 0.0, 15.0, 30.0, 45.0, 70.0)) for (height in listOf(0.0, 1.2, 3.0, 6.0)) {
            (RecoilPlay.carried(down, height) + RecoilPlay.SPARE <= RecoilPlay.LOOK_BACK) shouldBe true
        }
        // Straight down is straight up: it comes down where it went up.
        (RecoilPlay.carried(down = 90.0, height = 0.0) < 0.01) shouldBe true
    }
    "a falling Enderman blinks to where there is floor under it, the nearer underfoot and the nearer where it was heading the better" {
        BlinkPlay.landing(drop = null, toGoal = 0.0) shouldBe null
        BlinkPlay.landing(drop = BlinkPlay.FALL + 1, toGoal = 0.0) shouldBe null
        (BlinkPlay.landing(drop = 1, toGoal = 4.0)!! < BlinkPlay.landing(drop = 6, toGoal = 4.0)!!) shouldBe true
        (BlinkPlay.landing(drop = 2, toGoal = 1.0)!! < BlinkPlay.landing(drop = 2, toGoal = 9.0)!!) shouldBe true
        // Straight ahead is tried first, and looking up before looking down.
        BlinkPlay.YAWS.first() shouldBe 0.0
        (BlinkPlay.PITCHES.first() < 0.0) shouldBe true
        (BlinkPlay.DROP < BlinkPlay.FALL) shouldBe true
    }
})
