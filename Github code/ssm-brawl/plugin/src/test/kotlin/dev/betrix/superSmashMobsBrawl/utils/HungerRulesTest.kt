package dev.betrix.superSmashMobsBrawl.utils

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class HungerRulesTest : StringSpec({
    "every 2 damage dealt gives half a hunger bar back, so a Chicken Missile's 8 gives two bars" {
        HungerRules.restored(8.0) shouldBe 4
        HungerRules.restored(2.0) shouldBe 1
        HungerRules.restored(7.0) shouldBe 3
        HungerRules.restored(4.5) shouldBe 2
    }

    "any hit that lands gives at least half a bar, and a miss gives nothing" {
        HungerRules.restored(0.5) shouldBe 1
        HungerRules.restored(1.9) shouldBe 1
        HungerRules.restored(0.0) shouldBe 0
        HungerRules.restored(-3.0) shouldBe 0
    }

    "half a bar goes every 6.25 seconds, so a full bar lasts 125 seconds without a hit" {
        HungerRules.DRAIN_SECONDS shouldBe 6.25
        HungerRules.secondsUntilEmpty(20) shouldBe 125.0
        HungerRules.secondsUntilEmpty(1) shouldBe 6.25
        HungerRules.secondsUntilEmpty(0) shouldBe 0.0
    }
})