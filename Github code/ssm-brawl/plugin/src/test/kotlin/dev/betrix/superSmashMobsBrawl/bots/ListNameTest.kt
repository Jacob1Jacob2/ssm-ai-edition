package dev.betrix.superSmashMobsBrawl.bots

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class ListNameTest : StringSpec({
    val plain = PlainTextComponentSerializer.plainText()

    "in the player list a rank stands before the name, in bold and in the rank's colour" {
        val name = Component.text("cqsey", NamedTextColor.YELLOW)
        val line = BotIdentity.listName("elite", name)
        plain.serialize(line) shouldBe "ELITE cqsey"
        val tag = line.children().first()
        tag.color() shouldBe NamedTextColor.AQUA
        tag.hasDecoration(TextDecoration.BOLD) shouldBe true
        // The name is as it stood there before: its own colour, and not bold with the rank.
        line.children().last() shouldBe name
        line.hasDecoration(TextDecoration.BOLD) shouldBe false
    }

    "every rank a bot or the owner can have is written out, and the roster's 'mythic' reads MYTHICAL" {
        val name = Component.text("x")
        plain.serialize(BotIdentity.listName("god", name)) shouldBe "GOD x"
        plain.serialize(BotIdentity.listName("mythic", name)) shouldBe "MYTHICAL x"
        plain.serialize(BotIdentity.listName("Divine", name)) shouldBe "DIVINE x"
        plain.serialize(BotIdentity.listName("celestial", name)) shouldBe "CELESTIAL x"
        plain.serialize(BotIdentity.listName("mod", name)) shouldBe "MOD x"
        BotIdentity.rankTag("god")!!.color() shouldBe NamedTextColor.WHITE
        BotIdentity.rankTag("mythic")!!.color() shouldBe NamedTextColor.GREEN
    }

    "somebody with no rank keeps the line they had" {
        val name = Component.text("Steve", NamedTextColor.YELLOW)
        BotIdentity.listName("none", name) shouldBe name
        BotIdentity.listName("", name) shouldBe name
        BotIdentity.rankTag("none") shouldBe null
    }
})
