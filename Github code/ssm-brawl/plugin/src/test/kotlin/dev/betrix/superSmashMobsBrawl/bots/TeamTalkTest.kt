package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.bots.TeamTalk.Ask
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.random.Random

/** Two teams: what a player asks of the bots on their side, and what a bot says back. */
class TeamTalkTest : StringSpec({
    fun named(n: Long, name: String, kit: String) = TeamTalk.Named(UUID(0, n), name, kit)
    val horror = named(100, "_EternalHorror_", "Skeleton")
    val smarty = named(101, "SmartyAlek", "Creeper")
    val cqsey = named(200, "cqsey", "Pig")
    val charlie = named(201, "Charlie_Skirk", "Iron Golem")
    val mates = listOf(horror, smarty)
    val foes = listOf(cqsey, charlie)
    fun read(text: String) = TeamTalk.parse(text, mates, foes)
    fun ask(text: String) = read(text)?.ask

    "the line from the match is a call to come, to the one it names" {
        val order = read("_EternalHorror_ come in the cave so we can spam")!!
        order.ask shouldBe Ask.COME
        order.to shouldBe listOf(horror.id)
        order.target shouldBe null
    }

    "asked of nobody by name, it is asked of the whole team" {
        for (line in listOf("come here", "follow me", "help", "where are you", "over here", "stay with me", "group up", "get in the cave", "Come Here!!")) {
            val order = read(line)!!
            order.ask shouldBe Ask.COME
            order.to shouldBe mates.map { it.id }
        }
        // Being outnumbered is a call for help too, however it is put.
        for (line in listOf("theyre both on me", "2v1 over here", "cmere", "need u", "im getting jumped")) ask(line) shouldBe Ask.COME
        read("eternal follow me")!!.to shouldBe listOf(horror.id)
        read("alek come")!!.to shouldBe listOf(smarty.id)
    }

    "an opponent to go after: by name, by a piece of the name, by kit" {
        for (line in listOf("get cqsey", "kill the pig", "cqsey is low", "team on cqsey", "cqsey")) {
            val order = read(line)!!
            order.ask shouldBe Ask.FOCUS
            order.target shouldBe cqsey.id
        }
        for (line in listOf("charlie is low", "everyone on skirk", "golem first", "smarty get the golem")) {
            val order = read(line)!!
            order.ask shouldBe Ask.FOCUS
            order.target shouldBe charlie.id
        }
        read("smarty get the golem")!!.to shouldBe listOf(smarty.id)
        // "Him" is whoever the player is fighting: that is worked out in the match, not from the words.
        for (line in listOf("kill him", "hes low", "they are almost dead", "get that one")) read(line)!!.let { it.ask shouldBe Ask.FOCUS; it.target shouldBe null }
        // Somebody named who is on the player: that is who to get.
        read("cqsey on me")!!.let { it.ask shouldBe Ask.FOCUS; it.target shouldBe cqsey.id }
    }

    "talk that asks nothing is left to the ordinary chat" {
        for (line in listOf("gg cqsey", "nice one", "lol", "come on", "wtf", "that was close", "")) read(line) shouldBe null
    }

    "falling back, holding, going in, never mind" {
        for (line in listOf("run", "fall back", "back up", "get out", "retreat")) ask(line) shouldBe Ask.RETREAT
        for (line in listOf("stay here", "wait here", "hold", "dont move", "wait")) ask(line) shouldBe Ask.HOLD
        for (line in listOf("go", "push", "attack", "lets go")) ask(line) shouldBe Ask.PUSH
        for (line in listOf("nvm", "never mind", "DON'T FOLLOW ME", "stop following", "do your thing")) ask(line) shouldBe Ask.FREE
    }

    "said to a bot by name with nothing it can do: it is unclear, not a guess" {
        val order = read("smarty what are you doing")!!
        order.ask shouldBe Ask.UNCLEAR
        order.to shouldBe listOf(smarty.id)
    }

    "with nobody on the team there is nobody to ask" {
        TeamTalk.parse("come here", emptyList(), foes) shouldBe null
    }

    // ------------------------------------------------------------------ what the bot says back

    val human = BotDialogue.Fighter(UUID(0, 1), "Jacob", "Chicken", 4, false)
    val bot = BotDialogue.Fighter(horror.id, horror.name, horror.kit, 2, true)
    val foe = BotDialogue.Fighter(cqsey.id, cqsey.name, cqsey.kit, 3, true)
    val everyone = listOf(human, bot, foe)
    val talkers = listOf(BotPersonality.TOXIC, BotPersonality.SALTY, BotPersonality.CHILL)
    fun engine(mode: BotPersonality, seed: Int, memory: LineMemory = LineMemory()) = BotDialogue(Random(seed), memory, 1.0).also { it.mode = mode }
    fun BotDialogue.listen(from: Long, among: List<BotDialogue.Fighter> = everyone): List<Pair<Long, BotDialogue.Reply>> =
        (1..80).mapNotNull { step -> poll(from + step * 250L, among) { x, y -> x != y }?.let { step * 250L to it } }
    infix fun String.shouldSay(pattern: String) { (Regex(pattern).containsMatchIn(this)) shouldBe true }

    "asked to come it says it is coming, soon, and says nothing about spam" {
        for (seed in 1..15) for (mode in talkers) {
            val d = engine(mode, seed)
            // Without teams the word "spam" in the line would have had a comeback queued.
            d.chat(human.id, "_eternalhorror_ come in the cave so we can spam", 1000)
            d.teamAnswer(bot.id, human.id, Ask.COME, null, everyone, 1000) shouldBe true
            val said = d.listen(1000)
            said.size shouldBe 1
            said[0].first shouldBeLessThanOrEqual 2000L
            said[0].second.speaker shouldBe bot.id
            said[0].second.text shouldSay "omw|coming|on my way|1 sec|right there"
        }
    }

    "sent after somebody it says who" {
        for (seed in 1..15) for (mode in talkers) {
            val d = engine(mode, seed)
            d.teamAnswer(bot.id, human.id, Ask.FOCUS, foe.id, everyone, 1000) shouldBe true
            d.listen(1000).single().second.text shouldSay "cqsey|pig"
            // Nobody it could make out: it asks who.
            val e = engine(mode, seed)
            e.teamAnswer(bot.id, human.id, Ask.FOCUS, null, everyone, 1000) shouldBe true
            e.listen(1000).single().second.text shouldSay "who|which"
        }
    }

    "dead, it says it is dead; out of lives, that it is out" {
        for (seed in 1..10) for (mode in talkers) {
            val dead = everyone.map { if (it.id == bot.id) it.copy(fighting = false) else it }
            val d = engine(mode, seed)
            d.teamAnswer(bot.id, human.id, Ask.COME, null, dead, 1000) shouldBe true
            d.listen(1000, dead).single().second.text shouldSay "dead|died|respawn|back in"
            val out = everyone.map { if (it.id == bot.id) it.copy(lives = 0, fighting = false) else it }
            val e = engine(mode, seed)
            e.teamAnswer(bot.id, human.id, Ask.COME, null, out, 1000) shouldBe true
            e.listen(1000, out).single().second.text shouldSay "out|no lives|cant"
        }
    }

    "a silent teammate says nothing (and does it all the same)" {
        val d = engine(BotPersonality.SILENT, 1)
        d.teamAnswer(bot.id, human.id, Ask.COME, null, everyone, 1000) shouldBe false
        d.listen(1000).shouldBeEmpty()
    }

    "every answer is typed the way the bots type, and names nobody he or she" {
        val lines = ArrayList<String>()
        val memory = LineMemory()
        for (seed in 1..30) for (mode in talkers) for (what in Ask.entries) for (target in listOf(foe.id, null)) {
            val d = engine(mode, seed, memory)
            if (d.teamAnswer(bot.id, human.id, what, target, everyone, 1000)) lines += d.listen(1000).map { it.second.text }
            val dead = everyone.map { if (it.id == bot.id) it.copy(fighting = false, lives = seed % 2) else it }
            val e = engine(mode, seed, memory)
            if (e.teamAnswer(bot.id, human.id, what, target, dead, 1000)) lines += e.listen(1000, dead).map { it.second.text }
        }
        lines.size shouldBeGreaterThan 400
        lines.distinct().size shouldBeGreaterThan 60
        for (line in lines) {
            line shouldBe BotSlang.style(line)
            Regex("\\b(he|him|his|hes|she|her|shes|guy|man)\\b").containsMatchIn(line) shouldBe false
        }
    }
})
