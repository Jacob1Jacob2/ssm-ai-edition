package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.bots.combat.DeathMemory
import dev.betrix.superSmashMobsBrawl.bots.combat.DeathMemory.Point
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.UUID

class DeathMemoryTest : StringSpec({
    val camper = UUID(0, 1)
    val other = UUID(0, 2)
    // The camper sits in a cave room; the bot comes down a corridor and is shot off it.
    val camp = Point(2.5, 52.0, 1.5)
    val corridor = Point(4.5, 53.0, -5.5)
    val mouth = Point(11.5, 54.0, -13.5)

    fun DeathMemory.shotOff(now: Long, way: String = "walk", from: Point = corridor, at: Point = camp, holding: Boolean = true) =
        killedBy(camper, at, holding, fell = true, exchanged = false, hitAt = listOf(mouth, from), standing = listOf(from), way = way, now = now)

    "one death to a camper is enough for an expert: that ground and that way in are not tried again" {
        val memory = DeathMemory(1)
        val trap = memory.shotOff(1000)
        trap shouldNotBe null
        memory.learned(trap!!) shouldBe true
        trap.spots shouldContainExactly listOf(corridor)
        memory.trapOf(camper, camp) shouldBe trap
        memory.wayBarred(camper, camp, "walk", 1100) shouldBe true
        // It keeps out of their view for a minute and a half...
        trap.keepOutUntil shouldBe 1000 + DeathMemory.QUIET_TICKS
        // ...but something it has not died doing is still worth one try, and it is not giving up yet.
        memory.wayBarred(camper, camp, "cross:DROP", 1100) shouldBe false
        memory.standingOff(camper, camp, 1100) shouldBe null
    }

    "it idles further off than it was ever hit from" {
        val memory = DeathMemory(1)
        val trap = memory.shotOff(1000)!!
        // First hit at the cave mouth, about seventeen blocks from the camper.
        trap.keepOut shouldBe (mouth.distance(camp) + 3.0)
        // A point-blank kill still keeps it a fair way back; a shot from across the map does not ban the map.
        DeathMemory(1).killedBy(camper, camp, true, true, false, listOf(camp), listOf(camp), "walk", 0)!!.keepOut shouldBe DeathMemory.MIN_KEEP_OUT
        DeathMemory(1).killedBy(camper, camp, true, true, false, listOf(Point(80.0, 52.0, 1.5)), emptyList(), "walk", 0)!!.keepOut shouldBe DeathMemory.MAX_KEEP_OUT
    }

    "being worn down by their fire from further off than it thought they reached moves it further back" {
        val memory = DeathMemory(1)
        val trap = memory.killedBy(camper, camp, true, true, false, listOf(corridor), listOf(corridor), "walk", 0)!!
        val before = trap.keepOut
        // Nearer than it already keeps: nothing new.
        memory.hitFrom(trap, corridor) shouldBe false
        trap.keepOut shouldBe before
        memory.hitFrom(trap, mouth) shouldBe true
        trap.keepOut shouldBe (mouth.distance(camp) + 3.0)
        memory.hitFrom(trap, Point(90.0, 52.0, 1.5)) shouldBe true
        trap.keepOut shouldBe DeathMemory.MAX_KEEP_OUT
    }

    "a second death in the same trap ends the going in, for longer each time it happens again" {
        val memory = DeathMemory(1)
        memory.shotOff(1000)
        val trap = memory.shotOff(2000, way = "cross:DROP")!!
        trap.deaths shouldBe 2
        trap.standoffUntil shouldBe 2000 + 4 * DeathMemory.QUIET_TICKS
        trap.keepOutUntil shouldBe trap.standoffUntil
        memory.standingOff(camper, camp, 2500) shouldBe trap
        // Nothing is tried while it stands off, not even a way that has never failed.
        memory.wayBarred(camper, camp, "jump", 2500) shouldBe true
        // When the stand-off is over, only what has not cost a life is open again.
        val after = trap.standoffUntil + 1
        memory.standingOff(camper, camp, after) shouldBe null
        memory.wayBarred(camper, camp, "jump", after) shouldBe false
        memory.wayBarred(camper, camp, "cross:DROP", after) shouldBe true
        memory.wayBarred(camper, camp, "walk", after) shouldBe true
        memory.shotOff(after + 100, way = "jump")!!.standoffUntil shouldBe after + 100 + 9 * DeathMemory.QUIET_TICKS
    }

    "the lesson is about the camper sitting there, not about the place or the player" {
        val memory = DeathMemory(1)
        memory.shotOff(1000)
        memory.shotOff(2000)
        // They came out to fight: it fights them.
        memory.trapOf(camper, Point(20.0, 63.0, 10.0)) shouldBe null
        memory.wayBarred(camper, Point(20.0, 63.0, 10.0), "walk", 2100) shouldBe false
        // They shuffled a few blocks inside their hole: still the same trap.
        memory.trapOf(camper, Point(4.5, 52.0, 3.5)) shouldNotBe null
        // Somebody else standing in the same room has not earned it.
        memory.trapOf(other, camp) shouldBe null
        // Back in their hole, and the bot remembers.
        memory.standingOff(camper, camp, 2100) shouldNotBe null
    }

    "losing a fight that moves around the map teaches nothing about where it was lost" {
        val memory = DeathMemory(1)
        memory.shotOff(1000, holding = false) shouldBe null
        memory.traps.size shouldBe 0
        // Killed by damage after hitting back properly is a lost fight, even against someone standing still...
        memory.killedBy(camper, camp, holding = true, fell = false, exchanged = true, hitAt = listOf(corridor), standing = listOf(corridor), way = "walk", now = 0) shouldBe null
        memory.trapOf(camper, camp) shouldBe null
        // ...the first time. Losing the same fight to them in the same place again is a pattern.
        val again = memory.killedBy(camper, camp, holding = true, fell = false, exchanged = true, hitAt = listOf(corridor), standing = listOf(corridor), way = "walk", now = 50)
        again shouldNotBe null
        again!!.deaths shouldBe 1
        // Once it is a known trap, every death in it counts, however it went.
        memory.killedBy(camper, camp, holding = false, fell = false, exchanged = true, hitAt = listOf(corridor), standing = listOf(corridor), way = "walk", now = 100)!!.deaths shouldBe 2
    }

    "shot dead by a camper without getting a blow in is a trap the first time" {
        val memory = DeathMemory(1)
        memory.killedBy(camper, camp, holding = true, fell = false, exchanged = false, hitAt = listOf(corridor), standing = listOf(corridor), way = "walk", now = 0) shouldNotBe null
        memory.trapOf(camper, camp) shouldNotBe null
    }

    "weaker bots need it to happen more often before they catch on" {
        val hard = DeathMemory(2)
        hard.shotOff(1000)
        hard.trapOf(camper, camp) shouldBe null
        hard.wayBarred(camper, camp, "walk", 1100) shouldBe false
        hard.shotOff(2000)
        hard.trapOf(camper, camp)!!.keepOutUntil shouldBe 2000 + DeathMemory.QUIET_TICKS
        hard.standingOff(camper, camp, 2100) shouldBe null
        hard.shotOff(3000)
        hard.standingOff(camper, camp, 3100) shouldNotBe null
        val normal = DeathMemory(3)
        normal.shotOff(1000); normal.shotOff(2000)
        normal.trapOf(camper, camp) shouldBe null
        normal.shotOff(3000)
        normal.trapOf(camper, camp) shouldNotBe null
    }

    "the ground it was hit on is remembered once, however often it is hit there" {
        val memory = DeathMemory(1)
        memory.shotOff(1000)
        memory.shotOff(2000, from = Point(5.0, 53.0, -5.0))
        val trap = memory.shotOff(8000, from = Point(9.5, 54.0, -6.5))!!
        trap.spots shouldContainExactly listOf(corridor, Point(9.5, 54.0, -6.5))
    }

    "a jump it fell to its death on by itself is left alone for a minute, then for good" {
        val memory = DeathMemory(1)
        val hop = RouteNode(4, 53 * 16, -5) to RouteNode(2, 52 * 16, -1)
        memory.anyHops shouldBe false
        memory.fellOn(hop, 1000) shouldBe 1
        memory.hopBarred(hop.first, hop.second, 1500) shouldBe true
        memory.hopBarred(hop.second, hop.first, 1500) shouldBe false
        memory.hopBarred(hop.first, hop.second, 1000 + DeathMemory.HOP_REST_TICKS) shouldBe false
        memory.fellOn(hop, 5000) shouldBe 2
        memory.hopBarred(hop.first, hop.second, 5_000_000) shouldBe true
    }

    "a way in that ended in the void with nobody's help is not tried on them there again" {
        val memory = DeathMemory(1)
        memory.fellTrying("cross:DROP", camp)
        memory.wayBarred(camper, camp, "cross:DROP", 100) shouldBe true
        memory.wayBarred(camper, camp, "jump", 100) shouldBe false
        memory.wayBarred(camper, Point(30.0, 60.0, 30.0), "cross:DROP", 100) shouldBe false
        memory.clear()
        memory.wayBarred(camper, camp, "cross:DROP", 100) shouldBe false
    }
})
