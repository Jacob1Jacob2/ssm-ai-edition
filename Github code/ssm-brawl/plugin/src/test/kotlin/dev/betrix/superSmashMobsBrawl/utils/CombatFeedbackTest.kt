package dev.betrix.superSmashMobsBrawl.utils

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.format.NamedTextColor.*
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class CombatFeedbackTest:StringSpec({
    "remaining lives match the reference colors and elimination title" {
        CombatFeedback.livesColor(4) shouldBe GREEN
        CombatFeedback.livesTitle(3).color() shouldBe YELLOW
        CombatFeedback.livesTitle(2).color() shouldBe GOLD
        CombatFeedback.livesTitle(1).color() shouldBe RED
        CombatFeedback.livesTitle(0).color() shouldBe RED
        PlainTextComponentSerializer.plainText().serialize(CombatFeedback.livesTitle(0)) shouldBe "You Died"
    }
    "last hit display rounds to the nearest whole damage point" {
        CombatFeedback.damageText(4.0) shouldBe "4"
        CombatFeedback.damageText(3.5) shouldBe "4"
        CombatFeedback.damageText(3.49) shouldBe "3"
        CombatFeedback.damageText(3.36) shouldBe "3"
        CombatFeedback.damageText(0.125) shouldBe "0"
        CombatFeedback.damageText(0.1+0.2) shouldBe "0"
        CombatFeedback.damageText(0.0) shouldBe "0"
    }
})
