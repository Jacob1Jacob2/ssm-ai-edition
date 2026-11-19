package dev.betrix.superSmashMobsBrawl.bots

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.floor

class JumpSimTest : StringSpec({
    /** A world of whole blocks: [blocks] are the solid ones, everything else is air. */
    class Blocks(private val blocks: Set<Triple<Int, Int, Int>>) : JumpSim.Space {
        override fun clear(x: Double, y: Double, z: Double, foot: Double): Boolean {
            for (bx in floor(x - .3).toInt()..floor(x + .3).toInt()) for (bz in floor(z - .3).toInt()..floor(z + .3).toInt())
                for (by in floor(y + foot).toInt() - 1..floor(y + 1.8).toInt()) {
                    if (Triple(bx, by, bz) !in blocks) continue
                    if (bx < x + .3 && bx + 1 > x - .3 && bz < z + .3 && bz + 1 > z - .3 && by < y + 1.8 && by + 1 > y + foot) return false
                }
            return true
        }
    }
    fun world(vararg blocks: Triple<Int, Int, Int>) = Blocks(blocks.toSet())
    fun row(xs: IntRange, y: Int, z: Int = 0) = xs.map { Triple(it, y, z) }.toTypedArray()
    /** A jump to promise: from the block at ([ax], [ay], 0) onto the block at ([bx], [by], 0), standing on top of each. */
    fun jumps(space: JumpSim.Space, ax: Int, ay: Int, bx: Int, by: Int) =
        JumpSim.promised(space, JumpSim.STANDARD, ax + .5, ay + 1.0, .5, bx + .5, by + 1.0, .5)

    "a gap a running jump covers is jumped, with or without the air jump" {
        jumps(world(Triple(0, 63, 0), Triple(3, 63, 0)), 0, 63, 3, 63) shouldBe true
        jumps(world(Triple(0, 63, 0), Triple(5, 63, 0)), 0, 63, 5, 63) shouldBe true
    }

    "a double jump crosses a good deal more than the six blocks the planner used to stop at, and no more than it really can" {
        jumps(world(Triple(0, 63, 0), Triple(7, 63, 0)), 0, 63, 7, 63) shouldBe true
        jumps(world(Triple(0, 63, 0), Triple(8, 63, 0)), 0, 63, 8, 63) shouldBe true
        jumps(world(Triple(0, 63, 0), Triple(13, 63, 0)), 0, 63, 13, 63) shouldBe false
        // Dropping buys distance, climbing costs it.
        JumpSim.reach(-6.0) shouldBeGreaterThan JumpSim.reach(0.0)
        JumpSim.reach(0.0) shouldBeGreaterThan JumpSim.reach(3.0)
        JumpSim.reach(0.0) shouldBeGreaterThan 7.5
        JumpSim.reach(0.0) shouldBeLessThan 11.5
        JumpSim.reach(7.0) shouldBe 0.0
    }

    "the table of how far a jump goes for each height is in order" {
        val heights = (-20..13).map { it * 0.5 }
        println("reach by height: " + heights.joinToString(" ") { "$it=${JumpSim.reach(it)}" })
        // Never further for a higher landing than for a lower one.
        heights.zipWithNext().all { (low, high) -> JumpSim.reach(high) <= JumpSim.reach(low) + 1e-9 } shouldBe true
    }

    "a ledge a few blocks up is taken straight up and across, and one out of reach is not" {
        jumps(world(Triple(0, 63, 0), Triple(2, 66, 0)), 0, 63, 2, 66) shouldBe true
        jumps(world(Triple(0, 63, 0), Triple(3, 67, 0)), 0, 63, 3, 67) shouldBe true
        jumps(world(Triple(0, 63, 0), Triple(2, 71, 0)), 0, 63, 2, 71) shouldBe false
    }

    "a wall in the way is in the way" {
        val wall = (64..75).map { Triple(3, it, 0) }.toTypedArray()
        jumps(world(Triple(0, 63, 0), Triple(6, 63, 0), *wall), 0, 63, 6, 63) shouldBe false
    }

    "a hatch in the floor of a low corridor is hopped: bumping the head is not the end of a jump" {
        // Floor either side of a three block hole, and a ceiling three blocks over the floor.
        val floor = row(-3..0, 87) + row(4..8, 87)
        val ceiling = row(-3..8, 91)
        jumps(world(*floor, *ceiling), 0, 87, 4, 87) shouldBe true
        // The same hole is nothing special in the open.
        jumps(world(*floor), 0, 87, 4, 87) shouldBe true
    }

    "up a shaft and across at the top, under a ceiling" {
        // A step at the bottom of a shaft; the floor to get to is three up and three across, with a wall under it and a ceiling over it.
        val step = Triple(0, 84, 0)
        val wall = (84..86).map { Triple(3, it, 0) }.toTypedArray()
        val floor = row(3..6, 87)
        val ceiling = row(-1..6, 91)
        jumps(world(step, *wall, *floor, *ceiling), 0, 84, 3, 87) shouldBe true
    }

    "a jump starts from where there is room to stand: a wall right behind the block does not rule it out" {
        // Take-off block with a wall standing on the block behind it, and a landing four blocks on.
        val wall = (63..66).map { Triple(-1, it, 0) }.toTypedArray()
        jumps(world(Triple(0, 63, 0), Triple(4, 63, 0), *wall), 0, 63, 4, 63) shouldBe true
        // The same at an angle, onto a block under a roof (four blocks of room: a jump needs just over three).
        val space = world(Triple(0, 63, 0), Triple(0, 64, -1), Triple(0, 65, -1), Triple(1, 64, -1), Triple(1, 65, -1),
            Triple(-1, 63, 2), Triple(-1, 68, 2))
        JumpSim.promised(space, JumpSim.STANDARD, .5, 64.0, .5, -.5, 64.0, 2.5) shouldBe true
    }

    "a step up in a tunnel is a plain hop: it needs the room of a hop, not of a double jump" {
        // A block to step up onto, under a ceiling four blocks over the lower floor (three over the step).
        val floor = arrayOf(Triple(0, 63, 0), Triple(1, 64, 0))
        val ceiling = row(-1..2, 68)
        JumpSim.promised(world(*floor, *ceiling), null, .5, 64.0, .5, 1.5, 65.0, .5) shouldBe true
        // With no air jump in hand a gap of four blocks is not on, and with one it is.
        JumpSim.promised(world(Triple(0, 63, 0), Triple(5, 63, 0)), null, .5, 64.0, .5, 5.5, 64.0, .5) shouldBe false
        JumpSim.promised(world(Triple(0, 63, 0), Triple(5, 63, 0)), JumpSim.STANDARD, .5, 64.0, .5, 5.5, 64.0, .5) shouldBe true
    }

    "a long way down is a long way out" {
        jumps(world(Triple(0, 63, 0), Triple(9, 53, 0)), 0, 63, 9, 53) shouldBe true
        jumps(world(Triple(0, 63, 0), Triple(14, 53, 0)), 0, 63, 14, 53) shouldBe false
    }

    "the air jump is aimed by flying it: the pick comes down on the landing when any pitch does" {
        val space = world(Triple(0, 63, 0), Triple(5, 63, 0))
        // In the air over the gap, at the top of a jump.
        val from = JumpSim.Flight(1.9, 65.25, .5, 0.24, 0.0, 0.0)
        val aim = JumpSim.aim(space, JumpSim.STANDARD, from, 5.5, 64.0, .5)
        aim.lands shouldBe true
        // With nothing to land on, it still gives an answer: the best guess.
        JumpSim.aim(world(Triple(0, 63, 0)), JumpSim.STANDARD, from, 5.5, 64.0, .5).lands shouldBe false
    }

    "a block in the way is cleared by the feet, not by a centimetre of slack" {
        // Up against a pile two blocks high, at the top of a jump: 0.75 short of its top. An air
        // jump that rises 0.743 in its first tick is 8mm short of clearing it, and loses all its
        // speed across against the side. (A bot fell to its death on exactly this.)
        val pile = arrayOf(Triple(1, 64, 0), Triple(1, 65, 0))
        val space = world(Triple(0, 63, 0), *pile, Triple(1, 63, 0), Triple(6, 63, 0))
        val from = JumpSim.Flight(0.7, 65.2491, .5)
        val aim = JumpSim.aim(space, JumpSim.STANDARD, from, 6.5, 64.0, .5)
        aim.lands shouldBe true
        // Whatever it picked goes up by enough in the first tick to be over the pile, with room to spare.
        from.y + JumpSim.STANDARD.speeds(aim.pitch).second shouldBeGreaterThanOrEqual 66.0 - JumpSim.OVER
    }
})
