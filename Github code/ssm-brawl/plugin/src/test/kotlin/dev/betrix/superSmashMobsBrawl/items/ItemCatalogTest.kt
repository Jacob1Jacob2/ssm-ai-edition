package dev.betrix.superSmashMobsBrawl.items

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.plusOrMinus
import kotlin.random.Random

class ItemCatalogTest : StringSpec({
    "rarity is weighted by tier, not by the number of items in that tier" {
        val random = Random(481)
        val totals = mutableMapOf<ItemRarity, Int>()
        repeat(100_000) {
            val rarity = ArenaItem.roll(ArenaItem.entries.toSet(), random)!!.rarity
            totals[rarity] = (totals[rarity] ?: 0) + 1
        }
        ItemRarity.entries.sumOf { it.weight } shouldBe 100
        ItemRarity.entries.forEach { totals.getValue(it) / 1000.0 shouldBe (it.weight.toDouble() plusOrMinus .5) }
    }
    "disabled items never spawn and an empty selection has no fallback drops" {
        ArenaItem.roll(emptySet()) shouldBe null
        val enabled = setOf(ArenaItem.EXTRA_LIFE, ArenaItem.GILDED_APPLE)
        repeat(2000) { (ArenaItem.roll(enabled) in enabled) shouldBe true }
        repeat(20) { ArenaItem.roll(setOf(ArenaItem.FOOD)) shouldBe ArenaItem.FOOD }
    }
    "command accepts full names, ids, spaces and hyphens" {
        ArenaItem.entries.forEach { kind ->
            ArenaItem.parse(kind.label) shouldBe kind
            ArenaItem.parse(kind.id) shouldBe kind
            ArenaItem.parse(kind.id.replace('_', '-')) shouldBe kind
        }
        ArenaItem.parse("not_an_item") shouldBe null
        ArenaItem.RAIN_STAFF.uses shouldBe 125
        ArenaItem.MISSILE_BATTERY.uses shouldBe 16
    }
    "old enable-all selections receive the new helmet while custom lists and opt-outs persist" {
        val oldAll = ArenaItem.entries.filterNot { it == ArenaItem.DIAMOND_HELMET }.joinToString("|") { it.id }
        ArenaItem.restoreSelection(oldAll) shouldBe ArenaItem.entries.toSet()
        ArenaItem.restoreSelection("food|extra_life") shouldBe setOf(ArenaItem.FOOD, ArenaItem.EXTRA_LIFE)
        ArenaItem.restoreSelection("") shouldBe emptySet()
        ArenaItem.restoreSelection(null) shouldBe ArenaItem.entries.toSet()
        ArenaItem.restoreSelection(oldAll, ArenaItem.CATALOG_VERSION) shouldBe ArenaItem.entries.toSet() - ArenaItem.DIAMOND_HELMET
    }
})
