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
    "case insensitive manual selections are deduplicated and deleted names disappear" {
        LobbyRoster.choose(3, listOf("cUsToM", "CUSTOM", "Deleted"), listOf("Deleted"), listOf("A", "B"), listOf("Custom")) shouldBe listOf("Custom", "A", "B")
    }
})
