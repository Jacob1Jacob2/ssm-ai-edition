package dev.betrix.superSmashMobsBrawl.utils

import dev.betrix.superSmashMobsBrawl.services.EnergyHud
import dev.betrix.superSmashMobsBrawl.services.SidebarOverflow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor

/** The match sidebar with many fighters: one to a line, the settings on top, and nobody left off. */
class SidebarLayoutTest : StringSpec({
    "room is made step by step, and only what is over fifteen lines goes under the box" {
        for (fighters in 1..6) SidebarLayout.of(fighters) shouldBe SidebarLayout.Shape(4, true, 0)
        for (fighters in 7..8) SidebarLayout.of(fighters) shouldBe SidebarLayout.Shape(2, true, 0)
        for (fighters in 9..10) SidebarLayout.of(fighters) shouldBe SidebarLayout.Shape(2, false, 0)
        for (fighters in 11..16) SidebarLayout.of(fighters) shouldBe SidebarLayout.Shape(2, false, fighters - 10)
    }

    "every fighter has a line of their own at every size, and the box never holds more than fifteen" {
        for (fighters in 0..16) {
            val shape = SidebarLayout.of(fighters)
            // settings, divider, heading, one line a fighter, footer (and two blanks while there is room)
            shape.rows(fighters) shouldBe shape.settingsLines + 2 + fighters + 1 + if (shape.blanks) 2 else 0
            (shape.rows(fighters) - shape.overflow) shouldBeLessThanOrEqual SidebarLayout.LINES
            shape.overflow shouldBeLessThanOrEqual SidebarLayout.MAX_OVERFLOW
        }
    }

    "lines under the box start where the box's lines start, on a strip exactly as wide as the box" {
        val names = listOf("3 Butterbloom", "3 Charlie_Skirk", "2 _EternalHorror_", "2 iii", "AI Edition J9IhcC0r")
        val box = 133
        val own = Component.text("3 blufuffydragons", NamedTextColor.GREEN)
        val rows = names.mapIndexed { i, name -> SidebarOverflow.Row(Component.text(name), EnergyHud.widthOf(Component.text(name)), names.size - i) }
        val advance = box - SidebarOverflow.NUMBER_GAP - SidebarOverflow.numberWidth(6)
        val (drawn, width) = SidebarOverflow.trace(SidebarOverflow.host(own, EnergyHud.widthOf(own), advance, box, rows))
        // To the client the line is as wide as it was told to be, wherever the drawing went.
        width shouldBe advance
        drawn.first().let { it.below shouldBe 0; it.from shouldBe 0; it.text shouldBe "3 blufuffydragons" }
        for (below in 1..names.size) {
            val line = drawn.filter { it.below == below }
            val strip = line.filter { it.what == "strip" }
            // From two pixels before the text to two past the widest line, with no gap and no overlap.
            strip.first().from shouldBe -SidebarOverflow.EDGE
            strip.last().to shouldBe box + SidebarOverflow.EDGE
            strip.zipWithNext().forEach { (a, b) -> b.from shouldBe a.to }
            line.single { it.what == "text" }.let { it.from shouldBe 0; it.text shouldBe names[below - 1] }
            line.single { it.what == "number" }.let { it.to shouldBe box + SidebarOverflow.EDGE; it.text shouldBe "${names.size - below + 1}" }
        }
        drawn.map { it.below }.distinct() shouldContainExactly (0..names.size).toList()
    }

    "the two halves of a settings line start at the same place on both lines" {
        val firsts = listOf(Component.text("Items: Off"), Component.text("Difficulty: Expert"))
        val column = firsts.maxOf(EnergyHud::widthOf) + 9
        val starts = firsts.map { first ->
            val line = EnergyHud.beside(first, Component.text("Mode: FFA"), column, pack = true)
            // What the spacer glyph moves the pen by: its place in the pack's table of gaps.
            EnergyHud.widthOf(first) + (line.children()[1] as TextComponent).content().single().code - 0xF000 - 512
        }
        starts.distinct() shouldContainExactly listOf(column)
        // Letters are measured, not counted: three narrow ones are a third of three wide ones.
        EnergyHud.widthOf(Component.text("iii")) shouldBe 6
        EnergyHud.widthOf(Component.text("AAA")) shouldBe 18
    }

    "without the pack the gap is made of spaces, never fewer than one" {
        val cell = Component.text("Difficulty: Expert")
        val line = EnergyHud.beside(cell, Component.text("Mode: FFA"), EnergyHud.widthOf(cell) + 9, pack = false)
        (line.children()[1] as TextComponent).content() shouldBe "   "
        val tight = EnergyHud.beside(cell, Component.text("x"), 0, pack = false)
        (tight.children()[1] as TextComponent).content() shouldBe " "
    }
})
