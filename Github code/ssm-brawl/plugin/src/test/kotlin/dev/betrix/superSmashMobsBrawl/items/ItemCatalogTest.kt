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
    "the trampoline, the mist and the mystery join a selection that had everything before them" {
        val fresh = setOf(ArenaItem.POCKET_TRAMPOLINE, ArenaItem.POISON_MIST, ArenaItem.MYSTERY_ABILITY)
        fresh.map { it.rarity } shouldBe listOf(ItemRarity.UNCOMMON, ItemRarity.UNCOMMON, ItemRarity.LEGENDARY)
        val before = (ArenaItem.entries.toSet() - fresh).joinToString("|") { it.id }
        // Saved by the previous version with everything enabled: the three are switched on.
        ArenaItem.restoreSelection(before, 1) shouldBe ArenaItem.entries.toSet()
        // Someone who had switched Food off keeps a hand-picked list: nothing is added behind their back.
        val picky = (ArenaItem.entries.toSet() - fresh - ArenaItem.FOOD).joinToString("|") { it.id }
        ArenaItem.restoreSelection(picky, 1) shouldBe ArenaItem.entries.toSet() - fresh - ArenaItem.FOOD
        // Saved by this version without them: they were switched off on purpose.
        ArenaItem.restoreSelection(before, ArenaItem.CATALOG_VERSION) shouldBe ArenaItem.entries.toSet() - fresh
    }
    "a mystery ability comes from another kit, never from the excluded four, and counts its uses" {
        val rules = dev.betrix.superSmashMobsBrawl.abilities.MysteryRules
        val kits = mapOf("squid" to listOf("ink_shotgun", "super_squid", "fish_flurry"), "villager" to listOf("trade_scatter", "villager_arts", "sonic_hurr"),
            "phantom" to listOf("phantom_flight", "ravenous_membrane"), "polar_bear" to listOf("fish_gun", "cub_carrier"),
            "blaze" to listOf("inferno", "firefly"), "slime" to listOf("slime_rocket", "slime_slam"), "snowman" to listOf("blizzard", "ice_path"))
        val drawn = rules.candidates(kits, "squid", emptySet()).map { it.second }
        drawn.toSet() shouldBe setOf("sonic_hurr", "ravenous_membrane", "cub_carrier", "inferno", "firefly", "slime_rocket", "slime_slam", "blizzard", "ice_path")
        // Not one the player is already holding from an earlier Mystery Ability.
        ("inferno" in rules.candidates(kits, "squid", setOf("inferno")).map { it.second }) shouldBe false
        rules.candidates(kits, "blaze", emptySet()).first { it.second == "blizzard" } shouldBe ("snowman" to "blizzard")
        listOf("ink_shotgun", "blizzard", "inferno").map(rules::uses) shouldBe listOf(7, 50, 75)
        listOf("ink_shotgun", "slime_rocket", "blizzard").map(rules::cooldownScale) shouldBe listOf(0.5, 1.0, 0.5)
    }
    "an item thrown away sounds like what it is" {
        // Nothing to eat or drink makes the sound of a tool breaking.
        listOf(ArenaItem.FOOD, ArenaItem.GILDED_APPLE, ArenaItem.AWESOME_SOUP).map { it.dropFeel } shouldBe listOf(DropFeel.FOOD, DropFeel.FOOD, DropFeel.SPILL)
        ArenaItem.entries.filter { it.dropFeel == DropFeel.TOOL }.all { Regex(".*_(AXE|SHOVEL|SWORD|PICKAXE|HOE)|BOW").matches(it.material.name) } shouldBe true
        listOf(ArenaItem.CLOUD_IN_A_BOTTLE, ArenaItem.POCKET_TRAMPOLINE, ArenaItem.MYSTERY_ABILITY, ArenaItem.FRAG_GRENADE).map { it.dropFeel } shouldBe
            listOf(DropFeel.GLASS, DropFeel.SLIME, DropFeel.BAG, DropFeel.FUSE)
        // A Mystery Ability's tool is not in the catalogue: a copper tool is a tool, a pumpkin is not.
        listOf("COPPER_AXE", "COPPER_SWORD", "BOW", "CARVED_PUMPKIN", "FEATHER").map(DropFeel::of) shouldBe
            listOf(DropFeel.TOOL, DropFeel.TOOL, DropFeel.TOOL, DropFeel.PLAIN, DropFeel.PLAIN)
    }
    "an enlarged egg is not drawn bigger than the thrower's view of it allows" {
        val sprite = dev.betrix.superSmashMobsBrawl.projectiles.ProjectileSprite
        // Right at the eyes it is an ordinary egg; a couple of blocks out it is most of the way; then full size.
        sprite.scale(1.5, 0.3) shouldBe 1.0
        (sprite.scale(1.5, 2.1) in 1.05..1.15) shouldBe true
        sprite.scale(1.5, 3.9) shouldBe 1.5
        // The 20% of a diamond upgrade is there from the first block it is seen at.
        sprite.scale(1.2, 2.46) shouldBe 1.2
        sprite.scale(1.0, 0.3) shouldBe 1.0
    }
    "the click that opens a mystery ability does not use the tool that comes out of it" {
        val rules = dev.betrix.superSmashMobsBrawl.abilities.MysteryRules
        fun tool() = dev.betrix.superSmashMobsBrawl.abilities.Borrowed("slime", 7, 7, 0.5, 4).also { rules.open(it, 100) }
        // The button is still down: the same click comes in again 4 ticks later, and again.
        val held = tool()
        listOf(100, 104, 108, 112).map { rules.stillBagClick(held, it) } shouldBe listOf(true, true, true, true)
        // ...but a button held for most of a second is meant.
        rules.stillBagClick(held, 116) shouldBe false
        // Let go and clicked again: that is a new click.
        val clicked = tool()
        rules.stillBagClick(clicked, 104) shouldBe true
        rules.stillBagClick(clicked, 113) shouldBe false
        // Clicking as fast as a hand can does not keep the tool waiting for ever.
        val mashed = tool()
        (101..130).filter { it % 3 == 0 }.first { !rules.stillBagClick(mashed, it) } shouldBe 117
    }
})
