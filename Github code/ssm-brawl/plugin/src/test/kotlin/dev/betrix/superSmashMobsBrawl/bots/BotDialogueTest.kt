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

    "nobody is Silent by nature: a quiet bot says little, and in a small lobby even it says something" {
        val names = (1..400).map { "Player$it" }
        val dealt = BotCharacter.deal(names.mapIndexed { i, name -> UUID(9, i.toLong()) to name })
        dealt.size shouldBe names.size
        dealt.values.toSet() shouldBe setOf(BotPersonality.TOXIC, BotPersonality.SALTY, BotPersonality.CHILL)
        // About one in five hardly says a word among others...
        val quiet = names.filter { BotCharacter.of(it).chatty < 0.2 }
        (quiet.size in 40..130) shouldBe true
        quiet.forEach { BotCharacter.chattyAmong(it, 5) shouldBe BotCharacter.of(it).chatty }
        // ...and alone with a player, or with one other bot, it is not a silent match for that.
        quiet.forEach { (BotCharacter.chattyAmong(it, 1) >= 0.7) shouldBe true; (BotCharacter.chattyAmong(it, 2) >= 0.4) shouldBe true }
        names.forEach { (BotCharacter.chattyAmong(it, 1) <= 1.0) shouldBe true }
    }
    "in a Random match every bot is who it always is, whatever the match and whatever its id there" {
        val everybody = mutableSetOf<BotPersonality>()
        for (seed in 0..20) {
            val d = engine(BotPersonality.RANDOM, seed)
            d.poll(1000, listOf(human, bot, other, third)) { a, b -> a != b }
            val first = d.personalities()
            first.keys shouldBe setOf(bot.id, other.id, third.id)
            d.poll(2000, listOf(human, bot, other, third)) { a, b -> a != b }
            d.personalities() shouldBe first
            // The same three names under other ids, in another match: the same three people.
            val again = engine(BotPersonality.RANDOM, seed + 100)
            val moved = listOf(bot, other, third).map { it.copy(id = UUID(7, it.id.leastSignificantBits)) }
            again.poll(1000, listOf(human) + moved) { a, b -> a != b }
            moved.map { again.personalities()[it.id] } shouldBe listOf(bot, other, third).map { first[it.id] }
        }
        for (i in 1..200) everybody += BotCharacter.of("Player$i").nature
        everybody shouldBe setOf(BotPersonality.TOXIC, BotPersonality.SALTY, BotPersonality.CHILL)
    }
    "a bot's level, how much it talks and its turn of phrase go with its name" {
        for (name in listOf("Dino", "cqsey", "VirtualTheProto", "HotCold")) {
            BotCharacter.of(name).nature shouldBe BotCharacter.of(name.uppercase()).nature
            BotCharacter.of(name).chatty shouldBe BotCharacter.of(name.lowercase()).chatty
            for (difficulty in BotDifficulty.entries) {
                val level = BotIdentity.levelOf(name, difficulty)
                (level in BotIdentity.levels(difficulty)) shouldBe true
                BotIdentity.levelOf(name, difficulty) shouldBe level
            }
            BotVoice.of(name).bro shouldBe BotVoice.of(name).bro
        }
        // The talkative are picked to speak more often than the quiet, and nobody is never picked.
        val quiet = (1..400).map { "Player$it" }.first { BotCharacter.of(it).chatty < 0.5 }
        val loud = (1..400).map { "Player$it" }.first { BotCharacter.of(it).chatty >= 1.0 }
        val random = Random(4)
        val picks = (1..2000).map { BotCharacter.pick(listOf(quiet, loud), random) { it }!! }
        (picks.count { it == loud } > picks.count { it == quiet } * 2) shouldBe true
        (picks.count { it == quiet } > 0) shouldBe true
    }

    // ---------------------------------------------------------------- somebody it has played before

    fun known(killedMe: Int = 0, killedThem: Int = 0, theyWon: Boolean = false, iWon: Boolean = false, streak: Int = 0,
              mouthed: Boolean = false) = Acquaintance(1, 1_000_000, killedMe, killedThem, theyWon, iWon, streak, mouthed, false, "Skylands")
    fun BotDialogue.remembering(a: Acquaintance?, by: String = "Dino") = also {
        it.recall = { name, player -> if (name.equals(by, true) && player == human.id) a else null }
        it.clock = { 1_000_000 + 60_000 }
    }

    "a bot that was farmed last game says so when the next one starts, by name and with the number, and goes for them first" {
        for (seed in 0..30) for (mood in listOf(BotPersonality.TOXIC, BotPersonality.SALTY)) {
            val d = engine(mood, seed).remembering(known(killedMe = 4))
            d.start(1000)
            val line = d.poll(4000, listOf(human, bot, other)) { a, b -> a != b }!!
            line.speaker shouldBe bot.id
            line.text shouldSay "jacob|chicken|ur first|payback|4 times|not this time|not again|never again|not happening|twice"
            d.listen(4250, 9000, listOf(human, bot, other))
            d.grudge(bot.id, 9000) shouldBe human.id
            // And it lets go of it again: it does not chase them for the whole match on the strength of one line.
            d.grudge(bot.id, 4000 + BotDialogue.OWED_MS + 5000) shouldBe null
        }
    }
    "a chill bot remembers too, and does not make a feud of it" {
        for (seed in 0..30) {
            val d = engine(BotPersonality.CHILL, seed).remembering(known(killedMe = 4))
            d.start(1000)
            d.poll(4000, listOf(human, bot)) { a, b -> a != b }!!.text.shouldNotContain("ur first")
            d.grudge(bot.id, 5000) shouldBe null
        }
    }
    "what a bot says of last game is what happened in it" {
        val said = mutableSetOf<String>()
        for (seed in 0..60) {
            val d = engine(BotPersonality.TOXIC, seed).remembering(known(theyWon = true, streak = 3))
            d.start(1000)
            said += d.poll(4000, listOf(human, bot)) { a, b -> a != b }!!.text
        }
        said.any { it.contains("3 in a row") } shouldBe true
        said.forEach { it.shouldNotContain("killed me") }
        // Nobody it remembers: the ordinary start of a match.
        val d = engine().remembering(null)
        d.start(1000)
        d.poll(4000, listOf(human, bot)) { a, b -> a != b }!!.text.shouldNotContain("again")
    }
    "killing somebody who ran their mouth last game is answered once, and only by the bot that remembers" {
        for (seed in 0..20) {
            val d = engine(BotPersonality.TOXIC, seed).remembering(known(mouthed = true))
            d.death(human.id, bot.id, "Static Laser", 1000)
            d.poll(4000, listOf(human, bot, other)) { a, b -> a != b }!!.text shouldSay "trash|ez|saying|loud|type something|talk"
            d.death(human.id, bot.id, "Static Laser", 40_000)
            d.poll(43_000, listOf(human, bot, other)) { a, b -> a != b }?.text?.let { it.shouldNotContain("whos trash"); it.shouldNotContain("u were saying") }
            val stranger = engine(BotPersonality.TOXIC, seed).remembering(known(mouthed = true), by = "Fox")
            stranger.death(human.id, bot.id, "Static Laser", 1000)
            stranger.poll(4000, listOf(human, bot, other)) { a, b -> a != b }!!.text.let { it.shouldNotContain("whos trash"); it.shouldNotContain("u were saying") }
        }
    }
    "a bot walks out of a match only when the host allows it, rarely, one at most, and never a Chill one or one that is doing fine" {
        // Off: never, whatever happens to it.
        (0..500).none { RageQuit(Random(it), false).snaps(BotPersonality.SALTY, 3, 0, 1, 3) } shouldBe true
        // On: in about one match in five, however many chances a match gives, and one bot at most.
        var matches = 0
        for (seed in 0..1999) {
            val r = RageQuit(Random(seed), true)
            val quits = (1..12).count { r.snaps(BotPersonality.SALTY, 3, 0, 1, 2) }
            (quits <= 1) shouldBe true
            if (quits == 1) matches++
        }
        (matches in 330..470) shouldBe true
        for (seed in 0..500) {
            RageQuit(Random(seed), true).snaps(BotPersonality.CHILL, 3, 0, 1, 3) shouldBe false
            // Level on kills, dead only once, or already out of the match: not somebody who quits.
            RageQuit(Random(seed), true).snaps(BotPersonality.SALTY, 2, 2, 2, 1) shouldBe false
            RageQuit(Random(seed), true).snaps(BotPersonality.SALTY, 1, 0, 3, 1) shouldBe false
            RageQuit(Random(seed), true).snaps(BotPersonality.TOXIC, 4, 0, 0, 4) shouldBe false
        }
    }
    "a bot that walks out says so in its own voice, unless the bots are Silent, and somebody remarks on it" {
        for (seed in 0..20) {
            val last = engine(BotPersonality.SALTY, seed).rageLine(bot.id, human.id, listOf(human, bot, other), 5000)!!
            last.speaker shouldBe bot.id
            last.urgent shouldBe true
            last.text shouldSay "done|out|quit|leaving|bye"
            last.text shouldBe BotSlang.style(last.text)
            val after = engine(BotPersonality.TOXIC, seed).quitReaction(bot, listOf(human, other), 9000)!!
            after.speaker shouldBe other.id
            after.text shouldSay "left|quit|bye|gone|cya|one down"
        }
        engine(BotPersonality.SILENT).rageLine(bot.id, human.id, listOf(human, bot), 5000) shouldBe null
        engine(BotPersonality.SILENT).quitReaction(bot, listOf(human, other), 9000) shouldBe null
    }
    "when a match is over a few of the bots say so one after another, the quiet ones included only in a test" {
        for (seed in 0..30) {
            val d = engine(BotPersonality.CHILL, seed)
            val facts = listOf(human, bot, other, third, BotDialogue.Fighter(UUID(0, 5), "Pebble", "Pig", 0, true))
            val wave = d.finishAll(setOf(human.id), facts, 60_000, "Skylands", setOf(third.id))
            (wave.size in 2..BotDialogue.MAX_PARTING) shouldBe true
            wave.first().afterMs shouldBe 0
            wave.drop(1).forEach { (it.afterMs in 500..1900) shouldBe true }
            wave.map { it.reply.speaker }.distinct().size shouldBe wave.size
            wave.forEach { it.reply.text shouldBe BotSlang.style(it.reply.text); (it.reply.speaker == human.id) shouldBe false }
        }
        // Silent: not a word, as before.
        engine(BotPersonality.SILENT).finishAll(setOf(human.id), listOf(human, bot, other), 60_000) shouldBe emptyList()
        // Somebody who is leaving may say so; nobody who is staying does.
        val goodbyes = (0..80).flatMap { seed -> engine(BotPersonality.CHILL, seed).finishAll(setOf(human.id), listOf(human, bot, other, third), 60_000, "", setOf(third.id)) }
            .filter { Regex("gtg|last one|gotta go|im off|cya").containsMatchIn(it.reply.text) }
        (goodbyes.isNotEmpty()) shouldBe true
        goodbyes.forEach { it.reply.speaker shouldBe third.id }
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
        // (Not by a part of the name that reads as an insult.)
        BotSlang.nick("AnIdiotInATree") shouldBe "tree"
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
