package dev.betrix.superSmashMobsBrawl.bots

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LobbyRosterTest : StringSpec({
    "manual custom opponents lead and random fillers stay built in" {
        LobbyRoster.choose(3, listOf("Custom"), listOf("OldCustom", "B"), listOf("A", "B", "C"), listOf("Custom", "OldCustom")) shouldBe listOf("Custom", "B", "A")
    }
    "changing one selection preserves the other random neighbours" {
        LobbyRoster.choose(3, listOf("D"), listOf("B", "C", "A"), listOf("A", "B", "C", "D"), emptyList()) shouldBe listOf("D", "B", "C")
    }
    "count changes shrink and grow without duplicate names" {
        LobbyRoster.choose(1, emptyList(), listOf("B", "A"), listOf("A", "B", "C"), emptyList()) shouldBe listOf("B")
        LobbyRoster.choose(3, emptyList(), listOf("B"), listOf("A", "B", "C"), emptyList()) shouldBe listOf("B", "A", "C")
    }
    "whoever has just left is the last to be brought back in" {
        LobbyRoster.choose(3, emptyList(), listOf("B"), listOf("A", "B", "C", "D"), emptyList(), gone = listOf("a")) shouldBe listOf("B", "C", "D")
        // (With nobody else to bring in, they are: the lobby is kept as full as it was asked to be.)
        LobbyRoster.choose(3, emptyList(), listOf("B"), listOf("A", "B", "C"), emptyList(), gone = listOf("A")) shouldBe listOf("B", "C", "A")
        // Somebody picked by name is there whatever they did after the last match.
        LobbyRoster.choose(2, listOf("A"), listOf("B"), listOf("A", "B", "C"), emptyList(), gone = listOf("A")) shouldBe listOf("A", "B")
    }
    "who came and who went is told by name" {
        LobbyRoster.comings(listOf("A", "B", "C"), listOf("b", "C", "D")) shouldBe (listOf("D") to listOf("A"))
        // Nobody new and nobody gone: nothing to announce, whatever order they stand in.
        LobbyRoster.comings(listOf("A", "B"), listOf("B", "A")) shouldBe (emptyList<String>() to emptyList<String>())
        LobbyRoster.comings(emptyList(), listOf("A")) shouldBe (listOf("A") to emptyList<String>())
    }
    "case insensitive manual selections are deduplicated and deleted names disappear" {
        LobbyRoster.choose(3, listOf("cUsToM", "CUSTOM", "Deleted"), listOf("Deleted"), listOf("A", "B"), listOf("Custom")) shouldBe listOf("Custom", "A", "B")
    }
})
