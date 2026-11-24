package dev.betrix.superSmashMobsBrawl.bots

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** The language model's side of the bots' chat: what it is told, what of its answers may be said, and what happens when it fails. */
class ChatModelTest : StringSpec({
    val others = listOf("Jacob1Jacob2", "HotCold")
    val filter = ChatFilter(listOf("badword", "very bad phrase", "suck"))
    fun said(raw: String, facts: String = "") = filter.check(raw, "KinTheInfinite", others, facts)
    fun line(raw: String, facts: String = "") = (said(raw, facts) as? ChatFilter.Line)?.text

    // ---------------------------------------------------------------- what may be said

    "what the model writes is typed the way players type" {
        line("Lucky missile, lmao.") shouldBe "lucky missile lmao"
        line("\"gg\"") shouldBe "gg"
        line("  nah that's hacks!!  ") shouldBe "nah thats hacks"
        line("gg 😂") shouldBe "gg"
        line("wait what??") shouldBe "wait what??"
        line("??") shouldBe "??"
        // (A bare number is an answer, when it is one the bot was told: "since when" to somebody who started in 2016.)
        line("2016", "you have played this game since 2016") shouldBe "2016"
        said("2019", "you have played this game since 2016").shouldBeInstanceOf<ChatFilter.Rejected>()
        said(":) :) ;;").shouldBeInstanceOf<ChatFilter.Rejected>()
        // (Only the first line: a model that goes on to explain itself is cut off there.)
        line("ez\n(This is what the player would say.)") shouldBe "ez"
    }
    "a line that begins with the speaker's own name is that line; one that begins with somebody else's is not theirs to say" {
        line("KinTheInfinite: ez") shouldBe "ez"
        line("kintheinfinite > sit chicken") shouldBe "sit chicken"
        said("HotCold: why me").shouldBeInstanceOf<ChatFilter.Rejected>()
    }
    "nothing, a dash or a stage direction is the bot saying nothing" {
        listOf("-", " - ", "", "\n\n", "...", "(nothing)", "(silence)", "—").forEach { said(it) shouldBe ChatFilter.Silent }
    }
    "an essay, a model talking as itself, a link and a made-up number are not said" {
        said("well i think that was a really unlucky moment for me because the hitbox on that move is way bigger than it looks").shouldBeInstanceOf<ChatFilter.Rejected>()
        said("As an AI language model I cannot do that").shouldBeInstanceOf<ChatFilter.Rejected>()
        said("im just an assistant lol").shouldBeInstanceOf<ChatFilter.Rejected>()
        said("join discord.gg/abc").shouldBeInstanceOf<ChatFilter.Rejected>()
        said("add me www.example.com").shouldBeInstanceOf<ChatFilter.Rejected>()
        said("thats 5 kills on u").shouldBeInstanceOf<ChatFilter.Rejected>()
        line("thats 5 kills on u", "He has killed you 5 times.") shouldBe "thats 5 kills on u"
        // ("1v1" is a challenge, not a count.)
        line("1v1 me then") shouldBe "1v1 me then"
    }
    "words that are not said here are not said, and words that only begin like one are" {
        said("u badword").shouldBeInstanceOf<ChatFilter.Rejected>()
        said("ur such a badwording player").shouldBeInstanceOf<ChatFilter.Rejected>()
        said("that was a very bad phrase").shouldBeInstanceOf<ChatFilter.Rejected>()
        // Never, whatever list is loaded: telling somebody to hurt themselves, and anybody's family.
        said("kys").shouldBeInstanceOf<ChatFilter.Rejected>()
        said("go die lol").shouldBeInstanceOf<ChatFilter.Rejected>()
        said("ur mom is a chicken").shouldBeInstanceOf<ChatFilter.Rejected>()
        ChatFilter().check("kill urself", "Kin", others, "").shouldBeInstanceOf<ChatFilter.Rejected>()
        // Mild enough for these lobbies even though the list has it; and a word that only begins like one
        // of the few that are always out ("tard", "trans") is a word like any other.
        line("u suck at this") shouldBe "u suck at this"
        line("im so tardy") shouldBe "im so tardy"
        line("that didnt transfer") shouldBe "that didnt transfer"
        line("hello") shouldBe "hello"
        line("u died lol") shouldBe "u died lol"
        line("skill issue") shouldBe "skill issue"
        // Name-calling is not talk about the game; grumbling about the game is.
        said("just press space u idiot").shouldBeInstanceOf<ChatFilter.Rejected>()
        said("ur so dumb lol").shouldBeInstanceOf<ChatFilter.Rejected>()
        said("7v1 is the point u dummy").shouldBeInstanceOf<ChatFilter.Rejected>()
        line("this is so dumb") shouldBe "this is so dumb"
        line("map is fine its just u thats bad") shouldBe "map is fine its just u thats bad"
        // The words a model pads a line with and these players do not type are taken out of it.
        line("static laser is literally broken") shouldBe "static laser is broken"
        line("actually i finally hit something") shouldBe "i finally hit something"
    }
    "what a player typed is looked at before a model is shown it" {
        (filter.banned("you are a BADWORD") != null) shouldBe true
        filter.banned("gg that was close") shouldBe null
    }

    // ---------------------------------------------------------------- what the model is told

    val me = BotDialogue.Fighter(UUID(0, 2), "KinTheInfinite", "Wither Skeleton", 2, true)
    val jacob = BotDialogue.Fighter(UUID(0, 1), "Jacob1Jacob2", "Chicken", 3, false)
    val hot = BotDialogue.Fighter(UUID(0, 3), "HotCold", "Magma Cube", 0, true)

    "the model is told the mode, the sides and everybody's lives" {
        val ffa = ChatPrompt.match(me, listOf(jacob, me, hot), "Lava Spire")
        ffa shouldContain "free-for-all on Lava Spire, 3 players"
        ffa shouldContain "you 2, Jacob1Jacob2 (chicken) 3"
        ffa shouldContain "Out of the match: HotCold"
        val sided = ChatPrompt.match(me.copy(side = "ALL"), listOf(jacob.copy(side = "ALONE"), me.copy(side = "ALL"), hot.copy(side = "ALL", lives = 4)), "Astron")
        sided shouldContain "2 against 1"
        sided shouldContain "Your team: you, HotCold (magma cube)"
        sided shouldContain "The other team: Jacob1Jacob2 (chicken)"
        ChatPrompt.match(me.copy(lives = 0), listOf(jacob, me.copy(lives = 0)), "") shouldContain "You are out of the match too"
    }
    "the model is told who it is, the same every time, and what just happened" {
        val who = ChatPrompt.persona("KinTheInfinite", "Wither Skeleton", BotPersonality.TOXIC)
        who shouldBe ChatPrompt.persona("kintheinfinite", "Wither Skeleton", BotPersonality.TOXIC).copy(name = "KinTheInfinite")
        val prompt = ChatPrompt.user(who, ChatPrompt.match(me, listOf(jacob, me), "Lava Spire"), listOf(ChatPrompt.Said("Jacob1Jacob2", "L KinTheInfinite")),
            BotDialogue.Brief("Jacob1Jacob2 just killed you with chicken missile."), "lucky missile")
        prompt shouldContain "You are KinTheInfinite. Kit: wither skeleton. You are toxic"
        prompt shouldContain "since ${who.since}"
        prompt shouldContain "Jacob1Jacob2: L KinTheInfinite"
        prompt shouldContain "What just happened: Jacob1Jacob2 just killed you with chicken missile."
        prompt shouldContain "lucky missile"
        prompt shouldContain "Write KinTheInfinite's chat message."
        // In the lobby nobody has a kit yet.
        ChatPrompt.user(who.copy(kit = ""), ChatPrompt.lobby(me, listOf(jacob, me), "Jacob1Jacob2"), emptyList(), BotDialogue.Brief("x"), null).let {
            it.shouldNotContain("Kit:"); it shouldContain "with Jacob1Jacob2. Jacob1Jacob2 is the one who starts"; it shouldContain "(nothing yet)"
        }
    }

    "the worked examples the model is shown are lines a bot may say, with no number that is not in their own facts" {
        (ChatPrompt.SHOTS.size >= 15) shouldBe true
        for ((asked, answer) in ChatPrompt.SHOTS) {
            val name = Regex("^You are (\\w+)\\.").find(asked)!!.groupValues[1]
            val verdict = ChatFilter().check(answer, name, listOf("Alex", "Dino", "Fox", "Mango", "Pebble"), asked)
            if (answer == "-") verdict shouldBe ChatFilter.Silent else verdict shouldBe ChatFilter.Line(answer)
            asked shouldContain "What just happened: "
            asked shouldContain "Write $name's chat message."
        }
        // They are in the same form as a real question, or they teach nothing.
        val real = ChatPrompt.user(ChatPrompt.persona("KinTheInfinite", "Wither Skeleton", BotPersonality.TOXIC), ChatPrompt.match(me, listOf(jacob, me), "Desert"),
            emptyList(), BotDialogue.Brief("x"), null)
        fun shape(text: String) = text.lines().map { it.substringBefore(':').substringBefore(' ') }.filter { it in setOf("You", "About", "Match", "Lives", "Recent", "What", "Write") }
        shape(ChatPrompt.SHOTS.first().first) shouldBe shape(real)
    }

    // ---------------------------------------------------------------- the model itself, here a stand-in for it

    fun reply(content: String) = """{"choices":[{"index":0,"message":{"role":"assistant","content":${kotlinx.serialization.json.JsonPrimitive(content)}}}]}"""

    "an answer is read out of the server's reply, and anything else is no answer" {
        ChatModel.parse(reply("lucky missile lmao")) shouldBe "lucky missile lmao"
        ChatModel.parse("""{"error":{"message":"loading model"}}""") shouldBe null
        ChatModel.parse("not json") shouldBe null
    }
    "a model that answers is used, and one that is slow, down or busy is not waited for" {
        var delay = 0L
        var seen = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            seen = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            Thread.sleep(delay)
            val body = reply("nah thats hacks").toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/health") { exchange -> exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        try {
            val model = ChatModel("http://127.0.0.1:${server.address.port}", timeoutMs = 400, most = 1)
            model.healthy() shouldBe true
            model.complete("rules", "You are Kin.").get(5, TimeUnit.SECONDS) shouldBe "nah thats hacks"
            seen shouldContain "\"role\":\"system\""
            seen shouldContain "You are Kin."
            // Too slow: the scripted line is said instead, and the bot does not stand there waiting.
            delay = 1500
            val slow = model.complete("rules", "You are Kin.")
            // One at a time was asked for here: a second line asked for meanwhile is not made.
            model.complete("rules", "another").get(1, TimeUnit.SECONDS) shouldBe null
            slow.get(5, TimeUnit.SECONDS) shouldBe null
            delay = 0
            model.complete("rules", "You are Kin.").get(5, TimeUnit.SECONDS) shouldBe "nah thats hacks"
        } finally { server.stop(0) }
        val gone = ChatModel("http://127.0.0.1:${server.address.port}", timeoutMs = 400)
        gone.healthy() shouldBe false
        gone.complete("rules", "You are Kin.").get(5, TimeUnit.SECONDS) shouldBe null
    }

    // ---------------------------------------------------------------- which model, and what it is let write

    "the big model gives way when the video card fills up, and comes back only when there is room to spare" {
        val service = dev.betrix.superSmashMobsBrawl.services.ChatModelService
        // Loaded, and a game has filled the card: short. Loaded with a gigabyte free: not.
        service.short(free = 300, bigNeed = 7000, asleep = false) shouldBe true
        service.short(free = 1200, bigNeed = 7000, asleep = false) shouldBe false
        // Unloaded after a quiet while, and a game started meanwhile has taken the room it would need to load again.
        service.short(free = 6800, bigNeed = 7000, asleep = true) shouldBe true
        service.short(free = 11_000, bigNeed = 7000, asleep = true) shouldBe false
        // The small one is in (3.3 GB of the 16): the big one fits with 1.5 GB to spare only if 5.2 GB are free now.
        service.room(free = 5300, bigNeed = 7000, littleNeed = 3300, asleep = false, reserve = 1500) shouldBe true
        service.room(free = 4000, bigNeed = 7000, littleNeed = 3300, asleep = false, reserve = 1500) shouldBe false
        // (Unloaded itself, it frees nothing by going: all of the big one's memory has to be free already.)
        service.room(free = 6800, bigNeed = 7000, littleNeed = 3300, asleep = true, reserve = 1500) shouldBe false
        service.room(free = 9000, bigNeed = 7000, littleNeed = 3300, asleep = true, reserve = 1500) shouldBe true
    }
    "the settings file says which models there are and how much each writes; without it there is no model" {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "ssm-chat-model-test-${System.nanoTime()}").also { it.mkdirs() }
        try {
            val settings = dev.betrix.superSmashMobsBrawl.services.ChatModelService.Settings
            settings.read(java.io.File(dir, "none.properties")).enabled shouldBe false
            val file = java.io.File(dir, "chat-model.properties")
            file.writeText("enabled=true\nmodel=C:/models/big.gguf\nsmall-model=C:/models/small.gguf\nscope=all\nreserve-mb=2000\n")
            val read = settings.read(file)
            read.enabled shouldBe true
            read.model shouldBe "C:/models/big.gguf"
            read.smallModel shouldBe "C:/models/small.gguf"
            read.scope shouldBe "all"
            // (What is not in the file is as careful as it can be: the small model only answers what the script cannot.)
            read.smallScope shouldBe "answers"
            read.reserveMb shouldBe 2000
            read.url shouldBe "http://127.0.0.1:8090"
        } finally { dir.deleteRecursively() }
    }
    // ---------------------------------------------------------------- Bot Chat, in Misc Settings

    "the word filter switched off lets swearing through, and nothing else" {
        val list = listOf("shit", "fuck", "slurword", "very bad phrase")
        val on = ChatFilter(list)
        val off = ChatFilter(list, swearing = true)
        fun ChatFilter.says(raw: String) = (check(raw, "Kin", others, "") as? ChatFilter.Line)?.text
        // On: no swearing, with letters left out or not.
        on.says("oh shit i missed") shouldBe null
        on.says("fucking lag") shouldBe null
        on.says("sht") shouldBe null
        // Off: at the game, at luck, at nobody.
        off.says("oh shit i missed") shouldBe "oh shit i missed"
        off.says("fucking lag") shouldBe "fucking lag"
        // But a swear word turned on a person is name-calling, a slur is a slur, and the rest was never the filter's to give up.
        off.says("u fucker") shouldBe null
        off.says("ur a shithead") shouldBe null
        off.says("slurword") shouldBe null
        off.says("that was a very bad phrase") shouldBe null
        off.says("kys") shouldBe null
        off.says("ur mom plays better") shouldBe null
        off.says("just press space u idiot") shouldBe null
        // And the model is told so: it is not told to swear with the filter on.
        ChatPrompt.system(swearing = false) shouldContain "no swear words spelled out"
        ChatPrompt.system(swearing = true).shouldNotContain("no swear words spelled out")
        ChatPrompt.system(swearing = true) shouldContain "never slurs"
    }
    "what is picked in Bot Chat is kept, and the owner's own words are words the bots do not say" {
        val service = dev.betrix.superSmashMobsBrawl.services.ChatModelService
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "ssm-bot-chat-test-${System.nanoTime()}").also { it.mkdirs() }
        val logger = java.util.logging.Logger.getLogger("test")
        try {
            service.setup(dir, logger)
            // A server with no model on it: the script is all there is to pick, and picking a model does nothing.
            service.writer shouldBe dev.betrix.superSmashMobsBrawl.services.ChatModelService.Writer.AUTO
            service.has(dev.betrix.superSmashMobsBrawl.services.ChatModelService.Writer.SCRIPT) shouldBe true
            service.has(dev.betrix.superSmashMobsBrawl.services.ChatModelService.Writer.BIG) shouldBe false
            service.has(dev.betrix.superSmashMobsBrawl.services.ChatModelService.Writer.AUTO) shouldBe false
            service.pick(dev.betrix.superSmashMobsBrawl.services.ChatModelService.Writer.BIG)
            service.writer shouldBe dev.betrix.superSmashMobsBrawl.services.ChatModelService.Writer.AUTO
            service.pick(dev.betrix.superSmashMobsBrawl.services.ChatModelService.Writer.SCRIPT)
            service.reach() shouldBe null
            // A word of the owner's own, as typed; what is not a word is not taken.
            service.addWord("  Noob  Tube ") shouldBe "noob tube"
            service.addWord("Bozo") shouldBe "bozo"
            service.addWord("no!") shouldBe null
            service.addWord("x") shouldBe null
            service.addWord("a".repeat(40)) shouldBe null
            service.ownWords() shouldBe listOf("bozo", "noob tube")
            (service.banned("what a bozo") != null) shouldBe true
            (service.banned("u noob tube") != null) shouldBe true
            // With the filter off the owner's words are off with it; back on, they are back.
            service.filtering(false)
            service.banned("what a bozo") shouldBe null
            (service.banned("kys") != null) shouldBe true
            service.filtering(true)
            (service.banned("what a bozo") != null) shouldBe true
            service.removeWord("bozo")
            service.banned("what a bozo") shouldBe null
            service.filtering(false)
            // The server stops and starts: everything is as it was left.
            service.shutdown()
            service.setup(dir, logger)
            service.writer shouldBe dev.betrix.superSmashMobsBrawl.services.ChatModelService.Writer.SCRIPT
            service.filtering shouldBe false
            service.ownWords() shouldBe listOf("noob tube")
        } finally {
            // (It is one service for the whole server: left as a server nobody has touched.)
            service.shutdown()
            dir.deleteRecursively()
            service.setup(java.io.File(dir, "nothing-here"), logger)
        }
        service.filtering shouldBe true
        service.ownWords() shouldBe emptyList()
    }
    "a model writes names out in full; the bot that says the line calls people what it calls them" {
        val service = dev.betrix.superSmashMobsBrawl.services.ChatModelService
        val others = listOf("KinTheInfinite", "Jacob1Jacob2", "HotCold")
        // (Whether a given bot shortens names is its own habit; over many lines some do and some do not.)
        val lines = (0 until 200).map { service.called("chill kintheinfinite lol", "Speaker${it % 20}", others, Random(it)) }.toSet()
        lines shouldBe setOf("chill kintheinfinite lol", "chill kin lol")
        // Only whole names, and only people who are there.
        service.called("kintheinfinitely long", "Speaker1", others, Random(1)) shouldBe "kintheinfinitely long"
        (0 until 50).forEach { service.called("gg hotcold", "Speaker$it", others, Random(it)).let { line -> (line == "gg hotcold" || line == "gg hot") shouldBe true } }
    }

    // ---------------------------------------------------------------- the script, for the model to work from

    val human = BotDialogue.Fighter(UUID(0, 1), "Jacob", "Chicken", 4, false)
    val bot = BotDialogue.Fighter(UUID(0, 2), "Dino", "Guardian", 3, true)
    val other = BotDialogue.Fighter(UUID(0, 3), "Fox", "Creeper", 2, true)
    fun engine(mode: BotPersonality = BotPersonality.TOXIC, seed: Int = 2) = BotDialogue(Random(seed), LineMemory(), 1.0).also { it.mode = mode }

    "with a model every line comes with what it is about, in facts and nothing else" {
        val d = engine().also { it.wordsmith = BotDialogue.Brief.Kind.REMARK; it.tally = { a, b -> if (a == human.id && b == bot.id) 2 else 0 } }
        d.death(bot.id, human.id, "Chicken Missile", 1000)
        val reply = d.poll(4000, listOf(human, bot.copy(lives = 2))) { a, b -> a != b }!!
        reply.speaker shouldBe bot.id
        (reply.text.isNotEmpty()) shouldBe true
        reply.brief!!.event shouldContain "Jacob just killed you with chicken missile."
        reply.brief!!.event shouldContain "You have 2 lives left."
        reply.brief!!.event shouldContain "Kills between you two this match: Jacob 2, you 0."
        reply.brief!!.open shouldBe false
        // Without one nothing changes: the line as scripted, and no brief.
        val plain = engine().also { it.death(bot.id, human.id, "Chicken Missile", 1000) }.poll(4000, listOf(human, bot)) { a, b -> a != b }!!
        plain.brief shouldBe null
    }
    "something typed that the script has no line for is handed to the model, and without a model it is left alone" {
        val asked = "do u guys think this map is too small"
        engine().also { it.chat(human.id, asked, 1000) }.poll(4000, listOf(human, bot)) { a, b -> a != b } shouldBe null
        val d = engine().also { it.wordsmith = BotDialogue.Brief.Kind.REMARK; it.chat(human.id, asked, 1000) }
        val reply = d.poll(4000, listOf(human, bot)) { a, b -> a != b }!!
        reply.text shouldBe ""
        reply.brief!!.open shouldBe true
        reply.brief!!.event shouldContain "Jacob typed \"$asked\" in chat."
    }
    "a small model is let no further than what the script cannot do: the script's own lines stay as written" {
        // Only answers to what the script has no line for (the default): a death is remarked on as scripted, with no brief...
        val d = engine().also { it.wordsmith = BotDialogue.Brief.Kind.UNSURE }
        d.death(bot.id, human.id, "Chicken Missile", 1000)
        d.poll(4000, listOf(human, bot)) { a, b -> a != b }!!.brief shouldBe null
        // ...and so is a taunt the script knows how to answer...
        d.chat(human.id, "ez dino", 20_000)
        d.poll(23_000, listOf(human, bot)) { a, b -> a != b }!!.let { it.brief shouldBe null; (it.text.isNotEmpty()) shouldBe true }
        // ...while a question it has never heard of goes to the model.
        d.chat(human.id, "dino do u think pigs can fly", 40_000)
        d.poll(43_000, listOf(human, bot)) { a, b -> a != b }!!.brief!!.kind shouldBe BotDialogue.Brief.Kind.UNSURE
        d.chat(human.id, "do u guys think pigs can fly", 60_000)
        d.poll(63_000, listOf(human, bot)) { a, b -> a != b }!!.brief!!.kind shouldBe BotDialogue.Brief.Kind.OPEN
        // One step further and it words the answers to what players type as well, but still not the bots' own remarks.
        val chat = engine().also { it.wordsmith = BotDialogue.Brief.Kind.ANSWER }
        chat.chat(human.id, "ez dino", 1000)
        chat.poll(4000, listOf(human, bot)) { a, b -> a != b }!!.brief!!.kind shouldBe BotDialogue.Brief.Kind.ANSWER
        chat.death(bot.id, human.id, "Chicken Missile", 20_000)
        chat.poll(23_000, listOf(human, bot)) { a, b -> a != b }!!.brief shouldBe null
        // And "gg" back is said as scripted whatever the model is let.
        val all = engine().also { it.wordsmith = BotDialogue.Brief.Kind.REMARK }
        all.chat(human.id, "gg", 1000)
        all.poll(4000, listOf(human, bot)) { a, b -> a != b }!!.brief shouldBe null
    }
    "what one bot answers another is said as written by hand, unless a model is let that far" {
        // Every bot toxic: Dino is killed by Fox and has something to say about it, and Fox answers back as often as not.
        fun answers(reach: BotDialogue.Brief.Kind): List<BotDialogue.Reply> = (0..60).flatMap { seed ->
            val d = engine(seed = seed).also { it.wordsmith = reach }
            d.death(bot.id, other.id, "Explode", 1000)
            val facts = listOf(human, bot.copy(lives = 2), other)
            val first = d.poll(4000, facts) { a, b -> a != b } ?: return@flatMap emptyList()
            // (What Dino says of its own death is its own remark, and a model words that.)
            first.brief!!.kind shouldBe BotDialogue.Brief.Kind.REMARK
            (4500L..20_000L step 250).mapNotNull { t -> d.poll(t, facts) { a, b -> a != b } }.filter { it.speaker == other.id }
        }
        val written = answers(BotDialogue.Brief.Kind.REMARK)
        (written.size > 10) shouldBe true
        written.forEach { it.brief shouldBe null; (it.text.isNotEmpty()) shouldBe true }
        val worded = answers(BotDialogue.Brief.Kind.BANTER)
        worded.any { it.brief?.kind == BotDialogue.Brief.Kind.BANTER } shouldBe true
        // "all" in the settings file is all a bot says of the match and to a player; "banter" is this as well.
        (BotDialogue.Brief.Kind.BANTER > BotDialogue.Brief.Kind.REMARK) shouldBe true
    }
    "a bot that has just been talking leaves the next line to somebody else" {
        // The user's match of 2026-11-24: the bot that kept dying said 7 of the 11 lines of it, a
        // line for every death. Dino dies to the player every 40 seconds for five minutes.
        val mango = BotDialogue.Fighter(UUID(0, 7), "Mango", "Wolf", 3, true)
        val facts = listOf(human, bot, other, mango)
        // (How many lines Dino says, and how many times out of those it answers when it is then spoken to by name.)
        fun talk(tire: Double): Pair<Int, Int> {
            var lines = 0; var answers = 0
            for (seed in 0..120) {
                val d = BotDialogue(Random(seed), LineMemory(), 0.65, tire).also { it.mode = BotPersonality.CHILL }
                var t = 1000L
                while (t < 300_000L) {
                    if (t % 40_000L == 1000L) d.death(bot.id, human.id, "Chicken Missile", t)
                    if (d.poll(t, facts) { a, b -> a != b }?.speaker == bot.id) lines++
                    t += 250
                }
                d.chat(human.id, "dino what kit is that", t)
                if ((t..t + 12_000L step 250).any { d.poll(it, facts) { a, b -> a != b }?.speaker == bot.id }) answers++
            }
            return lines to answers
        }
        val (before, answeredBefore) = talk(1.0)
        val (after, answeredAfter) = talk(BotDialogue.TIRE)
        println("lines by the bot that keeps dying: $before as it was, $after now; answers to its name: $answeredBefore as it was, $answeredAfter now")
        // It has a good deal less to say about dying yet again...
        (before > 300) shouldBe true
        (after * 10 < before * 6) shouldBe true
        (after > 100) shouldBe true
        // ...and answers to its name as it always did, however much it has just said.
        (answeredBefore > 100) shouldBe true
        (answeredAfter * 10 >= answeredBefore * 9) shouldBe true
    }
    "nobody is called he or she: a line that does it is not said" {
        val filter = ChatFilter()
        filter.check("finally got him", "Lyzardguy", listOf("Jacob"), "").shouldBeInstanceOf<ChatFilter.Rejected>()
        filter.check("she just spams missile", "Lyzardguy", listOf("Jacob"), "").shouldBeInstanceOf<ChatFilter.Rejected>()
        // (Words that only have those letters in them are words like any other.)
        filter.check("the chicken got there first", "Lyzardguy", listOf("Jacob"), "") shouldBe ChatFilter.Line("the chicken got there first")
        filter.check("finally got jacob", "Lyzardguy", listOf("Jacob"), "") shouldBe ChatFilter.Line("finally got jacob")
    }
    "a bot is told what it has said already, and a line that says it again is known for one" {
        val who = ChatPrompt.persona("Aoryuuu", "Slime", BotPersonality.SALTY)
        val brief = BotDialogue.Brief("Jacob just killed you with chicken missile. You have 1 life left.")
        val plain = ChatPrompt.user(who, "Match: free-for-all, 4 players.", emptyList(), brief, "why me")
        plain shouldNotContain "already typed"
        val told = ChatPrompt.user(who, "Match: free-for-all, 4 players.", emptyList(), brief, "why me", mine = listOf("i need a win", "this game is so rigged"))
        told shouldContain "You have already typed: \"i need a win\", \"this game is so rigged\"."
        told shouldContain "Do not say any of that again"
        // The four lines of the user's match were one complaint made four times over.
        val mine = listOf("i need a win", "this game is so rigged")
        ChatPrompt.stale("i just want one win for once", mine) shouldBe true
        ChatPrompt.stale("the game is just broken", mine) shouldBe true
        ChatPrompt.stale("bro i need a win", mine) shouldBe true
        // Something else is something else, whoever it is said to...
        ChatPrompt.stale("that missile goes through walls", mine) shouldBe false
        ChatPrompt.stale("jacob was on fire lol", listOf("jacob is just camping"), listOf("Jacob1Jacob2", "Aoryuuu")) shouldBe false
        ChatPrompt.stale("nice one jacob", listOf("gg jacob"), listOf("Jacob1Jacob2")) shouldBe false
        // (The same goes for what somebody else has just said: three bots blaming the lag in a row is one script talking.)
        ChatPrompt.stale("the lag was crazy man", listOf("lag must have been bad", "gg")) shouldBe true
        // ...and the one-word lines everybody types all match long are let be.
        ChatPrompt.stale("ez", listOf("ez")) shouldBe false
        ChatPrompt.stale("gg", listOf("gg", "wtf")) shouldBe false
        ChatPrompt.stale("wtf", emptyList()) shouldBe false
        // (The worked examples still answer their own questions with nothing they are told not to say.)
        ChatPrompt.SHOTS.count { "already typed" in it.first } shouldBe 1
    }
    "a bot spoken to by name answers even what it can make nothing of" {
        for (seed in 0..20) {
            val reply = engine(seed = seed).also { it.chat(human.id, "dino the window is open", 1000) }.poll(4000, listOf(human, bot, other)) { a, b -> a != b }!!
            reply.speaker shouldBe bot.id
            (Regex("^(\\?|what|wdym|huh|hm\\?|what \\w+)$").matches(reply.text)) shouldBe true
        }
        val d = engine().also { it.wordsmith = BotDialogue.Brief.Kind.REMARK; it.chat(human.id, "dino the window is open", 1000) }
        d.poll(4000, listOf(human, bot, other)) { a, b -> a != b }!!.brief!!.event shouldContain "in chat to you."
    }

    "bots that are out talk about the end of the match they are watching, and nobody else does" {
        val mango = BotDialogue.Fighter(UUID(0, 7), "Mango", "Wolf", 2, true)
        val facts = listOf(human, mango, bot.copy(lives = 0), other.copy(lives = 0))
        var spoke = 0
        for (seed in 0..40) {
            val d = engine(BotPersonality.CHILL, seed).also { it.wordsmith = BotDialogue.Brief.Kind.REMARK }
            d.watching(1000) shouldBe true
            // (Not again straight away: a remark now and then, not a commentary.)
            d.watching(1500) shouldBe false
            val reply = d.poll(4000, facts) { x, y -> x != y } ?: continue
            spoke++
            (reply.speaker == bot.id || reply.speaker == other.id) shouldBe true
            reply.brief!!.event shouldContain "Still in it: Jacob (chicken, 4 lives), Mango (wolf, 2 lives)."
        }
        (spoke > 30) shouldBe true
        // With everybody still in it there is nobody watching.
        engine().also { it.watching(1000) }.poll(4000, listOf(human, bot, other)) { x, y -> x != y } shouldBe null
    }

    // ---------------------------------------------------------------- what the user's own session showed

    "the answer to a taunt after a second kill does not call it one kill" {
        for (seed in 0..60) {
            val d = engine(seed = seed).also { it.tally = { a, b -> if (a == human.id && b == bot.id) 2 else 0 } }
            d.death(bot.id, human.id, "Chicken Missile", 900)
            d.chat(human.id, "L Dino again", 1000)
            val text = d.poll(4000, listOf(human, bot)) { a, b -> a != b }!!.text
            (Regex("\\bone kill\\b").containsMatchIn(text)) shouldBe false
        }
        // The first one still is.
        val first = (0..60).map { seed ->
            engine(seed = seed).also { it.tally = { _, _ -> 1 }; it.death(bot.id, human.id, "Attack", 900); it.chat(human.id, "L Dino", 1000) }
                .poll(4000, listOf(human, bot)) { a, b -> a != b }!!.text
        }
        (first.count { "one kill" in it } > 10) shouldBe true
    }
    "a run of kills is remarked on by those it is done to, and a taunt in the middle of it is not answered with one kill" {
        val bots = (0 until 6).map { BotDialogue.Fighter(UUID(5, it.toLong()), "Bot$it", "Zombie", 3, true) }
        val facts = listOf(human) + bots
        var remarked = 0
        for (seed in 0..80) {
            val d = engine(seed = seed)
            bots.take(4).forEachIndexed { i, b -> d.death(b.id, human.id, "Attack", 1000L + i * 4000) }
            val said = (13_000L..22_000L step 250).mapNotNull { d.poll(it, facts) { a, b -> a != b } }
            if (said.any { Regex("hack|cheat|kill aura|legit|in a row|stop (jacob|chicken)|kill (jacob|chicken)|not dead|reported|wtf").containsMatchIn(it.text) }) remarked++
            said.forEach { (Regex("\\bone kill\\b").containsMatchIn(it.text)) shouldBe false }
        }
        (remarked > 60) shouldBe true
        // Dying ends a run: three kills, a death, and one more are not four in a row.
        val d = engine()
        bots.take(3).forEachIndexed { i, b -> d.death(b.id, human.id, "Attack", 1000L + i * 2000) }
        d.death(human.id, bots[4].id, "Attack", 8000)
        d.poll(11_000, facts) { a, b -> a != b }
        d.death(bots[3].id, human.id, "Attack", 30_000)
        (30_250L..40_000L step 250).mapNotNull { d.poll(it, facts) { a, b -> a != b } }.forEach { it.text.shouldNotContain("in a row") }
    }
    "the same word typed straight after every kill is called a macro, once" {
        val bots = (0 until 6).map { BotDialogue.Fighter(UUID(5, it.toLong()), "Bot$it", "Zombie", 3, true) }
        val facts = listOf(human) + bots
        var called = 0
        for (seed in 0..40) {
            val d = engine(seed = seed)
            val said = ArrayList<String>()
            // Three kills a while apart (so it is no run), each with an "L name" in the same second.
            bots.take(3).forEachIndexed { i, b ->
                val t = 1000L + i * 60_000
                d.death(b.id, human.id, "Attack", t)
                d.chat(human.id, "L ${b.name}", t + 400)
                (t + 500..t + 12_000 step 250).mapNotNull { d.poll(it, facts) { x, y -> x != y } }.forEach { said += it.text }
            }
            if (said.count { Regex("macro|auto l|automatic|type that").containsMatchIn(it) } == 1) called++
        }
        (called > 30) shouldBe true
        // Typed by hand, seconds after the kill: nobody calls that a macro.
        val d = engine()
        val said = ArrayList<String>()
        bots.take(4).forEachIndexed { i, b ->
            val t = 1000L + i * 60_000
            d.death(b.id, human.id, "Attack", t)
            d.chat(human.id, "L ${b.name}", t + 4000)
            (t + 4100..t + 16_000 step 250).mapNotNull { d.poll(it, facts) { x, y -> x != y } }.forEach { said += it.text }
        }
        said.forEach { it.shouldNotContain("macro"); it.shouldNotContain("auto l") }
    }
})
