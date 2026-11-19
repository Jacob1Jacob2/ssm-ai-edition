package dev.betrix.superSmashMobsBrawl.utils

import dev.betrix.superSmashMobsBrawl.services.EnergyHud
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor

/** The match sidebar with many fighters: everything stays on it, and the names stand in straight columns. */
class SidebarLayoutTest : StringSpec({
    "every number of fighters fits the fifteen lines with the settings and everybody on it" {
        for (fighters in 0..16) {
            val shape = SidebarLayout.of(fighters)
            val fighterLines = if (shape.columns == 1) fighters else (fighters + 1) / 2
            fighterLines shouldBeLessThanOrEqual SidebarLayout.FIGHTER_LINES
            (shape.head + fighterLines + shape.foot) shouldBeLessThanOrEqual SidebarLayout.LINES
        }
    }

    "a small match keeps its four lines of settings and one fighter to a line" {
        for (fighters in 1..6) SidebarLayout.of(fighters) shouldBe SidebarLayout.Shape(1, false)
        for (fighters in 7..8) SidebarLayout.of(fighters) shouldBe SidebarLayout.Shape(1, true)
        for (fighters in 9..16) SidebarLayout.of(fighters) shouldBe SidebarLayout.Shape(2, true)
    }

    "two teams of eight are a column each" {
        // A side is a column, so a column is as long as the bigger side still has fighters.
        val shape = SidebarLayout.of(16)
        (shape.head + 8 + shape.foot) shouldBeLessThanOrEqual SidebarLayout.LINES
        val lopsided = SidebarLayout.of(9)
        lopsided.columns shouldBe 2
        (lopsided.head + 8 + lopsided.foot) shouldBeLessThanOrEqual SidebarLayout.LINES
    }

    "the two columns read left to right and down, in the order of the list" {
        val (left, right) = SidebarLayout.columns(7)
        left shouldContainExactly listOf(0, 2, 4, 6)
        right shouldContainExactly listOf(1, 3, 5)
    }

    "the second column starts at the same place on every line, whatever stands in the first" {
        // The first column of the sixteen-player match this was reported from.
        val names = listOf("3 Achot", "3 AnIdiotInATree", "3 Atoximed", "3 blufuffydragons", "3 Butterbloom", "3 Jacob1Jacob2", "2 AGentleAura", "2 iii")
        val cells = names.map { Component.text(it, NamedTextColor.GREEN) }
        val column = cells.maxOf(EnergyHud::widthOf) + 9
        val starts = cells.map { cell ->
            val line = EnergyHud.beside(cell, Component.text("3 Alex12Y"), column, pack = true)
            // What the spacer glyph moves the pen by: its place in the pack's table of gaps.
            val spacer = (line.children()[1] as TextComponent).content().single().code - 0xF000 - 512
            EnergyHud.widthOf(cell) + spacer
        }
        starts.distinct() shouldContainExactly listOf(column)
        // Letters are measured, not counted: three narrow ones are a third of three wide ones.
        EnergyHud.widthOf(Component.text("iii")) shouldBe 6
        EnergyHud.widthOf(Component.text("AAA")) shouldBe 18
    }

    "without the pack the gap is made of spaces, never fewer than one" {
        val cell = Component.text("3 blufuffydragons")
        val line = EnergyHud.beside(cell, Component.text("3 BobLeeSwagger"), EnergyHud.widthOf(cell) + 9, pack = false)
        (line.children()[1] as TextComponent).content() shouldBe "   "
        val tight = EnergyHud.beside(cell, Component.text("x"), 0, pack = false)
        (tight.children()[1] as TextComponent).content() shouldBe " "
    }
})
