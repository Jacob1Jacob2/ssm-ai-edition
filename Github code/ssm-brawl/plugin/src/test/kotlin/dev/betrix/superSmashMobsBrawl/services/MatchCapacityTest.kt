package dev.betrix.superSmashMobsBrawl.services

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull

class MatchCapacityTest : StringSpec({
    "existing local installs remain unlimited" {
        MatchCapacity(0, 0).rejection(List(20) { 16 }, 16) shouldBe null
    }
    "two eight-player matches fit the public profile exactly" {
        MatchCapacity(2, 16).rejection(listOf(8), 8) shouldBe null
        MatchCapacity(2, 16).rejection(listOf(8, 8), 2).shouldNotBeNull()
    }
    "fighter cap prevents smaller matches from overfilling" {
        MatchCapacity(2, 16).rejection(listOf(15), 2).shouldNotBeNull()
    }
    "a full-size solo match is allowed when idle" {
        MatchCapacity(2, 16).rejection(emptyList(), 16) shouldBe null
    }
    "request above host limit gets a size explanation" {
        MatchCapacity(2, 8).rejection(emptyList(), 16) shouldBe "Choose a game with at most 8 players on this server."
    }
    "released matches allow the next game" {
        MatchCapacity(2, 16).rejection(listOf(4), 8) shouldBe null
    }
})
