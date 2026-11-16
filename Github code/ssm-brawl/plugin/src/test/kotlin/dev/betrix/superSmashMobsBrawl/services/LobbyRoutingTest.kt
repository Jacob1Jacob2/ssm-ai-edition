package dev.betrix.superSmashMobsBrawl.services

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LobbyRoutingTest : StringSpec({
    "arrivals stay together until a lobby starts" {
        repeat(20) { LobbyRouting.arrival(emptySet()) shouldBe 1 }
    }
    "choose the lowest free lobby even after earlier games finish" {
        LobbyRouting.arrival(setOf(1)) shouldBe 2
        LobbyRouting.arrival(setOf(1, 2, 4)) shouldBe 3
        LobbyRouting.arrival(setOf(2, 3, 4, 5)) shouldBe 1
    }
    "all busy uses an occupied hub and never creates a sixth lobby" {
        val full = (1..5).toSet()
        (1..5).forEach { chosen ->
            LobbyRouting.arrival(full) { choices -> choices shouldBe (1..5).toList(); chosen } shouldBe chosen
        }
    }
})
