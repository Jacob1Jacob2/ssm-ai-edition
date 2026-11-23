package dev.betrix.superSmashMobsBrawl.bots

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Which part of the bots' picture of an arena is thrown away when a block changes. */
class ChangedCellsTest : StringSpec({
    "a column packs into one number and comes back out of it, either side of zero" {
        for ((x, z) in listOf(0 to 0, 5 to -3, -17 to 240, -1 to -1, 30000 to -30000)) {
            val packed = ChangedCells.pack(x, z)
            (packed shr 32).toInt() shouldBe x
            packed.toInt() shouldBe z
        }
    }
    "everything within reach of a changed column is forgotten, and nothing beyond it" {
        val reach = ChangedCells.REACH
        // Changes either side of zero, where a careless shift or a mask goes wrong.
        for ((x, z) in listOf(0 to 0, 7 to 7, 8 to -1, -1 to -8, -9 to 15, 123 to -77)) {
            val area = ChangedCells.area(listOf(ChangedCells.pack(x, z)), reach)
            area.size shouldBe (2 * reach + 1) * (2 * reach + 1)
            for (dx in -reach..reach) for (dz in -reach..reach) (ChangedCells.pack(x + dx, z + dz) in area) shouldBe true
            (ChangedCells.pack(x + reach + 1, z) in area) shouldBe false
            (ChangedCells.pack(x, z - reach - 1) in area) shouldBe false
            (ChangedCells.pack(x - reach - 1, z + reach + 1) in area) shouldBe false
        }
    }
    "changes beside each other share their surroundings; changes apart each bring their own" {
        val reach = ChangedCells.REACH
        val side = 2 * reach + 1
        // A bite out of the floor: three blocks in a row. One block wider than a single change, not three times the size.
        val bite = ChangedCells.area(listOf(ChangedCells.pack(10, 10), ChangedCells.pack(11, 10), ChangedCells.pack(12, 10)), reach)
        bite.size shouldBe (side + 2) * side
        // Two at opposite ends of the arena: twice one, and nothing in between.
        val two = ChangedCells.area(listOf(ChangedCells.pack(0, 0), ChangedCells.pack(100, 0)), reach)
        two.size shouldBe 2 * side * side
        (ChangedCells.pack(50, 0) in two) shouldBe false
    }
    "a step looks no further than the reach, and the reach is no wider than it need be" {
        // A fighter steps one block, hops up to four across a gap, and climbs straight up.
        (ChangedCells.REACH >= 4) shouldBe true
        // Every block of reach is another ring of blocks whose steps are worked out again after every blast.
        (ChangedCells.REACH <= 7) shouldBe true
    }
    "no change, nothing forgotten" {
        ChangedCells.area(emptyList(), ChangedCells.REACH).size shouldBe 0
    }
})
