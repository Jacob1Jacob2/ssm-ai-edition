package dev.betrix.superSmashMobsBrawl.utils

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class ClassicCombatTest : StringSpec({
    "armor icons use the classic flat damage reduction rather than modern armor toughness" {
        ClassicCombat.damageAfterArmor(6.0, 6.0) shouldBe (3.12 plusOrMinus 0.00001)
        ClassicCombat.damageAfterArmor(20.0, 6.0) shouldBe (10.4 plusOrMinus 0.00001)
    }
    "a chicken takes more damage than an iron golem from the same hit" {
        ClassicCombat.damageAfterArmor(6.0, 2.5) shouldBe (4.8 plusOrMinus 0.00001)
        ClassicCombat.damageAfterArmor(6.0, 8.0) shouldBe (2.16 plusOrMinus 0.00001)
    }
    "chicken melee is damage points, not doubled hearts" {
        ClassicCombat.damageAfterArmor(4.5, 6.5) shouldBe (2.16 plusOrMinus 0.00001)
    }
    "low health increases launch strength for the same victim" {
        ClassicCombat.knockbackScale(1.1, 20.0) shouldBe (1.1 plusOrMinus 0.00001)
        ClassicCombat.knockbackScale(1.1, 10.0) shouldBe (2.2 plusOrMinus 0.00001)
        ClassicCombat.knockbackScale(2.0, 10.0) shouldBe (4.0 plusOrMinus 0.00001)
    }
    "armor cannot turn damage into healing" {
        ClassicCombat.damageAfterArmor(-5.0, 6.0) shouldBe 0.0
        ClassicCombat.damageAfterArmor(10.0, 100.0) shouldBe (2.0 plusOrMinus 0.00001)
    }
})
