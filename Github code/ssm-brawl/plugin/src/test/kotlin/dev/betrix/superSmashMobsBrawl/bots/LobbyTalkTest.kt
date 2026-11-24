package dev.betrix.superSmashMobsBrawl.bots

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.util.UUID
import kotlin.random.Random

class LobbyTalkTest : StringSpec({
    val jacob = BotDialogue.Fighter(UUID(0, 1), "Jacob", "", 0, false, false)
    val dino = BotDialogue.Fighter(UUID(0, 2), "Dino", "", 0, true, false)
    val fox = BotDialogue.Fighter(UUID(0, 3), "Fox", "", 0, true, false)
    val mango = BotDialogue.Fighter(UUID(0, 4), "Mango", "", 0, true, false)
    val everybody = listOf(jacob, dino, fox, mango)
    infix fun String.shouldSay(pattern: String) { (Regex(pattern).containsMatchIn(this)) shouldBe true }

    fun lobby(mode: BotPersonality = BotPersonality.CHILL, seed: Int = 1, eager: Boolean = true,
              recall: (String, UUID) -> Acquaintance? = { _, _ -> null }) =
        LobbyTalk(Random(seed), LineMemory(), recall, { 2_000_000 }, eager).also { it.mode = mode }
    /** Everything said from [from] to [until], polled four times a second as the server does. */
    fun LobbyTalk.listen(from: Long, until: Long, members: List<BotDialogue.Fighter> = everybody): List<BotDialogue.Reply> =
        (from..until step 250).mapNotNull { poll(it, members) }
    fun memory(killedMe: Int = 0, killedThem: Int = 0, theyWon: Boolean = false, iWon: Boolean = false) =
        Acquaintance(2, 2_000_000 - 60_000, killedMe, killedThem, theyWon, iWon, if (theyWon) 1 else 0, false, false, "Skylands")

    // ---------------------------------------------------------------- what is remembered

    "a bot remembers the last match with a player, counts their wins in a row, and forgets after a few days" {
        var time = 1_000_000L
        val m = BotMemory { time }
        val player = UUID(0, 1)
        m.recall("Dino", player) shouldBe null
        m.record("Dino", player, killedMe = 3, killedThem = 1, theyWon = true, iWon = false, mouthed = true, nice = false, map = "Skylands")
        val first = m.recall("dino", player)!!
        first.matches shouldBe 1; first.killedMe shouldBe 3; first.killedThem shouldBe 1; first.theirStreak shouldBe 1
        first.farmedMe shouldBe true; first.farmedThem shouldBe false; first.mouthed shouldBe true; first.fresh(time + 60_000) shouldBe true
        time += 10 * 60_000
        m.record("Dino", player, 0, 2, theyWon = true, iWon = false, mouthed = false, nice = true, map = "Desert")
        m.recall("Dino", player)!!.let { it.matches shouldBe 2; it.theirStreak shouldBe 2; it.farmedThem shouldBe true; it.mouthed shouldBe false; it.map shouldBe "Desert" }
        m.record("Dino", player, 0, 0, theyWon = false, iWon = true, mouthed = false, nice = false, map = "Desert").theirStreak shouldBe 0
        // Another bot, and another player, know nothing of it.
        m.recall("Fox", player) shouldBe null
        m.recall("Dino", UUID(0, 9)) shouldBe null
        // An hour on it is no longer "last game"; a few days on it is gone.
        m.recall("Dino", player)!!.fresh(time + 2 * 3_600_000L) shouldBe false
        time += BotMemory.REMEMBERED_MS + 1
        m.recall("Dino", player) shouldBe null
    }
    "what is remembered survives a restart" {
        val folder = File(System.getProperty("java.io.tmpdir"), "ssm-bot-memory-test-${System.nanoTime()}").also { it.mkdirs() }
        try {
            val time = 5_000_000L
            val player = UUID(3, 4)
            val before = BotMemory { time }.also { it.setup(folder) }
            before.record("HotCold", player, 2, 0, theyWon = true, iWon = false, mouthed = true, nice = false, map = "Sky; Ruins")
            before.save()
            val after = BotMemory { time + 1000 }.also { it.setup(folder) }
            after.recall("hotcold", player) shouldBe before.recall("HotCold", player)
            after.recall("HotCold", player)!!.map shouldBe "Sky  Ruins"
            BotMemory.parse("rubbish") shouldBe null
        } finally { folder.deleteRecursively() }
    }

    // ---------------------------------------------------------------- hello

    "somebody who comes into a lobby is said hello to, a few seconds after the bots are there" {
        for (seed in 0..30) {
            val l = lobby(seed = seed)
            // The player first; the bots turn up half a second later.
            l.poll(1000, listOf(jacob)) shouldBe null
            val said = l.listen(1500, 12_000)
            (said.isNotEmpty()) shouldBe true
            said.first().text shouldSay "^(hi|hey|yo|sup|hello|heyy|welcome)"
            said.forEach { it.text shouldBe BotSlang.style(it.text); it.speaker shouldNotBe jacob.id }
            // Nobody is said hello to twice for stepping out and back in.
            l.poll(13_000, listOf(dino, fox, mango))
            l.listen(13_250, 25_000).forEach { it.text.shouldNotContain("welcome") }
        }
    }
    "a bot that has played them before says more than hi, and it is the one that remembers who says it" {
        val said = mutableSetOf<String>()
        for (seed in 0..40) {
            val l = lobby(BotPersonality.SALTY, seed) { name, _ -> if (name.equals("Fox", true)) memory(killedMe = 4) else null }
            val first = l.listen(1000, 12_000).first()
            if (first.speaker == fox.id) said += first.text
        }
        (said.size >= 3) shouldBe true
        said.any { it.contains("4 times last game") } shouldBe true
        said.forEach { it shouldSay "jacob" }
    }
    "a Silent lobby says nothing, and neither does a lobby with nobody in it to hear" {
        val quiet = lobby(BotPersonality.SILENT)
        quiet.chat(jacob.id, "hi", 1000)
        quiet.listen(1000, 60_000) shouldBe emptyList()
        lobby().listen(1000, 60_000, listOf(dino, fox, mango)) shouldBe emptyList()
    }

    // ---------------------------------------------------------------- after a match

    "back from a match nobody says hello: they talk about the match, with what happened in it" {
        val seen = mutableSetOf<String>()
        for (seed in 0..60) {
            val l = lobby(BotPersonality.SALTY, seed)
            l.poll(1000, listOf(dino, fox, mango))
            l.matchOver(LobbyTalk.LastMatch(1000, "Skylands", listOf(
                LobbyTalk.Score("Jacob", false, 7, 1, true), LobbyTalk.Score("Dino", true, 1, 4, false),
                LobbyTalk.Score("Fox", true, 2, 4, false), LobbyTalk.Score("Mango", true, 0, 4, false)),
                mapOf(("jacob" to "dino") to 3, ("jacob" to "fox") to 2, ("jacob" to "mango") to 2, ("fox" to "dino") to 1, ("dino" to "jacob") to 1)))
            val said = l.listen(9000, 40_000)
            (said.isNotEmpty()) shouldBe true
            said.forEach { it.text.shouldNotContain("welcome"); it.text shouldBe BotSlang.style(it.text) }
            seen += said.map { it.text }
        }
        // Dino died four times; nobody else did what Dino says happened to it.
        seen.any { it.contains("died 4 times") } shouldBe true
        seen.none { it.contains("died 3 times") || it.contains("died 7 times") } shouldBe true
        // Nobody but the winner is spoken of as the winner.
        seen.filter { it.contains("always wins") || it.contains("got lucky") }.forEach { it shouldSay "jacob" }
    }
    "back from a match they all won against one player, nobody calls it easy and nobody tells a teammate to cope" {
        val seen = mutableSetOf<String>()
        for (mode in listOf(BotPersonality.TOXIC, BotPersonality.SALTY, BotPersonality.CHILL)) for (seed in 0..80) {
            val l = lobby(mode, seed)
            l.poll(1000, listOf(dino, fox, mango))
            l.matchOver(LobbyTalk.LastMatch(1000, "Skylands", listOf(
                LobbyTalk.Score("Jacob", false, 2, 4, false, "ALONE"), LobbyTalk.Score("Dino", true, 1, 1, true, "ALL"),
                LobbyTalk.Score("Fox", true, 2, 0, true, "ALL"), LobbyTalk.Score("Mango", true, 1, 1, true, "ALL")),
                mapOf(("jacob" to "dino") to 1, ("jacob" to "mango") to 1, ("fox" to "jacob") to 2, ("dino" to "jacob") to 1, ("mango" to "jacob") to 1)))
            seen += l.listen(9000, 40_000).map { it.text }
        }
        seen.forEach { line ->
            Regex("ez game|that was free|too ez|carried|made that ez|close game|finally a good game").containsMatchIn(line) shouldBe false
            Regex("^(cope|skill issue|cry|excuses|nobody asked)").containsMatchIn(line) shouldBe false
        }
        seen.any { "3v1" in it } shouldBe true
    }
    "with a model the lobby's lines come with what they are about, and what the script has no line for is handed on" {
        var open = 0
        for (seed in 0..30) {
            val l = lobby(seed = seed).also { it.wordsmith = BotDialogue.Brief.Kind.REMARK }
            l.poll(1000, everybody)
            l.chat(jacob.id, "does anyone here like pizza", 9000)
            val said = l.listen(1250, 20_000)
            // (The hello to whoever walked in is said as scripted, model or no model.)
            said.firstOrNull { it.brief?.open == true }?.let { reply ->
                open++
                reply.text shouldBe ""
                (reply.brief!!.event.contains("Jacob typed \"does anyone here like pizza\" in the lobby.")) shouldBe true
            }
        }
        (open > 25) shouldBe true
        // Without a model nothing changes: no briefs, and a question the script does not know gets no answer.
        val l = lobby()
        l.poll(1000, everybody)
        l.chat(jacob.id, "does anyone here like pizza", 9000)
        l.listen(1250, 20_000).forEach { it.brief shouldBe null; (it.text.isNotEmpty()) shouldBe true }
    }
    "somebody who walks in after the match may say hi, and has nothing to say about a match it was not in" {
        val pebble = BotDialogue.Fighter(UUID(0, 6), "Pebble", "", 0, true, false)
        val hellos = mutableSetOf<String>()
        for (seed in 0..40) {
            val l = lobby(BotPersonality.SALTY, seed)
            l.matchOver(LobbyTalk.LastMatch(1000, "Skylands", listOf(
                LobbyTalk.Score("Jacob", false, 3, 4, false), LobbyTalk.Score("Dino", true, 4, 2, true),
                LobbyTalk.Score("Fox", true, 2, 4, false), LobbyTalk.Score("Mango", true, 1, 4, false)), mapOf(("dino" to "jacob") to 3)))
            // Mango has left; Pebble is standing there instead when everybody gets back.
            val said = l.listen(9000, 60_000, listOf(jacob, dino, fox, pebble))
            val first = said.firstOrNull { it.speaker == pebble.id } ?: continue
            hellos += first.text
            said.filter { it.speaker == pebble.id }.forEach {
                it.text.shouldNotContain("skylands"); it.text.shouldNotContain("died"); it.text.shouldNotContain("killed me")
            }
        }
        (hellos.isNotEmpty()) shouldBe true
        hellos.forEach { it shouldSay "^(hi|yo|hey)" }
    }
    "a lobby in which nobody answers runs out of things to say" {
        for (seed in 0..10) {
            val l = lobby(seed = seed)
            val said = l.listen(1000, 3 * 3_600_000L)
            // A hello or two, a few lines while waiting with an answer here and there, and then quiet.
            (said.size <= 2 + LobbyTalk.MAX_IDLE * 2) shouldBe true
            l.listen(3 * 3_600_000L, 4 * 3_600_000L) shouldBe emptyList()
            // Until somebody says something.
            l.chat(jacob.id, "ready?", 4 * 3_600_000L)
            (l.listen(4 * 3_600_000L, 4 * 3_600_000L + 20_000).isNotEmpty()) shouldBe true
        }
    }

    // ---------------------------------------------------------------- answering

    "what a player types in the lobby is answered to the point" {
        fun answer(text: String, mode: BotPersonality = BotPersonality.CHILL, seed: Int = 3, recall: (String, UUID) -> Acquaintance? = { _, _ -> null }): BotDialogue.Reply? {
            val l = lobby(mode, seed, recall = recall)
            l.listen(1000, 20_000)
            l.chat(jacob.id, text, 30_000)
            return l.listen(30_250, 40_000).firstOrNull()
        }
        answer("ready?")!!.text shouldSay "ready|yep|lets go|go ahead|^r$"
        answer("gg")!!.text shouldSay "gg|gf"
        answer("yo")!!.text shouldSay "hey|yo|hi|sup"
        answer("gtg bye")!!.text shouldSay "cya|bye|gn|later"
        // By name: that bot answers, even something it can make nothing of.
        answer("Mango what kit are you going")!!.speaker shouldBe mango.id
        answer("fox the window is open")!!.let { it.speaker shouldBe fox.id; it.text shouldSay "\\?|wdym|what|hm" }
        // Something that is nobody's business is left alone.
        answer("the window is open") shouldBe null
        // Trash talk gets what really happened last game for an answer, from the bot it happened with.
        val facts = (0..40).mapNotNull { seed ->
            answer("ur all trash", BotPersonality.TOXIC, seed) { name, _ -> if (name.equals("Dino", true)) memory(killedMe = 1, killedThem = 4) else null }
        }
        facts.forEach { it.speaker shouldBe dino.id }
        facts.any { it.text.contains("4 times last game") } shouldBe true
    }
    "back from a match, with the chances as they are in play, what a player types is still answered nearly every time" {
        val pebble = BotDialogue.Fighter(UUID(0, 6), "Pebble", "", 0, true, false)
        var answered = 0
        for (seed in 0..199) {
            val l = lobby(BotPersonality.RANDOM, seed, eager = false)
            l.poll(1000, listOf(dino, fox, mango))
            l.matchOver(LobbyTalk.LastMatch(1000, "Skylands", listOf(
                LobbyTalk.Score("Jacob", false, 3, 4, false), LobbyTalk.Score("Dino", true, 4, 2, true),
                LobbyTalk.Score("Fox", true, 2, 4, false), LobbyTalk.Score("Mango", true, 1, 4, false)), mapOf(("dino" to "jacob") to 3)))
            // The players are back first; the lobby's bots a few seconds after them, one of them new.
            l.listen(9000, 12_000, listOf(jacob))
            val members = listOf(jacob, dino, fox, pebble)
            l.listen(12_250, 40_000, members)
            l.chat(jacob.id, if (seed % 2 == 0) "rematch?" else "gg", 41_000)
            if (l.listen(41_250, 52_000, members).isNotEmpty()) answered++
        }
        (answered >= 150) shouldBe true
    }
    "who won is answered with who won" {
        for (seed in 0..20) {
            val l = lobby(seed = seed)
            l.poll(1000, listOf(dino, fox, mango))
            l.matchOver(LobbyTalk.LastMatch(1000, "Desert", listOf(LobbyTalk.Score("Jacob", false, 2, 4, false), LobbyTalk.Score("Dino", true, 1, 4, false),
                LobbyTalk.Score("Fox", true, 5, 1, true), LobbyTalk.Score("Mango", true, 0, 4, false)), emptyMap()))
            l.listen(9000, 20_000)
            l.chat(jacob.id, "who won", 200_000)
            val reply = l.listen(200_250, 215_000).first()
            if (reply.speaker == fox.id) reply.text shouldSay "^(me|i did)" else reply.text shouldSay "fox"
        }
    }
})
