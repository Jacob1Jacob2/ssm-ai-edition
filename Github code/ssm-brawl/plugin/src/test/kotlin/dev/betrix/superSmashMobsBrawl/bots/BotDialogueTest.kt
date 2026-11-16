package dev.betrix.superSmashMobsBrawl.bots

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID
import kotlin.random.Random

class BotDialogueTest : StringSpec({
    val human = BotDialogue.Fighter(UUID(0,1), "Jacob", "Chicken", 4, false)
    val bot = BotDialogue.Fighter(UUID(0,2), "Dino", "Guardian", 3, true)
    val other = BotDialogue.Fighter(UUID(0,3), "Fox", "Creeper", 2, true)
    // A private memory and guaranteed reactions keep each test independent of the others.
    fun engine(mode: BotPersonality = BotPersonality.TOXIC, seed: Int = 2, memory: LineMemory = LineMemory()) =
        BotDialogue(Random(seed), memory, 1.0).also { it.mode = mode }
    fun BotDialogue.reply(text: String, time: Long = 1000, facts: List<BotDialogue.Fighter> = listOf(human,bot)): BotDialogue.Reply? {
        chat(human.id,text,time); return poll(time+3000,facts) {a,b -> a != b}
    }
    infix fun String.shouldSay(pattern: String) { (Regex(pattern).containsMatchIn(this)) shouldBe true }

    "old saves and unknown values migrate to Silent; every saved mode round trips" {
        BotPersonality.restore(null) shouldBe BotPersonality.SILENT
        BotPersonality.restore("invalid") shouldBe BotPersonality.SILENT
        BotPersonality.entries.forEach { BotPersonality.restore(it.name) shouldBe it }
    }
    "reply uses actual current lives rather than assuming bot is winning" {
        engine().reply("ez Dino")!!.text shouldSay "4 to 3|ahead by 1"
        engine().reply("ez Dino", facts=listOf(human.copy(lives=1),bot))!!.text shouldSay "1 to 3|1 lives"
    }
    "same life count is treated as tied" {
        engine().reply("ez",facts=listOf(human.copy(lives=3),bot))!!.text shouldSay "tied|both on 3|3 to 3"
    }
    "a boast is answered with the kill the bot really got" {
        val d=engine();d.death(human.id,bot.id,"Static Laser",500)
        d.reply("ez Dino")!!.text shouldSay "die|dead|killed"
    }
    "bot admits a real loss instead of claiming the kill" {
        val d=engine();d.death(bot.id,human.id,"Chicken Missile",500)
        val text=d.reply("L Dino")!!.text
        text shouldSay "one kill|ok and|missile"
        text.shouldNotContain("died to me")
    }
    "old deaths expire instead of being cited as just happened" {
        val d=engine();d.death(human.id,bot.id,"Static Laser",0)
        val text=d.reply("ez",30000)!!.text
        text.shouldNotContain("laser");text.shouldNotContain("die")
    }
    "death reaction cannot fabricate a void death for lava" {
        for (seed in 0..20) {
            val d=engine(seed=seed);d.death(bot.id,null,"the environment",1000)
            val text=d.poll(4000,listOf(human,bot)){_,_->true}!!.text
            text.shouldNotContain("void");text.shouldNotContain("fell");text.shouldNotContain("jump")
        }
    }
    "named bot responds instead of a random bot" {
        engine().reply("Fox what kit are you",facts=listOf(human,bot,other))!!.speaker shouldBe other.id
    }
    "mentions match full names, not incidental substrings" {
        engine().reply("what kit is foxtrot using",facts=listOf(human,bot))!!.speaker shouldBe bot.id
    }
    "a nickname reaches the bot it belongs to" {
        val long = bot.copy(name = "JezusLuver44")
        engine().reply("jezus what kit are u",facts=listOf(human,long,other))!!.speaker shouldBe long.id
    }
    "repeated casts are evidence of spam until they expire" {
        val d=engine();repeat(4){d.cast(human.id,"Chicken Missile",1000L+it)}
        d.reply("stop spamming",2000)!!.text shouldSay "missile|look whos talking"
        d.reply("stop spamming",60000)!!.text.shouldNotContain("missile")
    }
    "short followup stays on the prior ability topic with same bot" {
        val d=engine();repeat(3){d.cast(bot.id,"Static Laser",1000L+it)}
        d.reply("stop spamming",2000) shouldNotBe null
        val response=d.reply("why?",8000)!!
        response.speaker shouldBe bot.id;response.text shouldSay "laser|u know what"
    }
    "targeting accusation is denied when bot never attacked" {
        engine().reply("why are you targeting me")!!.text shouldSay "havent|didnt|wasnt|not even"
    }
    "targeting response counts unique actual attackers not number of hits" {
        val d=engine();repeat(8){d.damage(bot.id,human.id,1000)};d.damage(other.id,human.id,1001)
        val text=d.reply("why are you targeting me",2000)!!.text
        text shouldSay "2 |ffa|middle|everyone";text.shouldNotContain("8")
    }
    "teammates are not described as opponents" {
        val d=engine();d.chat(human.id,"why are you targeting me",1000)
        d.poll(4000,listOf(human,bot)){_,_->false}!!.text.shouldContain("team")
    }
    "an accusation of hacking is denied in character" {
        engine().reply("ur hacking")!!.text shouldSay "legit|no|hitbox|good|hacking|report|cope|aim"
    }
    "unrelated messages are not answered by canned insults" {
        engine().reply("I will be back after dinner") shouldBe null
        engine().reply("the window is open") shouldBe null
    }
    "no previous topic means no invented conversation for why" {
        engine().reply("why?") shouldBe null
    }
    "Silent suppresses chat combat start crystal and match-end messages" {
        val d=engine(BotPersonality.SILENT);d.start(1000);d.death(bot.id,human.id,"Attack",1001);d.crystal(human.id,"Ultra Smash Crystal",1002)
        d.burst(bot.id,human.id,3,1003);d.holding(human.id,bot.id,25,1,1004)
        d.reply("ez") shouldBe null
        d.finish(setOf(human.id),listOf(human,bot),5000) shouldBe null
    }
    "changing to Silent cancels an already queued reply even after changing back" {
        val d=engine();d.chat(human.id,"ez",1000);d.mode=BotPersonality.SILENT;d.mode=BotPersonality.TOXIC
        d.poll(4000,listOf(human,bot)){_,_->true} shouldBe null
    }
    "Chill answers the same facts without accusations or salt" {
        val d=engine(BotPersonality.CHILL);d.death(human.id,bot.id,"Static Laser",500)
        val text=d.reply("ez")!!.text
        listOf("wtf","bs","hack","dead guy").forEach { text.shouldNotContain(it) }
    }
    "typing delay and shared rate limit prevent a rapid-fire chorus" {
        val d=engine();d.chat(human.id,"ez",1000)
        d.poll(1500,listOf(human,bot,other)){_,_->true} shouldBe null
        (d.poll(4000,listOf(human,bot,other)){_,_->true}!=null) shouldBe true
        d.chat(human.id,"what kit are you",4100)
        d.poll(7000,listOf(human,bot,other)){_,_->true} shouldBe null
        (d.poll(9500,listOf(human,bot,other)){_,_->true}!=null) shouldBe true
    }
    "queued chat has priority over ambient events and another user's chat cannot replace its context" {
        val d=engine();d.start(1000);d.chat(human.id,"Dino what kit are you",1001) shouldBe true
        d.chat(other.id,"gg",1002) shouldBe false
        d.death(bot.id,other.id,"Attack",1003)
        d.poll(4000,listOf(human,bot,other)){_,_->true}!!.text.shouldContain("guardian")
    }
    "departed speakers and expired requests cannot send delayed chat" {
        val d=engine();d.chat(human.id,"ez",1000)
        d.poll(4000,listOf(bot)){_,_->true} shouldBe null
        d.chat(human.id,"ez",5000);d.poll(18000,listOf(human,bot)){_,_->true} shouldBe null
    }
    "life snapshot is read at send time and eliminated players are acknowledged" {
        val d=engine();d.chat(human.id,"ez",1000)
        d.poll(4000,listOf(human.copy(lives=0,fighting=false),bot)){_,_->true}!!.text shouldSay "out|dead|spectat|0 lives"
    }
    "the losing bot speaks at the end without claiming the win, and cleanup cancels delayed replies" {
        val d=engine();d.chat(human.id,"ez",1000)
        val last=d.finish(setOf(human.id),listOf(human,bot),4000)!!
        last.speaker shouldBe bot.id;last.text.shouldNotContain("ez")
        d.poll(10000,listOf(human,bot)){_,_->true} shouldBe null
        d.chat(human.id,"gg",11000);d.clear();d.poll(14000,listOf(human,bot)){_,_->true} shouldBe null
    }
    "camping remark needs repeated ranged-use evidence, never idle alone" {
        val d=engine();d.camp(human.id,1000);d.poll(4000,listOf(human,bot)){_,_->true} shouldBe null
        repeat(3){d.cast(human.id,"Chicken Missile",5000L+it)};d.camp(human.id,6000)
        d.poll(9000,listOf(human,bot)){_,_->true} shouldNotBe null
    }
    "crystal response is about the crystal" {
        val d=engine();d.crystal(human.id,"Ultra Smash Crystal",1000)
        d.poll(4000,listOf(human,bot)){_,_->true}!!.text shouldSay "crystal|run|oh no|smash|who let"
    }

    // ---------------------------------------------------------------- random personalities

    val third = BotDialogue.Fighter(UUID(0,4), "Mango", "Wolf", 4, true)
    /** Everything said from [from] to [until], polled the way the server does (four times a second). */
    fun BotDialogue.listen(from: Long, until: Long, facts: List<BotDialogue.Fighter>): List<BotDialogue.Reply> =
        (from..until step 250).mapNotNull { poll(it, facts) { a, b -> a != b } }

    "a random deal never leaves more than half the bots silent, and a lone bot always talks" {
        for (seed in 0..300) for (count in 1..15) {
            val bots = (1..count).map { UUID(9, it.toLong()) }
            val dealt = BotPersonality.deal(bots, emptyMap(), Random(seed))
            dealt.keys shouldBe bots.toSet()
            dealt.values.forEach { (it == BotPersonality.RANDOM) shouldBe false }
            (dealt.values.count { it == BotPersonality.SILENT } <= count / 2) shouldBe true
        }
        // Bots joining a match that is already half silent can only be talkers.
        val known = mapOf(UUID(9, 1) to BotPersonality.SILENT, UUID(9, 2) to BotPersonality.TOXIC)
        for (seed in 0..50) BotPersonality.deal(listOf(UUID(9, 3)), known, Random(seed)).values.single() shouldNotBe BotPersonality.SILENT
    }
    "a random lobby mixes personalities and they stay put for the match" {
        val seen = mutableSetOf<BotPersonality>()
        for (seed in 0..60) {
            val d = engine(BotPersonality.RANDOM, seed)
            d.poll(1000, listOf(human, bot, other, third)) { a, b -> a != b }
            val first = d.personalities()
            first.keys shouldBe setOf(bot.id, other.id, third.id)
            d.poll(2000, listOf(human, bot, other, third)) { a, b -> a != b }
            d.personalities() shouldBe first
            seen += first.values
        }
        seen shouldBe setOf(BotPersonality.TOXIC, BotPersonality.SALTY, BotPersonality.CHILL, BotPersonality.SILENT)
    }
    "a bot dealt Silent never says a word, whatever happens to it" {
        for (seed in 0..40) {
            val d = engine(BotPersonality.RANDOM, seed)
            d.assign(bot.id, BotPersonality.SILENT); d.assign(other.id, BotPersonality.TOXIC); d.assign(third.id, BotPersonality.CHILL)
            val facts = listOf(human, bot, other, third)
            d.burst(bot.id, human.id, 3, 1000); d.death(bot.id, human.id, "Chicken Missile", 1200)
            val said = d.listen(1500, 30_000, facts) + listOfNotNull(d.reply("Dino what kit are you", 40_000, facts)) +
                listOfNotNull(d.finish(setOf(bot.id), facts, 60_000))
            said.forEach { it.speaker shouldNotBe bot.id }
        }
    }
    "each bot answers in its own personality" {
        val d = engine(BotPersonality.RANDOM)
        d.assign(bot.id, BotPersonality.TOXIC); d.assign(other.id, BotPersonality.CHILL)
        d.personalityOf(bot.id) shouldBe BotPersonality.TOXIC
        d.personalityOf(other.id) shouldBe BotPersonality.CHILL
        d.reply("Dino ur hacking", facts = listOf(human, bot, other))!!.text shouldSay "legit|no|hitbox|good|hacking|report|cope|aim"
        d.reply("Fox ur hacking", 20_000, listOf(human, bot, other))!!.text shouldSay "lucky|no hacks|lol no|wish"
    }
    "a chill bot tells a salty one to calm down, gets told off, and the chill bots team up on it" {
        var defended = 0; var rebuked = 0; var teamed = 0
        for (seed in 0..400) {
            val d = engine(BotPersonality.RANDOM, seed)
            d.assign(bot.id, BotPersonality.TOXIC); d.assign(other.id, BotPersonality.CHILL); d.assign(third.id, BotPersonality.CHILL)
            val facts = listOf(human, bot, other, third)
            d.burst(bot.id, human.id, 3, 1000)
            val said = d.listen(1500, 25_000, facts)
            if (said.isEmpty()) continue
            said.first().speaker shouldBe bot.id
            val chill = said.filter { it.speaker == other.id || it.speaker == third.id }
            if (chill.isNotEmpty()) defended++
            // Once a chill bot has spoken, anything more from the salty one is its answer.
            val firstChill = said.indexOfFirst { it.speaker != bot.id }
            if (firstChill >= 0 && said.drop(firstChill).any { it.speaker == bot.id }) rebuked++
            if (chill.any { Regex("team on|everyone get|first then|truce|all on|who wants|can we all").containsMatchIn(it.text) }) {
                teamed++
                // Saying it is not enough: both of them now go after the salty bot.
                (d.grudge(other.id, 25_000) to d.grudge(third.id, 25_000)) shouldBe (bot.id to bot.id)
                (chill.size >= 2) shouldBe true
            }
            d.grudge(bot.id, 25_000) shouldBe null
            d.grudge(other.id, 25_000 + BotDialogue.GRUDGE_MS + 20_000) shouldBe null
        }
        (defended > 40) shouldBe true
        (rebuked > 20) shouldBe true
        (teamed > 5) shouldBe true
    }
    "two salty bots keep an argument going; a chill one shrugs it off" {
        var clapbacks = 0; var shrugs = 0
        for (seed in 0..200) for (mood in listOf(BotPersonality.TOXIC, BotPersonality.CHILL)) {
            val d = engine(BotPersonality.RANDOM, seed)
            d.assign(bot.id, BotPersonality.TOXIC); d.assign(other.id, mood)
            val facts = listOf(human, bot, other.copy(fighting = false))
            // Dino kills Fox; Dino (the killer) gloats and Fox answers from the respawn screen.
            d.death(other.id, bot.id, "Static Laser", 1000)
            val said = d.listen(1500, 20_000, facts)
            val answer = said.firstOrNull { it.speaker == other.id } ?: continue
            if (said.first().speaker != bot.id) continue
            if (mood == BotPersonality.TOXIC) clapbacks++ else {
                shrugs++
                listOf("shut up", "cry", "nobody asked").forEach { answer.text.shouldNotContain(it) }
            }
        }
        (clapbacks > 20) shouldBe true
        (shrugs > 20) shouldBe true
    }
    "a toxic bot gloats over a salty one, the salty one snaps, and the two go for each other" {
        var snapped = 0; var mutual = 0
        for (seed in 0..300) {
            val d = engine(BotPersonality.RANDOM, seed)
            d.assign(bot.id, BotPersonality.TOXIC); d.assign(other.id, BotPersonality.SALTY)
            val facts = listOf(human, bot, other.copy(fighting = false))
            // Dino kills Fox and says something like "ez"; Fox, already salty, has had enough.
            d.death(other.id, bot.id, "Static Laser", 1000)
            val said = d.listen(1500, 20_000, facts)
            if (said.firstOrNull()?.speaker != bot.id) continue
            val answer = said.firstOrNull { it.speaker == other.id } ?: continue
            if (!Regex("shut up|sick of u|thats it|ur dead|coming for u|say that again|laughing|ur first|ur next|done with u").containsMatchIn(answer.text)) continue
            snapped++
            // It is not just talk: the salty one now hunts the toxic one...
            d.grudge(other.id, 20_000) shouldBe bot.id
            // ...and when the toxic one answers, it hunts back.
            if (said.dropWhile { it.speaker != other.id }.any { it.speaker == bot.id }) { mutual++; d.grudge(bot.id, 20_000) shouldBe other.id }
        }
        (snapped > 60) shouldBe true
        (mutual > 30) shouldBe true
    }
    "teammates can argue but never get a grudge against each other" {
        for (seed in 0..150) {
            val d = engine(BotPersonality.RANDOM, seed)
            d.assign(bot.id, BotPersonality.TOXIC); d.assign(other.id, BotPersonality.SALTY)
            val facts = listOf(human, bot, other)
            // Fox dies to the human while Dino, on Fox's team, watches and may rub it in.
            d.death(other.id, human.id, "Chicken Missile", 1000)
            (1500L..20_000L step 250).forEach { d.poll(it, facts) { a, b -> setOf(a, b) != setOf(bot.id, other.id) } }
            d.grudge(bot.id, 20_000) shouldBe null
            d.grudge(other.id, 20_000) shouldBe null
        }
    }
    "a salty bot complains about what happened to it, and a toxic bystander rubs it in" {
        var complaints = 0; var needled = 0; var consoled = 0
        for (seed in 0..300) for (mood in listOf(BotPersonality.TOXIC, BotPersonality.CHILL)) {
            val d = engine(BotPersonality.RANDOM, seed)
            d.assign(other.id, BotPersonality.SALTY); d.assign(bot.id, mood)
            val facts = listOf(human, bot, other.copy(fighting = false))
            d.death(other.id, human.id, "Chicken Missile", 1000)
            val said = d.listen(1500, 20_000, facts)
            if (said.firstOrNull()?.speaker != other.id) continue
            complaints++
            // A complaint is about its own bad luck, never a gloat.
            listOf("ez", "get rekt", "skill issue", "sit down").forEach { said.first().text shouldNotBe it }
            val reply = said.firstOrNull { it.speaker == bot.id } ?: continue
            if (mood == BotPersonality.TOXIC) needled++ else {
                consoled++
                listOf("cry", "whining", "skill issue", "stay mad").forEach { reply.text.shouldNotContain(it) }
            }
        }
        (complaints > 200) shouldBe true
        (needled > 40) shouldBe true
        (consoled > 20) shouldBe true
    }
    "one personality for everyone still gives the old short pile-on and no team-ups" {
        for (seed in 0..120) {
            val d = engine(BotPersonality.CHILL, seed)
            val facts = listOf(human, bot, other, third)
            d.burst(bot.id, human.id, 3, 1000)
            d.listen(1500, 30_000, facts)
            listOf(bot, other, third).forEach { d.grudge(it.id, 30_000) shouldBe null }
            d.personalities() shouldBe emptyMap()
        }
    }
    "typing a line takes longer the longer it is, within a brief pause" {
        BotSlang.typingTicks("ez") shouldBe 8
        (BotSlang.typingTicks("stop spamming missile") in 25..35) shouldBe true
        BotSlang.typingTicks("x".repeat(200)) shouldBe 44
    }

    // ---------------------------------------------------------------- how bots type

    "every line is lowercase with no commas apostrophes or periods" {
        val said = mutableListOf<String>()
        val prompts = listOf("ez", "stop spamming", "why are you targeting me", "come fight", "ur hacking", "gg", "hi", "what kit",
            "how many lives", "this is boring", "nice shot", "sorry", "ur a bot", "always chicken")
        for (mode in listOf(BotPersonality.TOXIC, BotPersonality.SALTY, BotPersonality.CHILL)) for (seed in 0..40) {
            val d = engine(mode, seed)
            repeat(4) { d.cast(human.id, "Chicken Missile", 100L + it) }
            when (seed % 6) {
                0 -> d.death(human.id, bot.id, "Target Laser", 900)
                1 -> d.death(bot.id, human.id, "Chicken Missile", 900)
                2 -> d.burst(bot.id, human.id, 3, 900)
                3 -> d.holding(human.id, bot.id, 25, 1, 900)
                4 -> d.crystal(human.id, "Ultra Smash Crystal", 900)
                else -> d.start(900)
            }
            d.poll(4000, listOf(human, bot)) { a, b -> a != b }?.let { said += it.text }
            d.poll(7000, listOf(human, bot)) { a, b -> a != b }?.let { said += it.text }
            d.reply(prompts[seed % prompts.size], 20_000)?.let { said += it.text }
            d.finish(setOf(if (seed % 2 == 0) human.id else bot.id), listOf(human, bot), 40_000)?.let { said += it.text }
        }
        (said.size > 150) shouldBe true
        said.forEach { line ->
            line shouldBe line.lowercase()
            line.shouldMatch("[^,.'’!]+")
            line shouldBe line.trim()
        }
    }
    "styling strips grammar from anything, including names and ability names" {
        BotSlang.style("Hey, Jacob's  Chicken Missile. OK?") shouldBe "hey jacobs chicken missile ok?"
    }
    "names shorten the way players shorten them" {
        BotSlang.nick("JezusLuver44") shouldBe "jezus"
        BotSlang.nick("Jacob1Jacob2") shouldBe "jacob"
        BotSlang.nick("TongJeung") shouldBe "tong"
        BotSlang.nick("leo_thya") shouldBe "leo"
        BotSlang.nick("Q____Q") shouldBe "q____q"
        BotSlang.nick("Kimchar") shouldBe "kimchar"
        BotSlang.kit("Magma Cube") shouldBe "magma"
        BotSlang.move("Chicken Missile", short = true) shouldBe "missile"
    }
    "the same opening line does not come back until the others have been used" {
        val memory = LineMemory()
        val openers = (0 until 8).map { engine(seed = it, memory = memory).let { d -> d.start(1000); d.poll(4000, listOf(bot)) { _, _ -> true }!!.text } }
        openers.toSet().size shouldBe 8
        openers.forEach { it.shouldNotContain("actually fighting") }
    }
    "a camper is called out harder each time, then left alone for longer" {
        val d = engine()
        fun callout(at: Long): String? { if (!d.holding(human.id, bot.id, 25, 1, at)) return null; return d.poll(at + 3000, listOf(human, bot)) { a, b -> a != b }?.text }
        val first = callout(1000)!!
        d.holding(human.id, bot.id, 45, 1, 21_000) shouldBe false
        val second = callout(60_000)!!
        val third = callout(140_000)!!
        setOf(first, second, third).size shouldBe 3
    }
    "some lines arrive as two quick messages from the same bot" {
        var pairs = 0
        for (seed in 0..120) {
            val d = engine(seed = seed); d.burst(bot.id, human.id, 3, 1000)
            val first = d.poll(4000, listOf(human, bot)) { a, b -> a != b } ?: continue
            val second = d.poll(6500, listOf(human, bot)) { a, b -> a != b } ?: continue
            second.speaker shouldBe first.speaker
            pairs++
        }
        (pairs > 0) shouldBe true
    }
    "another bot sometimes backs up a callout, in a few words" {
        var echoes = 0
        for (seed in 0..120) {
            val d = engine(seed = seed); d.burst(bot.id, human.id, 3, 1000)
            val first = d.poll(4000, listOf(human, bot, other)) { a, b -> a != b } ?: continue
            val second = d.poll(8000, listOf(human, bot, other)) { a, b -> a != b } ?: continue
            if (second.speaker == other.id) { echoes++; (second.text.length < 30) shouldBe true }
            first.speaker shouldBe bot.id
        }
        (echoes > 0) shouldBe true
    }
})
