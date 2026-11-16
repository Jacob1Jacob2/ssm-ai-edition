package dev.betrix.superSmashMobsBrawl.kits

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KitStatsTest : StringSpec({
    // The squid: 0.25 regen, 5 armor, 6 damage, cooldowns of 6, 8 and 16 seconds.
    val squid = KitBase(0.25, 5.0, 6.0, mapOf("ink_shotgun" to 6.0, "super_squid" to 8.0, "fish_flurry" to 16.0))
    fun row(units: Double, squares: IntRange) = squares.map { StatBar.fill(units, it) }

    "the squid's stat rows look like the menu: icon plus filled, half and empty squares" {
        // Regen 0.25 at 0.10 a square, the icon being the first: icon, one full, one half, six empty.
        row(StatBar.units(0.25, 0.1), 2..9) shouldBe listOf(2, 1, 0, 0, 0, 0, 0, 0)
        // Armor 5: the icon and four more.
        row(StatBar.units(5.0, 1.0), 2..9) shouldBe listOf(2, 2, 2, 2, 0, 0, 0, 0)
        // Damage 6: the icon and five more.
        row(StatBar.units(6.0, 1.0), 2..9) shouldBe listOf(2, 2, 2, 2, 2, 0, 0, 0)
    }
    "cooldown rows are 2 seconds a square and the icon does not count" {
        row(StatBar.units(6.0, 2.0), 1..8).count { it == 2 } shouldBe 3
        row(StatBar.units(8.0, 2.0), 1..8).count { it == 2 } shouldBe 4
        row(StatBar.units(16.0, 2.0), 1..8) shouldBe List(8) { 2 }
        row(StatBar.units(40.0, 2.0), 1..8) shouldBe List(8) { 2 }
        row(StatBar.units(0.0, 2.0), 1..8) shouldBe List(8) { 0 }
        row(StatBar.units(5.0, 2.0), 1..8) shouldBe listOf(2, 2, 1, 0, 0, 0, 0, 0)
    }
    "clicking a square fills up to it, and clicking it again takes half back" {
        StatBar.click(2.5, 4) shouldBe 4.0
        StatBar.click(4.0, 4) shouldBe 3.5
        StatBar.click(3.5, 4) shouldBe 4.0
        StatBar.click(20.0, 8) shouldBe 8.0
    }
    "any stat better than normal is Buffed, only-worse is Nerfed, and normal values are not changes" {
        KitStats().verdict(squid) shouldBe KitVerdict.NORMAL
        KitStats(regen = 0.25, armor = 5.0, cooldowns = mapOf("ink_shotgun" to 6.0)).verdict(squid) shouldBe KitVerdict.NORMAL
        KitStats(regen = 0.25, armor = 5.0).trimmed(squid).isEmpty shouldBe true
        KitStats(armor = 6.0).verdict(squid) shouldBe KitVerdict.BUFFED
        KitStats(regen = 0.3).verdict(squid) shouldBe KitVerdict.BUFFED
        KitStats(cooldowns = mapOf("fish_flurry" to 4.0)).verdict(squid) shouldBe KitVerdict.BUFFED
        KitStats(damage = 3.0).verdict(squid) shouldBe KitVerdict.NERFED
        KitStats(cooldowns = mapOf("ink_shotgun" to 10.0)).verdict(squid) shouldBe KitVerdict.NERFED
        // Giving up armor for damage is still an advantage somewhere.
        KitStats(armor = 1.0, damage = 9.0).verdict(squid) shouldBe KitVerdict.BUFFED
        KitStats(armor = 1.0, regen = 0.1, cooldowns = mapOf("super_squid" to 6.0)).verdict(squid) shouldBe KitVerdict.BUFFED
        // A cooldown saved for an ability the kit no longer has is ignored.
        KitStats(cooldowns = mapOf("gone" to 1.0)).verdict(squid) shouldBe KitVerdict.NORMAL
    }
    "only the tool of the changed ability changes its look" {
        // A shorter Ink Shotgun: that tool is Buffed, the other two stay as they are.
        val quick = KitStats(cooldowns = mapOf("ink_shotgun" to 2.0))
        quick.toolVerdict(squid, "ink_shotgun", first = true) shouldBe KitVerdict.BUFFED
        quick.toolVerdict(squid, "super_squid", first = false) shouldBe KitVerdict.NORMAL
        quick.toolVerdict(squid, "fish_flurry", first = false) shouldBe KitVerdict.NORMAL
        // One shorter and one longer: wood on the one, gold on the other.
        val mixed = KitStats(cooldowns = mapOf("super_squid" to 4.0, "fish_flurry" to 20.0))
        mixed.toolVerdict(squid, "ink_shotgun", first = true) shouldBe KitVerdict.NORMAL
        mixed.toolVerdict(squid, "super_squid", first = false) shouldBe KitVerdict.BUFFED
        mixed.toolVerdict(squid, "fish_flurry", first = false) shouldBe KitVerdict.NERFED
        // Armor, damage and regen belong to the whole kit and show on its first tool only.
        val tanky = KitStats(armor = 8.0)
        tanky.toolVerdict(squid, "ink_shotgun", first = true) shouldBe KitVerdict.BUFFED
        tanky.toolVerdict(squid, "super_squid", first = false) shouldBe KitVerdict.NORMAL
        KitStats(damage = 2.0).toolVerdict(squid, "ink_shotgun", first = true) shouldBe KitVerdict.NERFED
        // A value that only restates the normal one changes nothing.
        KitStats(armor = 5.0, cooldowns = mapOf("ink_shotgun" to 6.0)).toolVerdict(squid, "ink_shotgun", first = true) shouldBe KitVerdict.NORMAL
    }
    "every kit in the kit menu has a small icon for an opponent's head" {
        (dev.betrix.superSmashMobsBrawl.services.KitMenuLayout.slots.keys - dev.betrix.superSmashMobsBrawl.services.MenuIcons.cornerIcons) shouldBe emptySet()
    }
    "changes survive being saved and loaded, and junk in the file is dropped" {
        val mine = KitStats(0.3, 4.0, null, mapOf("ink_shotgun" to 0.0, "fish_flurry" to 9.0))
        KitStats.decode(mine.encode()) shouldBe mine
        KitStats.decode("") shouldBe KitStats()
        KitStats.decode("armor=abc;damage=-4;regen=NaN;cd.x=2;what") shouldBe KitStats(cooldowns = mapOf("x" to 2.0))
    }
})