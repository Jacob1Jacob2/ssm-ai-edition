package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.bots.combat.BlastTactics
import dev.betrix.superSmashMobsBrawl.bots.combat.DodgeTactics
import dev.betrix.superSmashMobsBrawl.bots.combat.SummonTactics
import dev.betrix.superSmashMobsBrawl.bots.combat.WallTactics
import dev.betrix.superSmashMobsBrawl.bots.combat.WeaveTactics
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Getting out of the way of what kit abilities throw: whether it would hit, and which way to step. */
class DodgeTacticsTest : StringSpec({
    "something coming straight at a body will hit it; something going past, or going away, will not" {
        // A fireball ten blocks off along x, coming at 0.9 a tick.
        val straight = DodgeTactics.pass(px = -10.0, pz = 0.0, vx = 0.9, vz = 0.0, cx = 0.0, cz = 0.0)!!
        straight.ticks shouldBe (10.0 / 0.9 plusOrMinus 1e-9)
        straight.miss shouldBe (0.0 plusOrMinus 1e-9)
        DodgeTactics.threatens(straight, radius = 0.45, above = 1.0) shouldBe true
        // The same, three blocks to one side of the body: it goes past.
        val wide = DodgeTactics.pass(-10.0, 3.0, 0.9, 0.0, 0.0, 0.0)!!
        wide.miss shouldBe (3.0 plusOrMinus 1e-9)
        DodgeTactics.threatens(wide, 0.45, 1.0) shouldBe false
        // A near thing still counts: a body is not a point, and neither is a fireball.
        DodgeTactics.threatens(DodgeTactics.pass(-10.0, 1.2, 0.9, 0.0, 0.0, 0.0)!!, 0.45, 1.0) shouldBe true
        // Going the other way, or lying still.
        DodgeTactics.pass(-10.0, 0.0, -0.9, 0.0, 0.0, 0.0) shouldBe null
        DodgeTactics.pass(-10.0, 0.0, 0.0, 0.0, 0.0, 0.0) shouldBe null
    }
    "it has to come by soon, and at the height of a body" {
        // Crawling in from sixty blocks: not yet anything to do with it.
        val far = DodgeTactics.pass(-60.0, 0.0, 0.45, 0.0, 0.0, 0.0)!!
        DodgeTactics.threatens(far, 0.4, 1.0) shouldBe false
        val near = DodgeTactics.pass(-8.0, 0.0, 0.9, 0.0, 0.0, 0.0)!!
        // Over its head, or under the ledge it stands on.
        DodgeTactics.threatens(near, 0.45, above = 4.0) shouldBe false
        DodgeTactics.threatens(near, 0.45, above = -2.0) shouldBe false
        // At its feet, at its head.
        DodgeTactics.threatens(near, 0.45, above = 0.0) shouldBe true
        DodgeTactics.threatens(near, 0.45, above = 1.8) shouldBe true
    }
    "the way out is across its path, to the side the body is already on" {
        // Going along +x with the body a little to the +z side of its line: step to +z.
        val left = DodgeTactics.pass(-10.0, 0.0, 1.0, 0.0, 0.0, 0.4)!!
        left.side shouldBe 1
        DodgeTactics.aside(1.0, 0.0, left.side).let { (x, z) -> x shouldBe (0.0 plusOrMinus 1e-9); z shouldBe (1.0 plusOrMinus 1e-9) }
        // A little to the other side: step the other way.
        val right = DodgeTactics.pass(-10.0, 0.0, 1.0, 0.0, 0.0, -0.4)!!
        right.side shouldBe -1
        val (ax, az) = DodgeTactics.aside(1.0, 0.0, right.side)
        az shouldBe (-1.0 plusOrMinus 1e-9)
        ax shouldBe (0.0 plusOrMinus 1e-9)
        // Whatever way it is going, the step is square to it and one block long.
        for ((vx, vz) in listOf(0.3 to 0.8, -1.2 to 0.4, 0.0 to -0.9)) for (side in listOf(1, -1)) {
            val (sx, sz) = DodgeTactics.aside(vx, vz, side)
            (sx * vx + sz * vz) shouldBe (0.0 plusOrMinus 1e-9)
            (sx * sx + sz * sz) shouldBe (1.0 plusOrMinus 1e-9)
        }
        // The step takes the body further from its line, not across it.
        val (bx, bz) = DodgeTactics.aside(1.0, 0.0, left.side)
        val after = DodgeTactics.pass(-10.0, 0.0, 1.0, 0.0, 0.0 + bx * 2, 0.4 + bz * 2)!!
        (after.miss > left.miss) shouldBe true
    }
    "where it is already stepping to is kept to while that is out of the way of the next thing too" {
        // A stream of eggs along x; the bot is stepping to a spot two and a half blocks to the +z side.
        DodgeTactics.offLine(-10.0, 0.0, 1.5, 0.0, 0.0, 2.5) shouldBe (2.5 plusOrMinus 1e-9)
        DodgeTactics.clear(DodgeTactics.offLine(-10.0, 0.0, 1.5, 0.0, 0.0, 2.5), radius = 0.5) shouldBe true
        // The next egg is aimed at where the bot has got to: its line runs a block from that spot, which is no longer enough.
        DodgeTactics.clear(DodgeTactics.offLine(-10.0, 1.5, 1.5, 0.0, 0.0, 2.5), radius = 0.5) shouldBe false
        // The line, not the thing: how far along it the spot is makes no difference.
        DodgeTactics.offLine(-10.0, 0.0, 1.5, 0.0, 40.0, 2.5) shouldBe (2.5 plusOrMinus 1e-9)
        DodgeTactics.offLine(0.0, 0.0, 1.0, 1.0, 1.0, -1.0) shouldBe (kotlin.math.sqrt(2.0) plusOrMinus 1e-9)
    }
    "a bot comes at somebody who is facing it in a zigzag: off the straight line, still at a run, turning at no regular time" {
        // Straight ahead, turned to one side and to the other: the same speed, and mirror images.
        val (f1, s1) = WeaveTactics.turned(1f, 0f, WeaveTactics.ANGLE)
        val (f2, s2) = WeaveTactics.turned(1f, 0f, -WeaveTactics.ANGLE)
        (f1 * f1 + s1 * s1).toDouble() shouldBe (1.0 plusOrMinus 1e-5)
        f1.toDouble() shouldBe (f2.toDouble() plusOrMinus 1e-6)
        s1.toDouble() shouldBe (-s2.toDouble() plusOrMinus 1e-6)
        // Still mostly forwards: enough to keep running (a run needs the forward key well down).
        (f1 > 0.8f) shouldBe true
        // And enough to the side to be a body's width off the line within half a second.
        (kotlin.math.abs(s1) * 0.28 * 10 > DodgeTactics.BODY + DodgeTactics.MARGIN) shouldBe true
        // Turning nothing changes nothing.
        WeaveTactics.turned(0.6f, -0.3f, 0.0).let { (f, s) -> f.toDouble() shouldBe (0.6 plusOrMinus 1e-6); s.toDouble() shouldBe (-0.3 plusOrMinus 1e-6) }
        // Each stretch is short, and they are not all the same length.
        val stretches = (0..99).map { WeaveTactics.stretch(it / 100.0) }
        stretches.min() shouldBe WeaveTactics.SHORTEST
        stretches.max() shouldBe WeaveTactics.LONGEST
        (stretches.toSet().size > 5) shouldBe true
        WeaveTactics.stretch(1.0) shouldBe WeaveTactics.LONGEST
        // Only for somebody looking its way, and only by players who move well.
        WeaveTactics.facing(10.0) shouldBe true
        WeaveTactics.facing(-25.0) shouldBe true
        WeaveTactics.facing(90.0) shouldBe false
        WeaveTactics.weaves(BotDifficulty.EXPERT) shouldBe true
        WeaveTactics.weaves(BotDifficulty.NORMAL) shouldBe false
        // Not at arm's length, and not from across the map.
        (WeaveTactics.FROM < WeaveTactics.TO) shouldBe true
    }
    "a bot runs from something that is winding up beside it: straight away, far enough to be clear, and waits outside for it to go off" {
        // A Creeper's blast reaches eight blocks.
        BlastTactics.inReach(gap = 3.0, radius = 8.0) shouldBe true
        BlastTactics.inReach(gap = 9.0, radius = 8.0) shouldBe false
        // From three blocks off: six and a half more, to be a block and a half clear.
        BlastTactics.run(gap = 3.0, radius = 8.0) shouldBe (6.5 plusOrMinus 1e-9)
        // Nearly out already: a few steps all the same, not a shuffle.
        BlastTactics.run(gap = 7.9, radius = 8.0) shouldBe (2.5 plusOrMinus 1e-9)
        // Straight away from it: a body east of the middle runs east.
        BlastTactics.way(dx = 3.0, dz = 0.0, turn = 0.0, far = 6.5).let { (x, z) -> x shouldBe (6.5 plusOrMinus 1e-9); z shouldBe (0.0 plusOrMinus 1e-9) }
        // Round to one side when straight away is a wall or a drop: as far, and still away from it.
        for (turn in BlastTactics.TURNS) {
            val (x, z) = BlastTactics.way(3.0, 0.0, turn, 6.5)
            kotlin.math.hypot(x, z) shouldBe (6.5 plusOrMinus 1e-9)
            (kotlin.math.hypot(3.0 + x, z) > 6.0) shouldBe true
        }
        BlastTactics.TURNS.first() shouldBe 0.0
        // Standing right on it there is no "away": any way will do, and it is still a full run.
        BlastTactics.way(0.0, 0.0, 0.0, 4.0).let { (x, z) -> kotlin.math.hypot(x, z) shouldBe (4.0 plusOrMinus 1e-9) }
        // Out of it, it does not walk straight back in after whoever is hissing.
        BlastTactics.waitsOutside(gap = 9.0, radius = 8.0) shouldBe true
        BlastTactics.waitsOutside(gap = 7.0, radius = 8.0) shouldBe false
        BlastTactics.waitsOutside(gap = 14.0, radius = 8.0) shouldBe false
        // Where the floor is short it runs as far as there is, the longest first.
        BlastTactics.SHORTER.toList() shouldBe BlastTactics.SHORTER.sortedDescending()
        // A fuse of a second and a half is thirty ticks: an Expert has twenty-six of them to run in, which is seven blocks.
        ((30 - BlastTactics.readyAfter(BotDifficulty.EXPERT)) * 0.28 > 7.0) shouldBe true
        (BlastTactics.TOO_LATE < 30 - BlastTactics.readyAfter(BotDifficulty.NORMAL)) shouldBe true
    }
    "how far it has to get, and how soon it starts" {
        val dead = DodgeTactics.pass(-10.0, 0.0, 0.9, 0.0, 0.0, 0.0)!!
        // Dead on: all of half a body, the thing's own width and the gap.
        DodgeTactics.needs(dead, 0.45) shouldBe (0.45 + DodgeTactics.BODY + DodgeTactics.MARGIN plusOrMinus 1e-9)
        // Already clear: nothing.
        DodgeTactics.needs(DodgeTactics.pass(-10.0, 3.0, 0.9, 0.0, 0.0, 0.0)!!, 0.45) shouldBe 0.0
        // A step to the side is further than that, so one step is enough for anything a kit throws.
        (DodgeTactics.SIDESTEP > 0.6 + DodgeTactics.BODY + DodgeTactics.MARGIN) shouldBe true
        // The better the player the sooner; nobody is quicker than a fifth of a second.
        val ready = BotDifficulty.entries.map { DodgeTactics.readyAfter(it) }
        ready shouldBe ready.sortedDescending()
        (ready.last() >= 4) shouldBe true
        ready.first() shouldNotBe ready.last()
        // A Magma Blast (0.9 a tick) from ten blocks leaves an Expert seven ticks to move in; an arrow (2 a tick) leaves one.
        val blast = DodgeTactics.pass(-10.0, 0.0, 0.9, 0.0, 0.0, 0.0)!!.ticks - DodgeTactics.readyAfter(BotDifficulty.EXPERT)
        val arrow = DodgeTactics.pass(-10.0, 0.0, 2.0, 0.0, 0.0, 0.0)!!.ticks - DodgeTactics.readyAfter(BotDifficulty.EXPERT)
        (blast > 6.0) shouldBe true
        (arrow < 2.0) shouldBe true
    }
    "waiting outside something that is winding up, a bot does not walk or get carried back in" {
        val radius = 8.0
        // Ten blocks off: a step toward it that ends nine off is back too near; a step along its edge or away is not.
        BlastTactics.walksIn(gapNow = 10.0, gapAhead = 9.0, radius = radius) shouldBe true
        BlastTactics.walksIn(10.0, 10.0, radius) shouldBe false
        BlastTactics.walksIn(10.0, 11.0, radius) shouldBe false
        // From further out a step toward it is fine until it comes within the room it keeps.
        BlastTactics.walksIn(12.0, 11.0, radius) shouldBe false
        BlastTactics.walksIn(9.6, 9.1, radius) shouldBe false
        BlastTactics.walksIn(9.5, 8.9, radius) shouldBe true
        // Where it ran to is outside the room it keeps: it is not held fast on the spot it stopped on.
        (BlastTactics.CLEAR > BlastTactics.KEEP) shouldBe true
        // A dash of ten blocks straight at it from eleven off ends a block from the middle of it.
        BlastTactics.nearest(dx = 11.0, dz = 0.0, ux = -1.0, uz = 0.0, far = 10.0) shouldBe (1.0 plusOrMinus 1e-9)
        BlastTactics.carriesIn(11.0, 0.0, -1.0, 0.0, 10.0, radius) shouldBe true
        // A longer one goes right through the middle and out the far side: that counts too.
        BlastTactics.nearest(11.0, 0.0, -1.0, 0.0, 25.0) shouldBe (0.0 plusOrMinus 1e-9)
        BlastTactics.carriesIn(11.0, 0.0, -1.0, 0.0, 25.0, radius) shouldBe true
        // A short hop toward it that stops outside, a dash away from it and a dash across in front of it do not.
        BlastTactics.carriesIn(11.0, 0.0, -1.0, 0.0, 1.5, radius) shouldBe false
        BlastTactics.carriesIn(11.0, 0.0, 1.0, 0.0, 10.0, radius) shouldBe false
        BlastTactics.carriesIn(11.0, 0.0, 0.0, 1.0, 10.0, radius) shouldBe false
        // Across in front of it but passing close by: in.
        BlastTactics.carriesIn(6.0, -8.0, 0.0, 1.0, 16.0, radius) shouldBe true
        // Standing inside the room it keeps, away from it and along its edge are still free to go.
        BlastTactics.carriesIn(8.8, 0.0, 1.0, 0.0, 10.0, radius) shouldBe false
        BlastTactics.carriesIn(8.8, 0.0, 0.0, 1.0, 10.0, radius) shouldBe false
        // A running jump at it from ten and a half blocks off comes down beside it; from fifteen off it does not.
        BlastTactics.carriesIn(10.5, 0.0, -1.0, 0.0, BlastTactics.HOP, radius) shouldBe true
        BlastTactics.carriesIn(15.0, 0.0, -1.0, 0.0, BlastTactics.HOP, radius) shouldBe false
        // Something that does not carry it anywhere is never held back.
        BlastTactics.carriesIn(11.0, 0.0, -1.0, 0.0, 0.0, radius) shouldBe false
    }
    "what a kit sends to fight for it is hit back at when a few swings will finish it" {
        // A Wither Image and a bear cub are sent to fight; a Wolf's cub holds somebody down and a Pig's bomb goes off.
        SummonTactics.fights("WITHER_SKELETON") shouldBe true
        SummonTactics.fights("POLAR_BEAR") shouldBe true
        SummonTactics.fights("WOLF") shouldBe false
        SummonTactics.fights("PIG") shouldBe false
        // A Wither Image has sixteen health: four swings of five, three of six.
        SummonTactics.swings(16.0, 5.0) shouldBe 4
        SummonTactics.swings(16.0, 6.0) shouldBe 3
        SummonTactics.swings(0.5, 6.0) shouldBe 1
        // Worth it for any kit's swing; a grown bear's thirty health only for one that hits hard enough.
        SummonTactics.worthIt(16.0, 4.0) shouldBe true
        SummonTactics.worthIt(12.0, 4.0) shouldBe true
        SummonTactics.worthIt(30.0, 5.0) shouldBe true
        SummonTactics.worthIt(30.0, 4.0) shouldBe false
        // Something that cannot be hurt at all is never worth it.
        SummonTactics.worthIt(16.0, 0.0) shouldBe false
    }
    "a wall that hurts whoever touches it is kept out of, and stepped out of by the shortest way" {
        // A Spore Wall three blocks wide along x: blocks 10..12 by 20, so corners 10..13 by 20..21. It reaches 0.55 beyond them.
        val (minX, minZ, maxX, maxZ) = listOf(10.0, 20.0, 13.0, 21.0)
        fun touches(x: Double, z: Double, margin: Double = WallTactics.MARGIN) = WallTactics.touches(x, z, minX, minZ, maxX, maxZ, 0.55, margin)
        // Leaning on the front of it a body is touched; a block and a half back it is not.
        touches(11.5, 19.5, 0.0) shouldBe true
        touches(11.5, 18.5, 0.0) shouldBe false
        // Square at the corners: past its end and in front of it, each by less than it reaches, is touched.
        touches(13.7, 19.4, 0.0) shouldBe true
        // The room a bot keeps is on top of what touches it.
        touches(11.5, 18.9, 0.0) shouldBe false
        touches(11.5, 18.9) shouldBe true
        // The middle of the block beside its end is within its reach, the one after is not: a way round passes two blocks from its end.
        touches(13.5, 20.5, 0.0) shouldBe true
        touches(14.5, 20.5) shouldBe false
        // And in front of it: the block against it is within reach, the next one is not.
        touches(11.5, 19.5, 0.0) shouldBe true
        touches(11.5, 18.5) shouldBe false
        // How far off it is across the ground: over it, straight in front, and off a corner.
        WallTactics.gap(11.5, 20.5, minX, minZ, maxX, maxZ) shouldBe (0.0 plusOrMinus 1e-9)
        WallTactics.gap(11.5, 17.0, minX, minZ, maxX, maxZ) shouldBe (3.0 plusOrMinus 1e-9)
        WallTactics.gap(16.0, 25.0, minX, minZ, maxX, maxZ) shouldBe (5.0 plusOrMinus 1e-9)
        // Its height: beside it on the floor and standing on top of it count, well over it or under it do not.
        WallTactics.level(64.0, 64.0, 67.0, 0.55) shouldBe true
        WallTactics.level(67.0, 64.0, 67.0, 0.55) shouldBe true
        WallTactics.level(68.0, 64.0, 67.0, 0.55) shouldBe false
        WallTactics.level(61.0, 64.0, 67.0, 0.55) shouldBe false
        // In it, just in front of the middle: straight back out of the front is the shortest way.
        val ways = WallTactics.waysOut(11.5, 19.6, minX, minZ, maxX, maxZ, 0.55)
        ways.size shouldBe 4
        ways.first().first shouldBe (0.0 plusOrMinus 1e-9)
        (ways.first().second < 0.0) shouldBe true
        // Every way out ends clear of it with room to spare, and they come nearest first.
        ways.all { (dx, dz) -> !touches(11.5 + dx, 19.6 + dz) } shouldBe true
        val lengths = ways.map { (dx, dz) -> kotlin.math.hypot(dx, dz) }
        lengths shouldBe lengths.sorted()
        // Looking ahead further than the room it keeps, so it turns before it is in it; and turning as little as will do.
        (WallTactics.AHEAD > WallTactics.MARGIN + 0.5) shouldBe true
        WallTactics.TURNS.toList() shouldBe WallTactics.TURNS.sorted()
        // (The zigzag: kept up for a few seconds after the last thing thrown at it, which is longer than any one
        // stretch of it; and the last steps before a jump are walked straight.)
        (WeaveTactics.WARY > WeaveTactics.LONGEST * 4) shouldBe true
        (WeaveTactics.STRAIGHT >= 3) shouldBe true
        // It takes a bot as long to do something about a wall as about a thing in the air.
        WallTactics.readyAfter(BotDifficulty.EXPERT) shouldBe DodgeTactics.readyAfter(BotDifficulty.EXPERT)
    }
})
