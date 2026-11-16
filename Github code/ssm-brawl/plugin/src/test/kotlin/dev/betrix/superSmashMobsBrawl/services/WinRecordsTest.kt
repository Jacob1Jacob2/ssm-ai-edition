package dev.betrix.superSmashMobsBrawl.services

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.LocalDate

class WinRecordsTest : StringSpec({
    val monday = LocalDate.of(2026, 11, 9)
    val player = WinIdentity("player-id", "Jacob1Jacob2", false, "god")
    val bot = WinIdentity("bot-id", "Animadoria", true, "mythic")

    "player and bot wins survive reload and the same match cannot count twice" {
        val file = Files.createTempDirectory("ssm-wins").resolve("wins.json")
        val records = WinRecords(file)
        records.record("match-1", listOf(player, player), monday) shouldBe true
        records.record("match-2", listOf(bot), monday) shouldBe true
        records.record("match-3", listOf(bot), monday) shouldBe true
        val reloaded = WinRecords(file)
        reloaded.record("match-3", listOf(bot), monday.plusDays(1)) shouldBe false
        reloaded.standings(WinPeriod.ALL_TIME, monday) shouldBe listOf(RankedWins(bot, 2), RankedWins(player, 1))
    }

    "daily and Monday-based weekly rankings roll over without deleting all-time wins" {
        val records = WinRecords(Files.createTempDirectory("ssm-periods").resolve("wins.json"))
        records.record("sunday", listOf(bot), monday.minusDays(1))
        records.record("monday", listOf(bot), monday)
        records.record("tuesday", listOf(player), monday.plusDays(1))
        records.standings(WinPeriod.DAILY, monday.plusDays(1)) shouldBe listOf(RankedWins(player, 1))
        records.standings(WinPeriod.WEEKLY, monday.plusDays(1)) shouldBe listOf(RankedWins(bot, 1), RankedWins(player, 1))
        records.standings(WinPeriod.WEEKLY, monday.plusDays(7)) shouldBe emptyList()
        records.standings(WinPeriod.ALL_TIME, monday.plusDays(7)) shouldBe listOf(RankedWins(bot, 2), RankedWins(player, 1))
    }

    "team winners count once each, identities update, and empty results do not count" {
        val records = WinRecords(Files.createTempDirectory("ssm-teams").resolve("wins.json"))
        records.record("abandoned", emptyList(), monday) shouldBe false
        records.record("team", listOf(player, bot, bot), monday) shouldBe true
        val renamed = bot.copy(name = "RenamedBot", rank = "divine")
        records.record("later", listOf(renamed), monday) shouldBe true
        records.standings(WinPeriod.ALL_TIME, monday) shouldBe listOf(RankedWins(renamed, 2), RankedWins(player, 1))
    }
})
