package dev.betrix.superSmashMobsBrawl.bots

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.UUID
import kotlin.random.Random

/** What the bots say about teaming up on a player (the Team-Ups match mode). */
class TeamUpsTalkTest : StringSpec({
    val human = BotDialogue.Fighter(UUID(0, 1), "Jacob", "Chicken", 4, false)
    val dino = BotDialogue.Fighter(UUID(0, 100), "Dino", "Guardian", 4, true)
    val fox = BotDialogue.Fighter(UUID(0, 101), "Fox", "Creeper", 4, true)
    val moss = BotDialogue.Fighter(UUID(0, 102), "Moss", "Wolf", 4, true)
    val everyone = listOf(human, dino, fox, moss)
    fun engine(mode: BotPersonality, seed: Int = 2, memory: LineMemory = LineMemory()) = BotDialogue(Random(seed), memory, 1.0).also { it.mode = mode }
    fun pact(members: Set<UUID> = setOf(dino.id, fox.id), reason: TeamUps.Reason = TeamUps.Reason.CAMPING) =
        TeamUps.Pact(human.id, dino.id, members, reason, 0, TeamUps.PACT_MS)
    /** Everything the lobby says in the next [seconds]. */
    fun BotDialogue.listen(from: Long, seconds: Int = 25): List<BotDialogue.Reply> =
        (1..seconds * 4).mapNotNull { poll(from + it * 250L, everyone) { x, y -> x != y } }
    fun List<BotDialogue.Reply>.by(f: BotDialogue.Fighter) = filter { it.speaker == f.id }.joinToString(" ") { it.text }
    infix fun String.shouldSay(pattern: String) { (Regex(pattern).containsMatchIn(this)) shouldBe true }
    /** A real pact on the player, with the three bots as given. */
    fun teamed(heat: Double, vararg personalities: BotPersonality): TeamUps {
        val ups = TeamUps(Random(1)).also { it.enabled = true }
        val roster = listOf(TeamUps.Fighter(human.id, 4, false, true, BotPersonality.SILENT)) +
            listOf(dino, fox, moss).mapIndexed { i, f -> TeamUps.Fighter(f.id, 4, true, true, personalities[i]) }
        ups.poll(0, roster)
        ups.provoke(human.id, heat)
        ups.poll(250, roster).filterIsInstance<TeamUps.Formed>().size shouldBe 1
        return ups
    }

    "the bot whose idea it was calls for it, and a partner says it is in" {
        for (seed in 1..12) {
            val d = engine(BotPersonality.TOXIC, seed)
            d.teamedUp(pact(), emptyList(), everyone, 1000)
            val said = d.listen(1000)
            said.first().speaker shouldBe dino.id
            said.by(dino) shouldSay "jacob|chicken|camper"
            said.map { it.speaker } shouldContain fox.id
            // Nobody who is not in on it has anything to add.
            said.by(moss) shouldBe ""
        }
    }

    "what is held against the player is what the call is about" {
        fun call(reason: TeamUps.Reason, mode: BotPersonality, seed: Int): String {
            val d = engine(mode, seed)
            val ups = TeamUps(Random(1)).also { it.enabled = true }
            repeat(6) { ups.cast(human.id, "Chicken Missile", 500L + it) }
            repeat(3) { ups.death(dino.id, human.id, 600L + it) }
            d.teamUps = ups
            d.teamedUp(pact(reason = reason), emptyList(), everyone, 1000)
            return d.listen(1000).by(dino)
        }
        for (seed in 1..10) {
            call(TeamUps.Reason.SPAM, BotPersonality.TOXIC, seed) shouldSay "missile|truce|everyone on|spams"
            call(TeamUps.Reason.MOUTH, BotPersonality.TOXIC, seed) shouldSay "talk|say|shut|loud|typed|ez"
            call(TeamUps.Reason.FARMING, BotPersonality.SALTY, seed) shouldSay "kill|dying|thats it|team on|everyone on|get them"
        }
    }

    "the warning names what the player is doing" {
        for (seed in 1..10) {
            val d = engine(BotPersonality.TOXIC, seed)
            d.teamWarned(dino.id, human.id, TeamUps.Reason.CAMPING, everyone, 1000)
            val said = d.listen(1000)
            said.first().speaker shouldBe dino.id
            said.by(dino) shouldSay "camping|teamed|warning|3v1|team game"
        }
        engine(BotPersonality.SILENT).also { it.teamWarned(dino.id, human.id, TeamUps.Reason.CAMPING, everyone, 1000) }.listen(1000).shouldBeEmpty()
    }

    "a chill bot that was asked says no in its own words" {
        var spoke = 0
        for (seed in 1..20) {
            val d = engine(BotPersonality.RANDOM, seed)
            d.assign(dino.id, BotPersonality.TOXIC); d.assign(fox.id, BotPersonality.TOXIC); d.assign(moss.id, BotPersonality.CHILL)
            d.teamedUp(pact(), listOf(moss.id), everyone, 1000)
            val no = d.listen(1000).by(moss)
            if (no.isEmpty()) continue
            spoke++
            no shouldSay "nah|not|im good|lame|1v1|no teaming|im out|sorry"
        }
        spoke shouldBeGreaterThan 8
    }

    "a silent lobby teams up without a word, and still turns on its partners" {
        val d = engine(BotPersonality.SILENT)
        d.teamedUp(pact(), emptyList(), everyone, 1000)
        d.listen(1000).shouldBeEmpty()
        d.teamOver(pact(), TeamUps.End.KILLED, dino.id, fox.id, everyone, 30_000)
        d.listen(30_000).shouldBeEmpty()
        d.grudge(dino.id, 31_000) shouldBe fox.id
    }

    "when it is over somebody says so; a turncoat says what it is doing and means it" {
        for (seed in 1..10) {
            val d = engine(BotPersonality.TOXIC, seed)
            d.teamOver(pact(setOf(dino.id, fox.id, moss.id)), TeamUps.End.KILLED, fox.id, moss.id, everyone, 1000)
            d.grudge(fox.id, 1500) shouldBe moss.id
            val said = d.listen(1000)
            said.first().speaker shouldBe dino.id
            said.by(fox) shouldSay "next|truce over|friend|turn|personal|thought"
            said.by(moss) shouldNotBe ""
            // The one it turned on is not standing for it.
            d.grudge(moss.id, 20_000) shouldBe fox.id
        }
    }

    "asked why they are teaming, a bot that is in on it says why and one that is not says so" {
        for (seed in 1..10) {
            val toxic = engine(BotPersonality.TOXIC, seed)
            toxic.teamUps = teamed(100.0, BotPersonality.TOXIC, BotPersonality.TOXIC, BotPersonality.TOXIC)
            toxic.chat(human.id, "stop teaming on me", 2000)
            toxic.poll(5000, everyone) { x, y -> x != y }!!.text shouldSay "camp|come out|cry|fought"

            val mixed = engine(BotPersonality.RANDOM, seed)
            mixed.assign(dino.id, BotPersonality.TOXIC); mixed.assign(fox.id, BotPersonality.TOXIC); mixed.assign(moss.id, BotPersonality.CHILL)
            val ups = teamed(70.0, BotPersonality.TOXIC, BotPersonality.TOXIC, BotPersonality.CHILL)
            ups.pact!!.members shouldBe setOf(dino.id, fox.id)
            mixed.teamUps = ups
            mixed.chat(human.id, "moss why are you all teaming", 2000)
            val answer = mixed.poll(5000, everyone) { x, y -> x != y }!!
            answer.speaker shouldBe moss.id
            answer.text shouldSay "not|told them"
        }
    }

    "sorry does not work on a bot that is still after them" {
        for (seed in 1..10) {
            val d = engine(BotPersonality.TOXIC, seed)
            d.teamUps = teamed(100.0, BotPersonality.TOXIC, BotPersonality.TOXIC, BotPersonality.TOXIC)
            d.chat(human.id, "ok sorry", 2000)
            d.poll(5000, everyone) { x, y -> x != y }!!.text shouldSay "late|nah|^no$|should have|doesnt work"
        }
    }

    "everything said about teaming is typed the way the bots type, and names nobody he or she" {
        val lines = ArrayList<String>()
        // (One memory for all of it, so every wording comes up.)
        val memory = LineMemory()
        for (seed in 1..40) for (mode in listOf(BotPersonality.TOXIC, BotPersonality.SALTY, BotPersonality.CHILL)) for (reason in TeamUps.Reason.entries) {
            val d = engine(mode, seed, memory)
            val ups = TeamUps(Random(seed)).also { it.enabled = true }
            repeat(6) { ups.cast(human.id, "Chicken Missile", 500L + it) }
            repeat(3) { ups.death(dino.id, human.id, 600L + it) }
            d.teamUps = ups
            d.teamWarned(dino.id, human.id, reason, everyone, -30_000)
            lines += d.listen(-30_000).map { it.text }
            d.teamedUp(pact(setOf(dino.id, fox.id), reason), listOf(moss.id), everyone, 1000)
            lines += d.listen(1000).map { it.text }
            d.teamLeft(pact(setOf(dino.id), reason), listOf(fox.id), everyone, 40_000)
            lines += d.listen(40_000).map { it.text }
            val how = TeamUps.End.entries[seed % TeamUps.End.entries.size]
            d.teamOver(pact(setOf(dino.id, fox.id, moss.id), reason), how, fox.id, moss.id, everyone, 80_000)
            lines += d.listen(80_000).map { it.text }
        }
        lines.size shouldBeGreaterThan 300
        lines.distinct().size shouldBeGreaterThan 80
        for (line in lines) {
            line shouldBe BotSlang.style(line)
            Regex("\\b(he|him|his|hes|she|her|shes|guy|man)\\b").containsMatchIn(line) shouldBe false
        }
    }
})
