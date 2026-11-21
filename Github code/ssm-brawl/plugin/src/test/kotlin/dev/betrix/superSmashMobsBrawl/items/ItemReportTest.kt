package dev.betrix.superSmashMobsBrawl.items

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** The item balance report: what is recorded of a run of matches, and how it is added up. */
class ItemReportTest : StringSpec({
    val frag = ArenaItem.FRAG_GRENADE
    val steak = ArenaItem.STEAK
    val rod = ArenaItem.KNOCKBACK_ROD
    fun line(match: String, tick: Long, event: String, item: ArenaItem? = null, actor: String = "", target: String = "", value: Double = 0.0, extra: String = "") =
        ItemStats.line(match, tick, event, item?.id ?: "", actor, target, value, extra)

    /** Three fighters. Ana kills Bo with a grenade and wins; Cy eats a steak; Bo throws a grenade away unused. */
    fun match(id: String, ending: String = "natural|Ana") = listOf(
        line(id, 1, "start", value = 3.0, extra = "aviation|normal|expert|equal|3s|4 lives"),
        line(id, 1, "player", actor = "Ana", extra = "chicken"),
        line(id, 1, "player", actor = "Bo", extra = "pig"),
        line(id, 1, "player", actor = "Cy", extra = "cow"),
        line(id, 5, "drop", frag), line(id, 6, "drop", frag), line(id, 7, "drop", steak), line(id, 8, "drop", steak),
        line(id, 10, "pickup", frag, "Ana"),
        line(id, 11, "use", frag, "Ana", value = 1.0),
        line(id, 12, "damage", frag, "Ana", "Bo", 20.0, "Frag Grenade"),
        line(id, 12, "damage", frag, "Ana", "Ana", 5.0, "Frag Grenade"),
        line(id, 13, "death", frag, "Ana", "Bo", extra = "Frag Grenade"),
        line(id, 20, "pickup", steak, "Cy"),
        line(id, 21, "heal", steak, "Cy", value = 12.0),
        line(id, 21, "use", steak, "Cy", value = 1.0),
        line(id, 30, "pickup", frag, "Bo"),
        line(id, 40, "discard", frag, "Bo", extra = "no use found"),
        // A hit with a kit's own ability, and a fall that was nobody's doing.
        line(id, 45, "damage", null, "Cy", "Ana", 30.0, "Milk Spiral"),
        line(id, 50, "death", null, "", "Cy", extra = "Void"),
        line(id, 60, "end", value = 90.0, extra = ending),
    )
    fun built(vararg lines: List<String>) = ItemReport.build(ItemReport.parse((listOf(ItemStats.HEADER) + lines.toList().flatten()).asSequence()))

    "a line that is written is read back as it was, commas in a name or not" {
        val row = ItemReport.parse(sequenceOf(ItemStats.HEADER, ItemStats.line("AB12", 7, "damage", "frag_grenade", "A,na", "Bo", 12.5, "Frag Grenade"))).single()
        row shouldBe ItemReport.Row("AB12", 7, "damage", "frag_grenade", "A;na", "Bo", 12.5, "Frag Grenade")
        ItemStats.line("m", 1, "use", "steak", value = 2.0) shouldBe "m,1,use,steak,,,2,"
        ItemStats.itemOf("Falling Anvil") shouldBe ArenaItem.ANVIL
        ItemStats.itemOf("Milk Spiral") shouldBe null
        ItemStats.itemOf(null) shouldBe null
    }

    "one match is added up item by item" {
        val report = built(match("m1"))
        val grenade = report.line(frag)
        grenade.drops shouldBe 2
        grenade.pickups shouldBe 2
        grenade.uses shouldBe 1.0
        grenade.damage shouldBe 20.0
        grenade.selfDamage shouldBe 5.0
        grenade.kills shouldBe 1
        grenade.discards shouldBe 1
        // Ana's pickup hurt somebody; Bo's did nothing.
        grenade.hitPickups shouldBe 1
        grenade.hitShare shouldBe 0.5
        grenade.damageEach shouldBe 10.0
        grenade.killsEach shouldBe 0.5
        grenade.discardShare shouldBe 0.5
        val food = report.line(steak)
        food.pickups shouldBe 1
        food.healEach shouldBe 12.0
        food.damage shouldBe 0.0
        food.pickedShare shouldBe 0.5
        // The whole match: two deaths, one by an item and one nobody's; the ability's damage is not an item's.
        report.totals.matches shouldBe 1
        report.totals.fighters shouldBe 3
        report.totals.deaths shouldBe 2
        report.totals.itemKills shouldBe 1
        report.totals.unclaimed shouldBe 1
        report.totals.damage shouldBe 50.0
        report.totals.itemDamage shouldBe 20.0
        report.otherDamage shouldBe mapOf("Milk Spiral" to 30.0)
    }

    "who held what, and who won: the holder of a grenade against the holder of anything" {
        val report = built(match("m1"), match("m2", "natural|Cy"))
        // Two matches: grenades were held by Ana and Bo in each (4 holders), and won by Ana once; steaks by Cy (2), who won once.
        report.line(frag).holders shouldBe 4
        report.line(frag).holderWins shouldBe 1
        report.line(steak).holders shouldBe 2
        report.line(steak).holderWins shouldBe 1
        report.totals.baseline!! shouldBe (2.0 / 6 plusOrMinus 1e-9)
        val (points, margin) = ItemReport.lift(report.line(steak), report.totals)!!
        points shouldBe ((0.5 - 2.0 / 6) * 100 plusOrMinus 1e-9)
        margin shouldBe (kotlin.math.sqrt(0.25 / 2) * 100 plusOrMinus 1e-9)
        ItemReport.lift(report.line(ArenaItem.ANVIL), report.totals) shouldBe null
    }

    "a match stopped for going on too long counts for what its items did, not for who won; one cut off is left out" {
        val report = built(match("m1"), match("m2", "balance_timeout"), match("m3").dropLast(1))
        report.totals.matches shouldBe 2
        report.totals.timedOut shouldBe 1
        report.totals.decided shouldBe 1
        report.line(frag).pickups shouldBe 4
        report.line(frag).kills shouldBe 2
        // Only the match that was won says anything about winning.
        report.line(frag).holders shouldBe 2
        report.line(frag).holderWins shouldBe 1
    }

    "what an item does is put down to the latest pickup of it, and a launch counts as a hit" {
        val report = built(listOf(
            line("m", 1, "start"), line("m", 1, "player", actor = "Ana"), line("m", 1, "player", actor = "Bo"),
            line("m", 10, "pickup", rod, "Ana"),
            line("m", 20, "pickup", rod, "Ana"),
            // No damage, but it threw Bo: the second rod did something, the first did not.
            line("m", 25, "knock", rod, "Ana", "Bo", extra = "Knockback Rod"),
            // Something by a fighter who never picked the item up is still the item's, but nobody's pickup.
            line("m", 30, "damage", frag, "Bo", "Ana", 9.0, "Frag Grenade"),
            line("m", 40, "end", value = 30.0, extra = "natural|Ana")))
        report.line(rod).pickups shouldBe 2
        report.line(rod).hitPickups shouldBe 1
        report.line(frag).damage shouldBe 9.0
        report.line(frag).pickups shouldBe 0
        report.line(frag).damageEach shouldBe null
    }

    "an item far above its tier, or under the tier below, is named" {
        fun many(item: ArenaItem, damage: Double) = (1..ItemReport.ENOUGH).flatMap { n ->
            listOf(line("m", n * 10L, "pickup", item, "Ana"), line("m", n * 10L + 1, "damage", item, "Ana", "Bo", damage, item.label))
        }
        // Three commons at 10, 10 and 40; and a rare that does less than a common usually does.
        val rows = listOf(line("m", 1, "start"), line("m", 1, "player", actor = "Ana"), line("m", 1, "player", actor = "Bo")) +
            many(ArenaItem.FRAG_GRENADE, 10.0) + many(ArenaItem.HOMING_SNOWBALL, 10.0) + many(ArenaItem.INSTANT_TELEPORTER, 40.0) +
            many(ArenaItem.ICE_CUBE, 12.0) + many(ArenaItem.CLUSTER_GRENADE, 12.0) + many(ArenaItem.POISON_MIST, 12.0) +
            many(ArenaItem.SHOTBOW, 5.0) + listOf(line("m", 9000, "end", value = 60.0, extra = "natural|Ana"))
        val out = ItemReport.standouts(built(rows))
        out.size shouldBe 2
        out[0] shouldContain "Instant Teleporter (Common): 40.0% a pickup, where 10.0% is usual"
        out[1] shouldContain "The Shotbow (Rare): 5.0% a pickup, less than the 12.0% usual for the tier below"
    }

    "the page has a table for every rarity and the figures in it" {
        val page = ItemReport.markdown("A test run", built(match("m1"), match("m2", "natural|Cy")))
        page shouldContain "# A test run"
        page shouldContain "2 matches (2 with a winner, 0 stopped for going on too long), 3.0 fighters each, 1:30 on average."
        page shouldContain "8 items dropped, 6 were picked up (75%)."
        page shouldContain "4 deaths: 2 by an item (50%), 0 by a kit, 2 that were nobody's kill."
        for (tier in ItemRarity.entries) page shouldContain "## ${tier.label}"
        // Frag Grenade: 4 dropped, 4 picked up, half a use each, half hit someone, 10% damage, 50 kills per 100, half of all deaths.
        page shouldContain "| Frag Grenade | 4 | 4 | 0.5 | 50% | 10.0 | 50.0 | 50% | - | 2.5 | 50% |"
        page shouldContain "| Steak | 4 | 2 | 1.0 | 0% | - | - | 0% | 12.0 | - | 0% |"
        page shouldContain "Milk Spiral: 60% of all damage"
        val table = ItemReport.csv(built(match("m1")))
        table.lines().first() shouldContain "item,rarity,drops,pickups"
        table.lines().count { it.isNotBlank() } shouldBe ArenaItem.entries.size + 1
        table shouldContain "frag_grenade,Common,2,2,1.0000,0.5000,0.5000,1,10.0000,2.5000,1,50.0000,0.5000"
    }
})
