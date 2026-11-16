package dev.betrix.superSmashMobsBrawl.services

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.LocalDate

class KillRecordsTest : StringSpec({
    "legacy wins migrate without losing ranks or totals, and kills survive a restart" {
        val file = Files.createTempDirectory("ssm-kills").resolve("wins.json")
        Files.writeString(file, """{"version":1,"players":{"one":{"identity":{"id":"one","name":"First","bot":false,"rank":"god"},"days":{"2026-11-11":3}}},"matches":["old"]}""")
        val records = WinRecords(file)
        val identity = WinIdentity("one", "Renamed", false, "god")
        records.recordKill(identity); records.recordKill(identity)
        records.record("new", listOf(identity), LocalDate.of(2026,11,11)) shouldBe true
        val reloaded = WinRecords(file)
        reloaded.kills() shouldBe listOf(RankedWins(identity, 2))
        reloaded.standings(WinPeriod.ALL_TIME, LocalDate.of(2026,11,11)) shouldBe listOf(RankedWins(identity, 4))
        reloaded.record("old", listOf(identity), LocalDate.of(2026,11,11)) shouldBe false
    }
})
