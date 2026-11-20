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
        repeat(20) { ArenaItem.roll(setOf(ArenaItem.STEAK)) shouldBe ArenaItem.STEAK }
    }
    "more kinds of food do not make food turn up more often" {
        ArenaItem.foods shouldBe listOf(ArenaItem.PORKCHOP, ArenaItem.STEAK)
        ArenaItem.foods.map { it.healPercent } shouldBe listOf(11, 12)
        ArenaItem.entries.filterNot { it.food }.all { it.healPercent == 0 } shouldBe true
        val random = Random(77)
        val found = mutableMapOf<ArenaItem, Int>()
        repeat(120_000) { val kind = ArenaItem.roll(ArenaItem.entries.toSet(), random)!!; found[kind] = (found[kind] ?: 0) + 1 }
        val commons = ArenaItem.entries.filter { it.rarity == ItemRarity.COMMON }
        val common = commons.sumOf { found.getValue(it) }.toDouble()
        // Five other common things and food: a sixth of the common finds are food, as when Food was one item...
        val others = commons.filterNot { it.food }
        others.size shouldBe 5
        ArenaItem.foods.sumOf { found.getValue(it) } / common shouldBe ((1.0 / 6) plusOrMinus .01)
        others.forEach { found.getValue(it) / common shouldBe ((1.0 / 6) plusOrMinus .01) }
        // ...and which food it is, is even between them.
        found.getValue(ArenaItem.PORKCHOP) / common shouldBe ((1.0 / 12) plusOrMinus .008)
        // With one of them switched off, the other is all the food there is, and still one find among the others.
        val steakOnly = setOf(ArenaItem.STEAK, ArenaItem.FRAG_GRENADE)
        ((1..4000).count { ArenaItem.roll(steakOnly, random) == ArenaItem.STEAK } in 1850..2150) shouldBe true
    }
    "the item menus have a row for each rarity, in the order of the chest they were drawn in" {
        val layout = ItemMenuLayout
        ItemRarity.entries.map { it.block.name.removeSuffix("_CONCRETE") } shouldBe listOf("WHITE", "LIME", "BLUE", "MAGENTA", "YELLOW", "LIGHT_BLUE")
        ItemRarity.entries.forEach { it.pane.name shouldBe it.block.name.removeSuffix("_CONCRETE") + "_STAINED_GLASS_PANE" }
        ItemRarity.entries.map(layout::blockSlot) shouldBe listOf(0, 9, 18, 27, 36, 45)
        ItemRarity.entries.map(layout::paneSlot) shouldBe listOf(1, 10, 19, 28, 37, 46)
        // Seven to a row; the last row has the way back in its last slot.
        ItemRarity.entries.map { layout.inRow(it).size } shouldBe listOf(7, 7, 7, 7, 7, 6)
        val slots = ArenaItem.entries.map(layout::slotOf)
        slots.toSet().size shouldBe ArenaItem.entries.size
        slots.all { it in 0 until layout.SIZE && it % 9 >= 2 && it != layout.BACK } shouldBe true
        layout.BACK shouldBe 53
        fun row(rarity: ItemRarity) = layout.inRow(rarity).sortedBy(layout::slotOf).map { it.material.name.lowercase() }
        row(ItemRarity.COMMON) shouldBe listOf("fire_charge", "sugar", "ender_eye", "experience_bottle", "snowball", "cooked_porkchop", "cooked_beef")
        row(ItemRarity.UNCOMMON) shouldBe listOf("ice", "zombie_spawn_egg", "slime_ball", "flint_and_steel", "slime_block", "pufferfish", "oak_trapdoor")
        row(ItemRarity.RARE) shouldBe listOf("bow", "oak_pressure_plate", "tnt", "gunpowder", "emerald", "paper", "stick")
        row(ItemRarity.EPIC) shouldBe listOf("diamond_axe", "diamond_shovel", "diamond_sword", "diamond_helmet", "poppy", "cake", "golden_apple")
        row(ItemRarity.LEGENDARY) shouldBe listOf("skeleton_skull", "fishing_rod", "golden_pickaxe", "blaze_rod", "mushroom_stew", "golden_hoe", "orange_bundle")
        row(ItemRarity.ULTIMATE) shouldBe listOf("golden_axe", "diamond_pickaxe", "totem_of_undying", "firework_rocket", "anvil", "minecart")
        layout.slotOf(ArenaItem.FLYING_CAR) shouldBe 52
    }
    "shadow walk is a legendary half minute, and the snowball a common find that hurts less" {
        ArenaItem.SHADOW_WALK.rarity shouldBe ItemRarity.LEGENDARY
        ArenaItem.SHADOW_WALK_TICKS shouldBe 600
        ArenaItem.HOMING_SNOWBALL.rarity shouldBe ItemRarity.COMMON
        ArenaItem.SNOWBALL_DAMAGE shouldBe 15.0
        ArenaItem.CLOUD_IN_A_BOTTLE.rarity shouldBe ItemRarity.COMMON
        listOf(ArenaItem.PERSONAL_ROCKET, ArenaItem.ANVIL, ArenaItem.FLYING_CAR).map { it.rarity }.toSet() shouldBe setOf(ItemRarity.ULTIMATE)
        ArenaItem.CAGE_TRAP.rarity shouldBe ItemRarity.UNCOMMON
        listOf(ArenaItem.PERSONAL_ROCKET, ArenaItem.ANVIL, ArenaItem.CAGE_TRAP, ArenaItem.FLYING_CAR).map { it.usesText() } shouldBe
            listOf("4 uses", "1 use", "1 use", "40 seconds of fuel")
        ArenaItem.FLYING_CAR.usesText(0) shouldBe "0 seconds of fuel"
        // The anvil is the Falling Anvil now, and still answers to its old name.
        ArenaItem.ANVIL.label shouldBe "Falling Anvil"
        listOf("anvil", "falling anvil", "Falling_Anvil").map(ArenaItem::parse).toSet() shouldBe setOf(ArenaItem.ANVIL)
        // The cage is oak.
        ArenaItem.CAGE_TRAP.material.name shouldBe "OAK_TRAPDOOR"
        ArenaItem.FLYING_CAR.usesText(1) shouldBe "1 second of fuel"
        ArenaItem.DIAMOND_HELMET.usesText() shouldBe "4 hits"
    }
    "the flying car goes where its three keys send it, and no faster for looking up" {
        fun go(yaw: Float, forward: Boolean = false, up: Boolean = false, down: Boolean = false) = CarRules.velocity(yaw, forward, up, down)
        fun near(a: Triple<Double, Double, Double>, x: Double, y: Double, z: Double) {
            a.first shouldBe (x plusOrMinus 1e-9); a.second shouldBe (y plusOrMinus 1e-9); a.third shouldBe (z plusOrMinus 1e-9)
        }
        // No key held: it hangs where it is.
        near(go(0f), 0.0, 0.0, 0.0)
        // Forward is the way the driver faces: south at yaw 0, west at 90.
        near(go(0f, forward = true), 0.0, 0.0, CarRules.FORWARD)
        near(go(90f, forward = true), -CarRules.FORWARD, 0.0, 0.0)
        // Jump is up, sneak is down, both is neither; and they go with forward, not instead of it.
        near(go(0f, up = true), 0.0, CarRules.CLIMB, 0.0)
        near(go(0f, down = true), 0.0, -CarRules.CLIMB, 0.0)
        near(go(0f, up = true, down = true), 0.0, 0.0, 0.0)
        near(go(180f, forward = true, up = true), 0.0, CarRules.CLIMB, -CarRules.FORWARD)
        // Quick, not silly: a fifth faster than the 15 blocks a second it began at, up and down in proportion.
        CarRules.FORWARD * 20 shouldBe (18.0 plusOrMinus 1e-9)
        CarRules.CLIMB * 20 shouldBe (12.0 plusOrMinus 1e-9)
        ArenaItem.FLYING_CAR.uses shouldBe 40
        CarRules.FUEL shouldBe 40
        // Running someone down hurts less than the rocket's blast, which is less than the Rocket Launcher's.
        (CarRules.RAM_DAMAGE < RocketRules.BLAST_DAMAGE && RocketRules.BLAST_DAMAGE < 90.0 && RocketRules.BLAST_RADIUS < 10.0) shouldBe true
        (RocketRules.CRATER < RocketRules.BLAST_RADIUS) shouldBe true
    }
    "the flying car gathers speed, and a parked one fills its tank again" {
        // From standing to nearly flat out takes a third of a second or so, not one tick and not a second.
        var speed = 0.0
        val ticks = generateSequence { speed = CarRules.eased(speed, CarRules.FORWARD); speed }.takeWhile { it < CarRules.FORWARD * 0.9 }.count() + 1
        (ticks in 5..9) shouldBe true
        // The first tick is a part of the way, never all of it; and it comes to a dead stop, not a crawl for ever.
        (CarRules.eased(0.0, CarRules.FORWARD) in 0.1..0.5) shouldBe true
        var coasting = CarRules.FORWARD
        repeat(40) { coasting = CarRules.eased(coasting, 0.0) }
        coasting shouldBe 0.0
        // Nothing for the first ten seconds it stands; then a second of flying for every four.
        (0..CarRules.REST_TICKS).none(CarRules::refuels) shouldBe true
        (0..1200).filter(CarRules::refuels) shouldBe (1..12).map { CarRules.REST_TICKS + it * CarRules.REFUEL_TICKS }.filter { it <= 1200 }
        CarRules.REST_TICKS shouldBe 200
        CarRules.REFUEL_TICKS shouldBe 80
        listOf(0, 200, 279, 280, 600, 200 + 40 * 80).map(CarRules::refuelled) shouldBe listOf(0, 0, 0, 1, 5, 40)
    }
    "what an unseen fighter carries is itself in the hotbar and nothing in a hand seen from outside" {
        val sword = """{"model": {"type": "minecraft:composite", "models": [{"type": "minecraft:model", "model": "minecraft:item/iron_sword"}, {"type": "minecraft:model", "model": "ssm:item/opponent_selected_overlay"}]}}"""
        val unseen = kotlinx.serialization.json.Json.parseToJsonElement(UnseenGear.unseen(sword)!!) as kotlinx.serialization.json.JsonObject
        val model = unseen["model"] as kotlinx.serialization.json.JsonObject
        model["type"].toString() shouldBe "\"minecraft:select\""
        model["property"].toString() shouldBe "\"minecraft:display_context\""
        // In either hand, or on the head, of a figure seen from outside: nothing.
        val case = (model["cases"] as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
        (case["when"] as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') } shouldBe listOf("thirdperson_righthand", "thirdperson_lefthand", "head")
        case["model"].toString() shouldBe """{"type":"minecraft:empty"}"""
        // Everywhere else (the hotbar, the inventory, the hand in first person): the sword, without the selection overlay.
        model["fallback"].toString() shouldBe """{"type":"minecraft:model","model":"minecraft:item/iron_sword"}"""
        // And putting it on does not dip the hand as changing items would.
        unseen["hand_animation_on_swap"].toString() shouldBe "false"
        UnseenGear.unseen("""{"model":{"type":"minecraft:model","model":"minecraft:item/iron_sword"}}""") shouldBe null
    }
    "a falling anvil is down in half a second, on whoever is not running and jumping" {
        AnvilRules.FALL_TICKS shouldBe 10
        AnvilRules.DAMAGE shouldBe 90.0
        listOf(0, 5, 10, 40).map(AnvilRules::heightAt) shouldBe listOf(AnvilRules.HEIGHT, AnvilRules.HEIGHT / 2, 0.0, 0.0)
        val seconds = AnvilRules.FALL_TICKS / 20.0
        // Standing there, walking (4.3 blocks a second) or running (5.6): under it.
        AnvilRules.lands(0.0, 0.0, 0.0) shouldBe true
        AnvilRules.lands(4.3 * seconds, 0.0, 0.0) shouldBe true
        AnvilRules.lands(5.6 * seconds, 0.0, 0.0) shouldBe true
        // Already running and jumping (7.1): just out from under it. (In its first 0.8 seconds a walk was nearly enough.)
        AnvilRules.lands(0.0, 0.0, 7.1 * seconds) shouldBe false
        AnvilRules.lands(4.3 * 0.8, 0.0, 0.0) shouldBe true
        AnvilRules.lands(5.6 * 0.8, 0.0, 0.0) shouldBe false
        // Jumping on the spot does not help; being a floor below does.
        AnvilRules.lands(1.0, 2.5, 1.0) shouldBe true
        AnvilRules.lands(1.0, -4.0, 1.0) shouldBe false
    }
    "a cage has bars on four sides, two high, and a lid" {
        CageRules.HOLD_TICKS shouldBe 160
        CageRules.BARS.toSet() shouldBe setOf(Triple(1, 0, 0), Triple(1, 1, 0), Triple(-1, 0, 0), Triple(-1, 1, 0),
            Triple(0, 0, 1), Triple(0, 1, 1), Triple(0, 0, -1), Triple(0, 1, -1), Triple(0, 2, 0))
        // Walked over: on it. Beside it, or jumped clean over it: not.
        CageRules.steppedOn(0.0, 0.0, 0.0) shouldBe true
        CageRules.steppedOn(0.7, 0.1, -0.7) shouldBe true
        CageRules.steppedOn(1.3, 0.0, 0.0) shouldBe false
        CageRules.steppedOn(0.0, 1.4, 0.0) shouldBe false
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
        // "Food" was one item then; it is two now, and having had it on is having both on.
        ArenaItem.restoreSelection("food|extra_life") shouldBe setOf(ArenaItem.PORKCHOP, ArenaItem.STEAK, ArenaItem.EXTRA_LIFE)
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
        val picky = (ArenaItem.entries.toSet() - fresh - ArenaItem.foods.toSet()).joinToString("|") { it.id }
        ArenaItem.restoreSelection(picky, 1) shouldBe ArenaItem.entries.toSet() - fresh - ArenaItem.foods.toSet()
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
        listOf(ArenaItem.STEAK, ArenaItem.PORKCHOP, ArenaItem.GILDED_APPLE, ArenaItem.AWESOME_SOUP).map { it.dropFeel } shouldBe
            listOf(DropFeel.FOOD, DropFeel.FOOD, DropFeel.FOOD, DropFeel.SPILL)
        listOf(ArenaItem.CAGE_TRAP, ArenaItem.PERSONAL_ROCKET, ArenaItem.ANVIL, ArenaItem.FLYING_CAR).map { it.dropFeel } shouldBe
            listOf(DropFeel.WOOD, DropFeel.FUSE, DropFeel.METAL, DropFeel.METAL)
        ArenaItem.entries.filter { it.dropFeel == DropFeel.TOOL }.all { Regex(".*_(AXE|SHOVEL|SWORD|PICKAXE|HOE)|BOW").matches(it.material.name) } shouldBe true
        listOf(ArenaItem.CLOUD_IN_A_BOTTLE, ArenaItem.POCKET_TRAMPOLINE, ArenaItem.MYSTERY_ABILITY, ArenaItem.FRAG_GRENADE).map { it.dropFeel } shouldBe
            listOf(DropFeel.GLASS, DropFeel.SLIME, DropFeel.BAG, DropFeel.FUSE)
        // A Mystery Ability's tool is not in the catalogue: a copper tool is a tool, a pumpkin is not.
        listOf("COPPER_AXE", "COPPER_SWORD", "BOW", "CARVED_PUMPKIN", "FEATHER").map(DropFeel::of) shouldBe
            listOf(DropFeel.TOOL, DropFeel.TOOL, DropFeel.TOOL, DropFeel.PLAIN, DropFeel.PLAIN)
    }
    "a shotbow not drawn all the way does not shoot like one that is" {
        // The draw of an ordinary bow: nothing for the first ticks, full after a second, and no more than full.
        (ShotbowRules.force(2) < ShotbowRules.LEAST) shouldBe true
        (ShotbowRules.force(3) >= ShotbowRules.LEAST) shouldBe true
        ShotbowRules.force(10) shouldBe (0.4167 plusOrMinus .001)
        listOf(20, 200).map(ShotbowRules::force) shouldBe listOf(1.0, 1.0)
        // Speed and damage follow the draw from nothing: a quick tap is a tenth of a full shot, not 40% of one.
        ShotbowRules.speed(1.0) shouldBe 3.0
        ShotbowRules.speed(ShotbowRules.force(3)) shouldBe (0.32 plusOrMinus .01)
        ShotbowRules.damage(1.0) shouldBe 6.25
        ShotbowRules.damage(ShotbowRules.force(10)) shouldBe (2.6 plusOrMinus .01)
        // ...and the arrows fall: a tap drops them at the shooter's feet, half a draw goes a few blocks, a full one carries.
        val reach = listOf(3, 10, 20).map { ShotbowRules.reach(ShotbowRules.force(it)) }
        (reach[0] < 4.0) shouldBe true
        (reach[1] in 8.0..13.0) shouldBe true
        (reach[2] > 20.0) shouldBe true
        listOf(0.5, 0.99, 1.0).map(ShotbowRules::trail) shouldBe listOf(false, false, true)
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
