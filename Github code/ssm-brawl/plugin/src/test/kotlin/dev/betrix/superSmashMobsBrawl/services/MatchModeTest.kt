package dev.betrix.superSmashMobsBrawl.services

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MatchModeTest : StringSpec({
    "each mode is called what the settings menu calls it" {
        MatchMode.FFA.label(4) shouldBe "FFA"
        MatchMode.TEAM_UPS.label(8) shouldBe "Team-Ups"
        MatchMode.BOTS_VS_YOU.label(4) shouldBe "3v1"
        MatchMode.BOTS_VS_YOU.label(16) shouldBe "15v1"
    }

    "two teams are 2v2, 4v4 and 8v8" {
        MatchMode.TEAMS.label(4) shouldBe "2v2"
        MatchMode.TEAMS.label(8) shouldBe "4v4"
        MatchMode.TEAMS.label(16) shouldBe "8v8"
    }

    "two players are a duel and one alone is the sandbox, whatever the mode" {
        for (mode in MatchMode.entries) {
            mode.label(2) shouldBe "1v1"
            mode.label(1) shouldBe "Sandbox"
        }
    }

    "a setup knows its seats: who is a teammate, who an opponent" {
        val setup = GameSetupService.Setup(players = 8, teams = true, teammates = listOf("Ana", "Bo", "Cy", "Di"),
            selectedBotNames = listOf("ana", "Ed", "Flo", "Gus", "Hal", "Ivy"))
        setup.matchMode shouldBe MatchMode.TEAMS
        setup.teamGame shouldBe true
        setup.modeLabel shouldBe "4v4"
        setup.teammateSlots shouldBe 3
        setup.opponentSlots shouldBe 4
        // Three seats on the team; Ana is on it, so she is not an opponent as well.
        setup.effectiveTeammates shouldBe listOf("Ana", "Bo", "Cy")
        setup.effectiveOpponents shouldBe listOf("Ed", "Flo", "Gus", "Hal")
        setup.wantedBotNames shouldBe listOf("Ana", "Bo", "Cy", "Ed", "Flo", "Gus", "Hal")
    }

    "outside two teams nobody is a teammate and every bot is an opponent" {
        val setup = GameSetupService.Setup(players = 4, teammates = listOf("Ana"), selectedBotNames = listOf("Ana", "Bo", "Cy", "Di"))
        setup.matchMode shouldBe MatchMode.FFA
        setup.teamGame shouldBe false
        setup.teammateSlots shouldBe 0
        setup.wantedBotNames shouldBe listOf("Ana", "Bo", "Cy")
        // Two teams take four: with two players the same setting is a duel.
        val duel = GameSetupService.Setup(players = 2, teams = true)
        duel.teamGame shouldBe false
        duel.modeLabel shouldBe "1v1"
        GameSetupService.Setup(players = 1).sandbox shouldBe true
    }

    "choosing a mode clears the others" {
        val setup = GameSetupService.Setup(teamUps = true)
        setup.matchMode = MatchMode.TEAMS
        setup.teams shouldBe true
        setup.teamUps shouldBe false
        setup.matchMode = MatchMode.BOTS_VS_YOU
        setup.teams shouldBe false
        setup.allBotsVsPlayer shouldBe true
    }
})
