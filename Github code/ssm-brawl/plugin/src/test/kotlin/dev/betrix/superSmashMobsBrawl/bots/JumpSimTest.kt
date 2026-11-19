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
            val half = .3 - JumpSim.TOUCH
            for (bx in floor(x - half).toInt()..floor(x + half).toInt()) for (bz in floor(z - half).toInt()..floor(z + half).toInt())
                for (by in floor(y + foot).toInt() - 1..floor(y + 1.8).toInt()) {
                    if (Triple(bx, by, bz) !in blocks) continue
                    if (bx < x + half && bx + 1 > x - half && bz < z + half && bz + 1 > z - half && by < y + 1.8 && by + 1 > y + foot) return false
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
        // (Seven up is more than a jump and a double jump make: nowhere a block away.)
        JumpSim.reach(7.0) shouldBeLessThan 1.0
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

    "going across is one axis at a time, as in the game: no slipping diagonally past a corner" {
        // In the air beside a pillar, going diagonally: where it would be after the tick is clear
        // of the pillar, but the way there along x is not, and x is moved first. The game stops
        // it against the pillar's side and takes its speed along x. (Moved both ways at once it
        // slipped past, and a wolf promised a ledge that way clipped a corner and fell.)
        val pillar = world(Triple(1, 70, 0), Triple(1, 71, 0))
        val flat = JumpSim.Launch { 0.6 * kotlin.math.sqrt(2.0) to 0.0 }
        val path = JumpSim.path(pillar, flat, JumpSim.Flight(.5, 70.0, .75), 0.0, 10.5, 60.0, 10.75)
        path[0].x shouldBeLessThan 0.701
        path[0].x shouldBeGreaterThan 0.69
        path[0].z shouldBeGreaterThan 1.3
        path[0].vx shouldBe 0.0
        // With nothing beside it the same tick takes it past where the pillar's corner was.
        JumpSim.path(world(), flat, JumpSim.Flight(.5, 70.0, .75), 0.0, 10.5, 60.0, 10.75)[0].x shouldBeGreaterThan 1.05
    }

    "a running jump pushes off with what the game gives it" {
        // The sprint-jump boost and a tick of running: at walking speed if it was not sprinting the tick before.
        JumpSim.takeoff(JumpSim.WALK) shouldBeLessThan 0.2981
        JumpSim.takeoff(JumpSim.WALK) shouldBeGreaterThan 0.2979
        JumpSim.takeoff(JumpSim.SPRINT) shouldBeLessThan 0.3275
        JumpSim.takeoff(JumpSim.SPRINT) shouldBeGreaterThan 0.3273
    }

    "the air jump is kept for when the plain jump will not land" {
        // Three blocks across under a roof too low for any air jump: the plain jump makes it, so
        // that is what is flown. (A guess at how far the jump would go used to send the air jump
        // into the roof here.)
        val low = world(Triple(0, 63, 0), Triple(3, 63, 0), *row(0..3, 67))
        jumps(low, 0, 63, 3, 63) shouldBe true
        // In the air at the top of that jump, it is seen to come down on the landing as it is going.
        JumpSim.lands(world(Triple(0, 63, 0), Triple(3, 63, 0)), JumpSim.Flight(1.6, 65.249, .5, 0.19, 0.003, 0.0), 3.5, 64.0, .5) shouldBe true
        // And with the landing two blocks further on, not.
        JumpSim.lands(world(Triple(0, 63, 0), Triple(5, 63, 0)), JumpSim.Flight(1.6, 65.249, .5, 0.19, 0.003, 0.0), 5.5, 64.0, .5) shouldBe false
    }

    "a jump at the foot of something low is a step up onto it, as in the game" {
        // A full block right in front is jumped onto. Something half a block high right in front
        // is stepped onto instead: the jump never happens. (Half blocks are not in this world of
        // whole ones, so the test stands the fighter half a block up its own block instead: the
        // top of the next one is then half a block over its feet.)
        val seen = ArrayList<JumpSim.Flight>()
        val steps = world(Triple(0, 63, 0), Triple(1, 63, 0), Triple(1, 64, 0), Triple(4, 64, 0))
        // From the floor: a jump up onto the block, and on over the gap behind it with the air jump.
        JumpSim.stepsUp(steps, .5, 64.0, .5, 4.5, 65.0, .5) shouldBe false
        JumpSim.jump(steps, JumpSim.STANDARD, .5, 64.0, .5, 4.5, 65.0, .5, seen = seen) shouldBe true
        seen[0].y shouldBeGreaterThan 64.4
        // From half a block up (feet at 64.5, the block's top half a block over them): a step.
        seen.clear()
        val half = object : JumpSim.Space {
            override fun clear(x: Double, y: Double, z: Double, foot: Double) = steps.clear(x, y, z, foot) && !(x < 1.0 - .3 + 1e-7 && y + foot < 64.5)
        }
        JumpSim.stepsUp(half, .5, 64.5, .5, 4.5, 65.0, .5) shouldBe true
        // Onto the block next door, that is the whole of it: a flight of stairs is two of these.
        JumpSim.jump(half, JumpSim.STANDARD, .5, 64.5, .5, 1.5, 65.0, .5, seen = seen) shouldBe true
        // It is on top of that block after one tick, and not in the air.
        seen[0].y shouldBe 65.0
        seen[0].x shouldBeGreaterThan 0.7
        // And to what lies beyond a gap, it is the step and then the jump from the top of it.
        JumpSim.jump(half, JumpSim.STANDARD, .5, 64.5, .5, 4.5, 65.0, .5) shouldBe true
        // With no air jump in hand and the gap too wide for a plain one, the step gets it no nearer.
        val wide = world(Triple(0, 63, 0), Triple(1, 63, 0), Triple(1, 64, 0), Triple(7, 64, 0))
        val halfWide = object : JumpSim.Space {
            override fun clear(x: Double, y: Double, z: Double, foot: Double) = wide.clear(x, y, z, foot) && !(x < 1.0 - .3 + 1e-7 && y + foot < 64.5)
        }
        JumpSim.jump(halfWide, null, .5, 64.5, .5, 7.5, 65.0, .5) shouldBe false
    }

    "each kit's air jump is the one its passive gives" {
        fun JumpSim.Launch.at(pitch: Double) = speeds(pitch).let { "%.3f/%.3f".format(java.util.Locale.ROOT, it.first, it.second) }
        // DoubleJumpPassive 0.9/0.9: the look flattened onto a fixed lift.
        JumpSim.STANDARD.at(0.0) shouldBe "0.669/0.602"
        JumpSim.STANDARD.at(-90.0) shouldBe "0.000/0.900"
        // The same with 1.2/1.0 (slime, magma cube): never a low jump.
        JumpSim.BOUNCY.at(0.0) shouldBe "0.768/0.922"
        JumpSim.BOUNCY.at(-90.0) shouldBe "0.000/1.000"
        // Wolf and spider go exactly where they look.
        JumpSim.WOLF.at(0.0) shouldBe "1.100/-0.000"
        JumpSim.WOLF.at(-90.0) shouldBe "0.000/1.100"
        JumpSim.SPIDER.at(-45.0) shouldBe "0.707/0.707"
        // The goat: full lift whatever it looks at.
        JumpSim.GOAT.at(0.0) shouldBe "0.900/0.900"
        JumpSim.GOAT.at(-60.0) shouldBe "0.450/0.900"
    }

    "the stronger jumps go further and higher than the standard one" {
        val kinds = listOf("standard" to JumpSim.STANDARD, "bouncy" to JumpSim.BOUNCY, "wolf" to JumpSim.WOLF, "spider" to JumpSim.SPIDER, "goat" to JumpSim.GOAT)
        for ((name, launch) in kinds) {
            val reach = JumpSim.reachOf(launch)
            println("reach of $name: rise ${reach.rise}; " + listOf(-10.0, -3.0, 0.0, 2.0, 4.0, 5.0, 6.0, 7.0, 8.0).joinToString(" ") { "$it=${reach.at(it)}" })
        }
        val standard = JumpSim.reachOf(JumpSim.STANDARD)
        for ((_, launch) in kinds.drop(1)) {
            val reach = JumpSim.reachOf(launch)
            reach.at(0.0) shouldBeGreaterThan standard.at(0.0)
            reach.at(4.0) shouldBeGreaterThan standard.at(4.0)
        }
        // A jump and the standard double jump top out a little over six blocks up; a slime, a
        // spider and above all a wolf go higher; a goat with nothing charged goes no higher, only further.
        standard.rise shouldBe 5.5
        JumpSim.reachOf(JumpSim.BOUNCY).rise shouldBeGreaterThan 6.0
        JumpSim.reachOf(JumpSim.SPIDER).rise shouldBeGreaterThan 6.0
        JumpSim.reachOf(JumpSim.WOLF).rise shouldBeGreaterThan 7.0
        JumpSim.reachOf(JumpSim.GOAT).rise shouldBe 5.5
    }

    "a goat that crouches first leaps a great deal higher" {
        val began = System.nanoTime()
        val half = JumpSim.reachOf(JumpSim.GOAT_HALF)
        val full = JumpSim.reachOf(JumpSim.GOAT_FULL)
        println("reach of a goat half charged: rise ${half.rise}; " + listOf(0.0, 6.0, 9.0, 11.0, 12.0).joinToString(" ") { "$it=${half.at(it)}" })
        println("reach of a goat fully charged: rise ${full.rise}; " + listOf(0.0, 6.0, 12.0, 16.0, 19.0, 20.0, 21.0).joinToString(" ") { "$it=${full.at(it)}" })
        println("both tables worked out in ${(System.nanoTime() - began) / 1_000_000} ms")
        half.rise shouldBeGreaterThan 10.0
        full.rise shouldBeGreaterThan 18.0
        // A tower fifteen blocks up, two away: nothing for an uncharged leap or a half charged one, a leap for a full one.
        val tower = world(Triple(0, 63, 0), Triple(2, 78, 0))
        JumpSim.promised(tower, JumpSim.GOAT, .5, 64.0, .5, 2.5, 79.0, .5) shouldBe false
        JumpSim.promised(tower, JumpSim.GOAT_HALF, .5, 64.0, .5, 2.5, 79.0, .5) shouldBe false
        JumpSim.promised(tower, JumpSim.GOAT_FULL, .5, 64.0, .5, 2.5, 79.0, .5) shouldBe true
        // Nine up is one for half a charge.
        JumpSim.promised(world(Triple(0, 63, 0), Triple(2, 72, 0)), JumpSim.GOAT_HALF, .5, 64.0, .5, 2.5, 73.0, .5) shouldBe true
    }

    "a ledge too high for a standard jump is one for a wolf" {
        val space = world(Triple(0, 63, 0), Triple(2, 70, 0))
        JumpSim.promised(space, JumpSim.STANDARD, .5, 64.0, .5, 2.5, 71.0, .5) shouldBe false
        JumpSim.promised(space, JumpSim.WOLF, .5, 64.0, .5, 2.5, 71.0, .5) shouldBe true
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
        // Whatever it picked goes up by enough in the first tick to be over the pile.
        from.y + JumpSim.STANDARD.speeds(aim.pitch).second shouldBeGreaterThanOrEqual 66.0
    }
})
