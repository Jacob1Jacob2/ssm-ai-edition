package dev.betrix.superSmashMobsBrawl.minigames

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.random.Random

class TeamSidesTest : StringSpec({
    fun fighters(vararg names: String) = names.map { TeamSides.Fighter(UUID.nameUUIDFromBytes(it.toByteArray()), it) }
    fun Map<UUID, TeamSide>.names(side: TeamSide, of: List<TeamSides.Fighter>) = of.filter { this[it.id] == side }.map { it.name }

    "the host and the picked teammate are Blue, everybody else is Red" {
        val all = fighters("You", "Ana", "Bo", "Cy")
        val sides = TeamSides.assign(all[0].id, all, teammates = listOf("Bo"), opponents = emptyList())
        sides.names(TeamSide.BLUE, all) shouldContainExactlyInAnyOrder listOf("You", "Bo")
        sides.names(TeamSide.RED, all) shouldContainExactlyInAnyOrder listOf("Ana", "Cy")
    }

    "the sides are even at every size and the host is always Blue" {
        for (size in listOf(4, 8, 16)) repeat(20) { seed ->
            val all = fighters(*Array(size) { "p$it" })
            val sides = TeamSides.assign(all[0].id, all, emptyList(), emptyList(), Random(seed))
            sides.size shouldBe size
            sides.values.count { it == TeamSide.BLUE } shouldBe size / 2
            sides[all[0].id] shouldBe TeamSide.BLUE
        }
    }

    "a free seat goes to somebody nobody picked before it goes to a picked opponent" {
        val all = fighters("You", "Ana", "Bo", "Cy")
        repeat(30) { seed ->
            val sides = TeamSides.assign(all[0].id, all, emptyList(), opponents = listOf("Ana", "Bo"), random = Random(seed))
            sides.names(TeamSide.BLUE, all) shouldContainExactlyInAnyOrder listOf("You", "Cy")
        }
    }

    "when everybody else was picked to be fought, one of them still fills the team" {
        val all = fighters("You", "Ana", "Bo", "Cy")
        val sides = TeamSides.assign(all[0].id, all, emptyList(), opponents = listOf("Ana", "Bo", "Cy"), random = Random(1))
        sides.values.count { it == TeamSide.BLUE } shouldBe 2
        sides[all[0].id] shouldBe TeamSide.BLUE
    }

    "teammates are taken in the order they were picked, as far as there are seats" {
        val all = fighters("You", "Ana", "Bo", "Cy", "Di", "Ed", "Flo", "Gus")
        val sides = TeamSides.assign(all[0].id, all, teammates = listOf("gus", "ANA", "Bo", "Cy", "Di"), opponents = emptyList())
        // Four seats: the host and the first three picked, whatever their spelling.
        sides.names(TeamSide.BLUE, all) shouldContainExactlyInAnyOrder listOf("You", "Gus", "Ana", "Bo")
    }

    "somebody picked for both lists is a teammate, and names not in the match are passed over" {
        val all = fighters("You", "Ana", "Bo", "Cy")
        val sides = TeamSides.assign(all[0].id, all, teammates = listOf("Nobody", "Ana"), opponents = listOf("Ana", "Bo"))
        sides.names(TeamSide.BLUE, all) shouldContainExactlyInAnyOrder listOf("You", "Ana")
    }

    "a match of bots only, with nobody hosting, is split evenly as well" {
        val all = fighters("a", "b", "c", "d", "e", "f", "g", "h")
        val sides = TeamSides.assign(null, all, emptyList(), emptyList(), Random(7))
        sides.values.count { it == TeamSide.BLUE } shouldBe 4
        sides.values.count { it == TeamSide.RED } shouldBe 4
    }

    "one fighter alone has no sides" {
        TeamSides.assign(null, fighters("You"), emptyList(), emptyList()).shouldBeEmpty()
    }
})
