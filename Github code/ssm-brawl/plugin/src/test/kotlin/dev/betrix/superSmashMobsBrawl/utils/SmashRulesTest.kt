package dev.betrix.superSmashMobsBrawl.utils

import dev.betrix.superSmashMobsBrawl.smash.*
import dev.betrix.superSmashMobsBrawl.bots.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import net.kyori.adventure.text.format.NamedTextColor

class SmashRulesTest:StringSpec({
    "crystal fireworks have exact per-second counts and stop at landing" {
        SmashTier.entries.map {it.descentSeconds} shouldBe listOf(8,10,14,20)
        SmashTier.entries.map {it.fireworksPerSecond} shouldBe listOf(3,6,9,12)
        SmashTier.entries.forEach { tier ->
            (1..tier.landingTicks).chunked(20).forEach { second ->
                second.sumOf {tier.fireworksAt(it)} shouldBe tier.fireworksPerSecond
            }
            tier.fireworksAt(0) shouldBe 0
            tier.fireworksAt(tier.landingTicks+1) shouldBe 0
            val emissions=(1..tier.landingTicks).filter {tier.fireworksAt(it)>0}
            (emissions.zipWithNext().maxOf {it.second-it.first}-emissions.zipWithNext().minOf {it.second-it.first}<=1) shouldBe true
        }
    }
    "crystals descend at constant speed and stop exactly at the pad" {
        SmashTier.entries.forEach {tier ->
            for(height in listOf(2.0,17.0,35.0)) {
                tier.descentHeight(height,0) shouldBe height
                (kotlin.math.abs(tier.descentHeight(height,tier.landingTicks)-.35)<1e-10) shouldBe true
                tier.descentHeight(height,tier.landingTicks+40) shouldBe tier.descentHeight(height,tier.landingTicks)
                val delta=(height-.35)/tier.landingTicks
                for(tick in 1..tier.landingTicks)
                    (kotlin.math.abs(tier.descentHeight(height,tick-1)-tier.descentHeight(height,tick)-delta)<1e-10) shouldBe true
            }
        }
    }
    "weighted roll has exactly the requested hundred outcomes" {
        (0..99).map {SmashTier.fromRoll(it)}.groupingBy {it}.eachCount() shouldBe mapOf(
            SmashTier.LIGHT to 40,SmashTier.REGULAR to 30,SmashTier.SUPER to 20,SmashTier.ULTRA to 10)
        shouldThrow<IllegalArgumentException> {SmashTier.fromRoll(-1)}
        shouldThrow<IllegalArgumentException> {SmashTier.fromRoll(100)}
    }
    "every classic kit has four distinct powers" {
        SmashCatalog.specs.size shouldBe 100
        SmashCatalog.specs.map {it.key}.distinct().size shouldBe 100
        SmashCatalog.specs.groupBy {it.kit}.size shouldBe 25
        SmashCatalog.specs.groupBy {it.kit}.values.forEach {powers->powers.map {it.tier}.toSet() shouldBe SmashTier.entries.toSet()}
    }
    "fuel remains until it is spent and uses expire only at zero" {
        val power=SmashPower(SmashCatalog.get("slime",SmashTier.LIGHT),0)
        power.expired(100000) shouldBe false
        power.fuel=1;power.expired(100000) shouldBe false
        power.fuel=0;power.expired(100000) shouldBe true
        val missile=SmashPower(SmashCatalog.get("chicken",SmashTier.ULTRA),0)
        missile.uses=1;missile.expired(100000) shouldBe false
        missile.uses=0;missile.expired(100000) shouldBe true
    }
    "temporary forms end on their precise duration boundary" {
        val power=SmashPower(SmashCatalog.get("skeleton",SmashTier.REGULAR),12)
        power.expired(211) shouldBe false
        power.expired(212) shouldBe true
        val red=SmashPower(SmashCatalog.get("skeleton",SmashTier.ULTRA),12)
        red.expired(471) shouldBe false
        red.expired(472) shouldBe true
        val dome=SmashPower(SmashCatalog.get("spider",SmashTier.ULTRA),12)
        dome.expired(611) shouldBe false
        dome.expired(612) shouldBe true
    }
    "life long goat auras do not expire and spider fuel doubles" {
        listOf(SmashTier.SUPER,SmashTier.ULTRA).forEach {
            SmashPower(SmashCatalog.get("goat",it),0).expired(1000000) shouldBe false
        }
        SmashPower(SmashCatalog.get("spider",SmashTier.SUPER),0).fuel shouldBe 400
        SmashPower(SmashCatalog.get("goat",SmashTier.LIGHT),0).uses shouldBe 125
        SmashPower(SmashCatalog.get("spider",SmashTier.REGULAR),0).uses shouldBe 12
    }
    "difficulty levels never fall below normal and stay in their requested bands" {
        BotIdentity.levels(BotDifficulty.NORMAL) shouldBe 40..55
        BotIdentity.levels(BotDifficulty.HARD) shouldBe 55..70
        BotIdentity.levels(BotDifficulty.HARDER) shouldBe 70..85
        BotIdentity.levels(BotDifficulty.EXPERT) shouldBe 85..100
        for(d in BotDifficulty.entries)repeat(100){(BotIdentity.rollLevel(d) in BotIdentity.levels(d)) shouldBe true}
    }
    "level colors resolve the overlap at sixty and rank colors are cosmetic" {
        listOf(40,49).forEach {BotIdentity.levelColor(it) shouldBe NamedTextColor.BLUE}
        listOf(50,59).forEach {BotIdentity.levelColor(it) shouldBe NamedTextColor.GREEN}
        listOf(60,69).forEach {BotIdentity.levelColor(it) shouldBe NamedTextColor.YELLOW}
        listOf(70,79).forEach {BotIdentity.levelColor(it) shouldBe NamedTextColor.GOLD}
        listOf(80,89).forEach {BotIdentity.levelColor(it) shouldBe NamedTextColor.RED}
        listOf(90,100).forEach {BotIdentity.levelColor(it) shouldBe NamedTextColor.DARK_RED}
        BotIdentity.rankColor("elite") shouldBe NamedTextColor.AQUA
        BotIdentity.rankColor("mythic") shouldBe NamedTextColor.GREEN
        BotIdentity.rankColor("mythical") shouldBe NamedTextColor.GREEN
        BotIdentity.rankColor("celestial") shouldBe NamedTextColor.RED
        BotIdentity.rankColor("divine") shouldBe NamedTextColor.LIGHT_PURPLE
    }
})
