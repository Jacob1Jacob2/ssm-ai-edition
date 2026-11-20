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

    "a mode's button is dark when the match has nobody, or too few, to play it with" {
        // The sandbox: nobody to fight, so none of the four.
        MatchMode.entries.filter { it.available(1) } shouldBe emptyList()
        MatchMode.entries.map { it.unavailable(1) }.toSet() shouldBe setOf("The sandbox has nobody to fight")
        // Two players are a duel: only FFA is back.
        MatchMode.entries.filter { it.available(2) } shouldBe listOf(MatchMode.FFA)
        MatchMode.TEAM_UPS.unavailable(2) shouldBe "Takes 3 or more players"
        MatchMode.BOTS_VS_YOU.unavailable(2) shouldBe "Takes 3 or more players"
        MatchMode.TEAMS.unavailable(2) shouldBe "Takes 4 or more players"
        // From four players on, and on Random (where the number is rolled for every match), all of them.
        for (players in GameSetupService.PLAYER_COUNTS.filter { it >= 4 }) MatchMode.entries.filter { it.available(players) } shouldBe MatchMode.entries
        for (players in listOf(1, 2, 16)) MatchMode.entries.filter { it.available(players, random = true) } shouldBe MatchMode.entries
        // Every icon that can go dark has its dark copy in the pack: the four modes, the difficulty row, the personality column.
        MenuAssets.DARK_FLAT_ICONS.toSet() shouldBe setOf("copper_axe", "iron_axe", "copper_sword", "iron_sword",
            "barrier", "lime_dye", "yellow_dye", "orange_dye", "red_dye", "magenta_dye",
            "minecart", "furnace_minecart", "chest_minecart", "tnt_minecart", "command_block_minecart")
        MenuAssets.DARK_FLAT_ICONS.size shouldBe MenuAssets.DARK_FLAT_ICONS.toSet().size
    }

    "every dark icon is in the pack, as something the game can read" {
        val files = mutableMapOf<String, ByteArray>()
        MenuAssets.addTo(files)
        // The flat ones, and the three that are drawn as blocks: both chests and the head.
        for (icon in MenuAssets.DARK_FLAT_ICONS + listOf("chest", "copper_chest", "player_head")) {
            val definition = files["assets/ssm/items/dark_$icon.json"]?.toString(Charsets.UTF_8)
            (definition != null) shouldBe true
            val model = (kotlinx.serialization.json.Json.parseToJsonElement(definition!!) as kotlinx.serialization.json.JsonObject)["model"] as kotlinx.serialization.json.JsonObject
            (model["type"].toString().trim('"') in setOf("minecraft:model", "minecraft:composite")) shouldBe true
        }
        // The dark wooden chest is the game's own chest under a veil made on the chest's own model.
        val chest = files.getValue("assets/ssm/items/dark_chest.json").toString(Charsets.UTF_8)
        (chest.contains("\"texture\":\"minecraft:normal\"") && chest.contains("ssm:item/dark_plain_chest_veil")) shouldBe true
        val veil = kotlinx.serialization.json.Json.parseToJsonElement(files.getValue("assets/ssm/models/item/dark_plain_chest_veil.json").toString(Charsets.UTF_8)) as kotlinx.serialization.json.JsonObject
        veil["parent"].toString() shouldBe "\"minecraft:item/chest\""
    }

    "the bots' difficulty, their chat and who they are is only there to set when the match has bots" {
        // The sandbox has none; any real match has, and so has Random players, which never rolls the sandbox.
        GameSetupService.Setup(players = 1).hasBots shouldBe false
        for (players in GameSetupService.PLAYER_COUNTS) GameSetupService.Setup(players = players).hasBots shouldBe true
        GameSetupService.Setup(players = 1, playersRandom = true).hasBots shouldBe true
        // Going to the sandbox and back leaves the difficulty that was picked as it was.
        val setup = GameSetupService.Setup(players = 8, difficulty = dev.betrix.superSmashMobsBrawl.bots.BotDifficulty.EXPERT)
        setup.players = 1
        setup.hasBots shouldBe false
        setup.players = 8
        setup.difficulty shouldBe dev.betrix.superSmashMobsBrawl.bots.BotDifficulty.EXPERT
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
