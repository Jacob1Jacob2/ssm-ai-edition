package dev.betrix.superSmashMobsBrawl.bots

import java.util.UUID
import kotlin.random.Random

/** How bot chat is typed: all lowercase, no commas, apostrophes or periods, the way players type mid-fight. */
object BotSlang {
    private val dropped = Regex("[,.'’`]")
    private val spaces = Regex("\\s+")

    /** Applied to every line a bot sends, so no line can slip through with proper grammar. */
    fun style(text: String): String = spaces.replace(dropped.replace(text.lowercase(), ""), " ").trim()

    /**
     * How many server ticks a line takes to type, during which the bot stands still: a quick "ez"
     * is under half a second, a full sentence about two seconds.
     */
    fun typingTicks(text: String): Int = (6 + text.length * 6 / 5).coerceIn(8, 44)

    /** Trash talk, as players type it (matched against a line in lower case). */
    const val INSULT = "\\b(ez+|easy|trash|bad|suck|sucks|noob|nub|cope|skill issue|washed|loser|bozo|garbage|dogwater|get good|git gud|mad|cry|lmao|lmfao)\\b|" +
        "^l+(?:\\s|$)|\\b(shut up|stfu|you lost|u lost|you'?re dead|ur dead|i win|i won|winning)\\b"
    /** Calling somebody a cheater. */
    const val CHEATS = "\\b(hack|hacks|hacker|hacking|cheat|cheats|cheater|cheating|aimbot|reach|kill ?aura|client)\\b"
    /** Being friendly. */
    const val NICE = "\\b(gg|ggs|good game|gf|good fight|wp|gl|glhf|good luck|ns|nice|sorry|sry|my bad|mb)\\b"

    /** Does [text] (in lower case) call somebody named [name] by name, or by the short form people would use? */
    fun names(name: String, text: String): Boolean {
        fun word(w: String) = Regex("(?<![a-z0-9_])${Regex.escape(w)}(?![a-z0-9_])").containsMatchIn(text)
        val nick = nick(name)
        return word(name.lowercase()) || (nick.length >= 4 && word(nick))
    }

    private val chunk = Regex("[A-Z]?[a-z]+|[A-Z]+(?![a-z])")
    // (Nor is anybody called by a part of their name that reads as an insult: "AnIdiotInATree" is "tree", not "idiot".)
    private val notNames = setOf("its", "the", "not", "just", "iam", "you", "mrs", "sir", "xxx",
        "idiot", "stupid", "dumb", "noob", "trash", "loser", "ugly", "fat", "hate", "kill", "die", "dead", "bad")

    /** What people actually call someone: "JezusLuver44" is "jezus", "Jacob1Jacob2" is "jacob". */
    fun nick(name: String): String {
        val parts = chunk.findAll(name).map { it.value.lowercase() }.toList()
        val short = parts.firstOrNull { it.length >= 3 && it !in notNames }
        return if (short == null || parts.size < 2 && short.length == name.length) name.lowercase() else short
    }

    private val kitWords = mapOf("magma cube" to "magma", "wither skeleton" to "wither", "iron golem" to "golem",
        "polar bear" to "bear", "skeletal horse" to "horse")

    /** "Magma Cube" is "magma" in chat, "Chicken" is "chicken". */
    fun kit(displayName: String): String = displayName.lowercase().let { kitWords[it] ?: it.substringAfterLast(' ') }

    /** "Chicken Missile" is often just "missile". */
    fun move(ability: String, short: Boolean): String {
        val full = ability.lowercase()
        val last = full.substringAfterLast(' ')
        return if (short && last != full && last.length >= 4) last else full
    }
}

/**
 * Which wordings were used recently. Shared by every match on the server, so bots work through
 * all the ways of saying something before any one of them comes round again.
 */
class LineMemory {
    private val used = HashMap<String, Long>()
    private var clock = 0L
    @Synchronized fun lastUsed(key: String): Long = used[key] ?: 0L
    @Synchronized fun mark(key: String) { used[key] = ++clock }
    companion object { val shared = LineMemory() }
}

/** A bot's habits, fixed by who it is: its filler words and how it refers to people. */
class BotVoice private constructor(seed: Long) {
    private val r = Random(seed)
    val bro = listOf("bro", "bro", "bro", "dude", "dawg", "man", "bruh").random(r)
    val lol = listOf("lol", "lol", "lol", "lmao", "lmao", "lmfao", "xd").random(r)
    /** Chance of calling someone by a shortened name. */
    val nicknames = listOf(0.15, 0.5, 0.8).random(r)
    /** Chance of calling someone by their kit ("chicken") instead of their name. */
    val kitNames = listOf(0.1, 0.3, 0.5).random(r)
    val shortMoves = r.nextBoolean()

    companion object {
        /** By name, not by id: the same bot has a different id in every lobby and is still the same person ([BotCharacter]). */
        fun of(name: String) = BotVoice(BotCharacter.seedOf(name) xor 0x5DEECE66DL)
    }
}

/**
 * Every line a bot can say. Each function is handed facts from the match (who, which ability, how
 * many lives) and returns one line in that bot's voice; nothing here invents an event.
 * A " / " in a line means the bot sends it as two messages.
 */
class BotLines(private val random: Random, private val memory: LineMemory) {
    /** [personality] is this bot's own: in a Random match the bots in one lobby differ. */
    fun speaker(bot: BotDialogue.Fighter, fighters: List<BotDialogue.Fighter>, personality: BotPersonality) =
        Speaker(bot, fighters, personality)

    /** The least recently used wording in this pool, so nothing repeats until everything has been said. */
    private fun pick(pool: String, options: List<String>, personality: BotPersonality): String? {
        if (options.isEmpty()) return null
        val oldest = options.indices.minOf { memory.lastUsed("$personality/$pool/$it") }
        val index = options.indices.filter { memory.lastUsed("$personality/$pool/$it") == oldest }.random(random)
        memory.mark("$personality/$pool/$index")
        return options[index]
    }

    inner class Speaker(private val bot: BotDialogue.Fighter, private val fighters: List<BotDialogue.Fighter>,
                        private val personality: BotPersonality) {
        private val voice = BotVoice.of(bot.name)
        private val bro = voice.bro
        private val lol = voice.lol
        private val names = HashMap<UUID, String>()
        private val myKit get() = BotSlang.kit(bot.kit)
        /**
         * One line from the pool for this bot's personality. A Salty bot (the complainer) uses its
         * own wording where it has one and otherwise sounds like a Toxic one.
         */
        private fun say(pool: String, toxic: List<String>, chill: List<String>, salty: List<String>? = null) =
            pick(pool, when (personality) {
                BotPersonality.CHILL -> chill
                BotPersonality.SALTY -> salty ?: toxic
                else -> toxic
            }, personality)

        /** Name, nickname or kit, chosen once so a line never calls the same person two things. */
        fun ref(f: BotDialogue.Fighter): String = names.getOrPut(f.id) {
            val kit = BotSlang.kit(f.kit)
            val nick = BotSlang.nick(f.name)
            val roll = random.nextDouble()
            when {
                // "nice chicken missile chicken" reads wrong; use a name next to that kit's own moves.
                // (In the lobby nobody has a kit yet.)
                roll < voice.kitNames && kit.isNotEmpty() && kit !in moveInLine && fighters.count { BotSlang.kit(it.kit) == kit } == 1 -> kit
                roll < voice.kitNames + voice.nicknames && fighters.count { BotSlang.nick(it.name) == nick } == 1 -> nick
                else -> f.name.lowercase()
            }
        }
        private var moveInLine = ""
        private fun move(ability: String) = BotSlang.move(ability, voice.shortMoves && random.nextBoolean()).also { moveInLine = ability.lowercase() }
        /** Kill-feed labels that are not ability names read badly in a sentence. */
        private fun named(ability: String) = ability !in setOf("Attack", "Projectile", "Explosion", "Slam", "Void", "the environment")

        // ------------------------------------------------------------ match events

        fun start(human: BotDialogue.Fighter?): String? {
            val kit = human?.let { BotSlang.kit(it.kit) }
            return say("start",
                listOf("gl", "yo", "lets go", "gl dont camp", "no campers this round pls", "pls actually fight this time",
                    "first one to camp gets reported $lol", "gl no hiding") +
                    if (kit != null) listOf("ugh $kit", "ofc theres a $kit", "$kit huh") else emptyList(),
                listOf("gl", "glhf", "gl all", "hf", "yo gl", "lets go", "gl everyone"),
                listOf("gl i guess", "pls no campers this time", "watch me get targeted first again", "bet i get the worst spawn",
                    "if i lag again im done", "pls let me have one good game", "everyone goes for me first watch"))
        }

        fun killed(victim: BotDialogue.Fighter, ability: String): String? {
            val a = move(ability); val v = ref(victim); val left = victim.lives
            val out = victim.lives <= 0
            val withMove = if (named(ability)) listOf("$a too strong for u $v?", "$bro walked right into $a", "$a diff") else emptyList()
            return if (out) say("kill.out",
                listOf("ez", "ez $v", "$v is out $lol", "bye $v", "sit down $v", "gg $v ur done", "gg ez $v", "that was free", "ez / next"),
                listOf("gg $v", "gf $v", "wp $v", "gg that was close", "gf"),
                listOf("finally", "about time $v", "took way too long", "ok finally one goes my way", "finally $v is out", "thank god"))
            else say("kill",
                listOf("ez", "ez $v", "ezz", "get rekt", "$v down to $left $lol", "get good $v", "skill issue", "thats $left left $v",
                    "u good $v? $lol", "free", "sit $v", "too ez", "$v $left lives left keep talking", "ez / skill issue") + withMove,
                listOf("gf", "nice fight $v", "got u $lol", "gf $v", "close one", "wp") +
                    if (named(ability)) listOf("that $a finally hit $lol") else emptyList(),
                listOf("finally", "about time", "finally a hit registers", "see when the game actually works", "took like 5 tries",
                    "ok finally", "one thing goes right for once", "$v still has $left tho ugh") +
                    if (named(ability)) listOf("finally $a hits something", "$a actually worked for once") else emptyList())
        }

        fun diedTo(killer: BotDialogue.Fighter?, ability: String, killerSpams: Boolean): String? {
            val a = move(ability)
            if (killer == null) return if (ability == "Void") say("death.void",
                listOf("wtf", "i fell $lol", "bruh i slipped", "nah the void", "my double jump didnt work", "lag", "$bro i missed the jump"),
                listOf("oops", "whoops fell off", "lol i fell", "missed that jump"),
                listOf("are u kidding me", "i pressed jump", "my jump didnt go off again", "this map is so bad", "this game hates me",
                    "nah im so done", "ofc i fall", "why is the edge right there", "i hate this map"))
            else (if (ability == "the environment") "the map" else a).let { what ->
                say("death.map", listOf("wtf was that", "died to $what $lol", "bruh", "nah $what got me"), listOf("oops", "rip", "lol $what got me"),
                    listOf("ofc", "are u kidding", "why does $what even exist", "this map is so bad", "i cant even walk around without dying"))
            }
            val k = ref(killer)
            val withMove = if (named(ability)) listOf("$a is so bs", "nah that $a is broken", "ofc $a", "one $a and hes cocky", "how did that $a hit") else emptyList()
            val spam = if (killerSpams && named(ability)) listOf("$k only knows one button $lol", "all $k does is $a") else emptyList()
            return say("death",
                listOf("wtf", "bs", "how did that hit", "ok $k", "$bro what", "lucky", "$k got lucky", "ok that was bs", "im lagging",
                    "that hitbox $lol", "whatever", "bruh", "??", "wtf / how") + withMove + spam,
                listOf("gf", "ok that was clean", "u got me $lol", "nice one", "wp $k", "oof") +
                    if (named(ability)) listOf("nice $a $k") else emptyList(),
                listOf("are u kidding", "every time", "im so done", "this is so unfair", "why is it always me", "i cant do anything",
                    "i was one hit off", "nothing i do works", "i had u $k", "my $myKit does nothing", "this kit is so bad", "ofc i die",
                    "i cant even play", "omg", "why do i even try", "are u kidding / i was one hit off", "im so done / every single time") +
                    (if (named(ability)) listOf("$a again really", "im so sick of $a", "$a needs a nerf", "how do u even dodge $a",
                        "i cant do anything about $a") else emptyList()) +
                    if (killerSpams && named(ability)) listOf("$k just spams $a its not fair", "all i ever see is $a") else emptyList())
        }

        fun crystal(holder: BotDialogue.Fighter, tier: String): String? {
            val n = ref(holder); val t = tier.lowercase()
            return say("crystal",
                listOf("ofc $n gets the crystal", "$n with a crystal great", "run", "oh no", "everyone run $lol", "$bro who let $n get that", "not the $t"),
                listOf("$n has a crystal watch out", "uh oh $n got the $t", "nice grab $n", "careful $n has smash"),
                listOf("ofc i never get the crystal", "why does $n always get it", "i was right there", "great now im dead",
                    "that crystal spawned on $n i swear", "i never get a smash", "and $n gets the $t ofc"))
        }

        fun spam(spammer: BotDialogue.Fighter, ability: String, count: Int): String? {
            val a = move(ability); val n = ref(spammer)
            return say("spam",
                listOf("$n stop spamming $a", "$count ${a}s in a row $lol", "$n only has one button", "its just $a over and over", "all $n does is $a",
                    "$bro learn another move", "spam $a more $n", "$a $a $a", "wow $n big skill", "sit and spam $a", "press something else $n"),
                listOf("thats a lot of $a $n $lol", "$n loves that $a", "ok $n i see the $a plan", "$a again $lol"),
                listOf("$n pls stop with the $a", "i cant move without getting hit by $a", "$count ${a}s how is that fair", "$a is so annoying",
                    "im so tired of $a", "can u use anything else $n", "i cant even get close bc of $a", "$a every 2 seconds im done"))
        }

        /** [height] is 1 when the camper is above this bot, -1 below, 0 level. [level] rises each time they are called out. */
        fun hold(camper: BotDialogue.Fighter, seconds: Int, height: Int, level: Int, spammed: String?): String? {
            val useSpam = spammed != null && random.nextBoolean()
            val a = if (useSpam) move(spammed!!) else ""
            val n = ref(camper)
            val where = when (height) { 1 -> "up there"; -1 -> "down there"; else -> "over there" }
            val come = when (height) { 1 -> "come down"; -1 -> "come up"; else -> "come out" }
            val kit = BotSlang.kit(camper.kit)
            if (useSpam) {
                return say("hold.spam",
                    listOf("$n just sits $where spamming $a", "sit and spam $a", "camp and $a thats the whole strat", "all $n does is hide and $a",
                        "$a spam from $where wow", "big skill $n", "hide spam $a repeat $lol", "$n $come and stop spamming $a"),
                    listOf("$n u been $where using $a for a while $lol", "that $a from $where is annoying ngl", "$come and fight up close $n"),
                    listOf("i cant even get to $n and i keep getting hit by $a", "$a from $where is so unfair", "how am i supposed to fight that",
                        "$n pls $come im so sick of $a", "i cant do anything about $a from $where"))
            }
            return when (level) {
                0 -> say("hold.0",
                    listOf("$n come fight", "$n $come", "$bro $come", "$n stop hiding", "u gonna fight or what $n", "$n is hiding $lol",
                        "$n stop running", "fight me $n", "why is $n just standing $where"),
                    listOf("$n $come lets fight", "come fight $n", "where u hiding $n $lol"),
                    listOf("$n pls just fight", "i cant reach $n", "how am i supposed to get $where", "why does $n get to hide",
                        "$n $come pls", "i cant hit anything $where"))
                1 -> say("hold.1",
                    listOf("$n has been $where for ${seconds}s", "$n is camping", "hes just camping", "$bro is camping $where $lol",
                        "aint no way ur having fun $n", "actually play the game $n", "$n playing hide n seek", "imagine camping $lol",
                        "this $kit just camps", "$n scared?", "whats the point $n", "${seconds}s in the same spot $lol", "$n just sits $where"),
                    listOf("nice spot $n", "ok i cant reach u $where $lol", "$n found a good spot", "how do i get $where"),
                    listOf("ive been trying to hit $n for ${seconds}s", "this is so annoying", "why does everyone camp against me",
                        "i cant get $where its not fair", "${seconds}s and i cant touch $n", "this isnt even fun anymore", "my $myKit cant reach $where"))
                else -> say("hold.2",
                    listOf("ok im not chasing u $n", "whatever have fun camping", "so boring", "$n still $where $lol", "hes not gonna come out",
                        "im just gonna wait", "camp harder $n", "wake me up when $n moves", "reported for stalling $lol", "still camping", "yawn",
                        "$bro u have ${camper.lives} lives and ur hiding"),
                    listOf("ill wait here then", "ur safe $where i guess $lol", "ok u win that spot", "coming for u eventually $n"),
                    listOf("im so bored", "i give up", "whats even the point", "ok i quit chasing $n", "this game is so boring like this",
                        "$n still $where im done", "i hate this"))
            }
        }

        fun burst(attacker: BotDialogue.Fighter, hits: Int): String? {
            val n = ref(attacker)
            return say("burst",
                listOf("wtf", "wtf $n", "$hits hits in a sec??", "hacks", "$n is hacking", "nah thats hacks", "reach", "$bro has reach", "reported",
                    "$n reported", "how is $n not banned", "turn it off $n", "aint no way thats legit", "recording this", "ok hacker",
                    "kill aura $lol", "nobody hits that fast", "sus", "$n lagging or hacking??", "nice client $n", "wtf / $n is hacking", "?? / reported"),
                listOf("nice combo $n", "wow $n", "that combo tho", "ok that was clean", "$n is cracked", "sheesh", "how did u do that $lol", "teach me that $n"),
                listOf("how do i even fight that", "i couldnt even move", "$hits hits and i cant do anything", "that is so unfair",
                    "i got no time to react", "i was stuck", "why cant i ever do that", "my screen froze", "i cant even hit back",
                    "omg $n", "what am i supposed to do there", "i couldnt move / $hits hits in a sec"))
        }

        /** This bot has been killed [times] times by [camper] sitting in one spot, and has had enough of walking into it. */
        fun trapped(camper: BotDialogue.Fighter, times: Int): String? {
            val n = ref(camper)
            return if (times <= 1) say("trap.1",
                listOf("ok not going that way again", "nah im not walking into that twice", "ok i see the setup $n", "not falling for that again $lol",
                    "u can keep that spot $n", "ok $n u got ur one", "yeah im not coming back in there", "cool spot $n im going around"),
                listOf("ok not going that way again $lol", "good spot ngl", "noted $n $lol", "ok that way is a no", "nice setup $n im going around"),
                listOf("im not going in there again", "ofc theres no safe way in", "i cant even get near $n", "every way in is a death trap",
                    "ok so i just cant go that way great"))
            else say("trap.2",
                listOf("ok im done going in there", "nah im staying out $n", "come out if u want the kill $n", "im not feeding u anymore $n",
                    "sit there all game idc", "u want me u come here $n", "not doing that a third time $lol"),
                listOf("ok im staying out of there $lol", "ur spot wins $n ill wait", "not walking into that a third time $lol", "ill be out here $n"),
                listOf("im done going in there", "i die every time i go near $n", "whats the point of going in", "im just gonna stand here then",
                    "i give up on that spot"))
        }

        fun retreat(threat: BotDialogue.Fighter?): String? {
            val t = threat?.let(::ref) ?: return say("retreat.alone", listOf("im low", "one sec", "im one hit"), listOf("im low one sec", "need a sec"),
                listOf("im always low", "im one hit again", "i cant get one second to heal"))
            return say("retreat",
                listOf("$t chill", "im one hit", "stop chasing me $t", "$bro im low", "ofc $t targets me when im low", "leave me alone $lol",
                    "one sec", "go fight someone else $t", "why me"),
                listOf("im low one sec", "brb healing $lol", "need a sec", "ok ok backing off", "u almost got me $t"),
                listOf("why is it always me $t", "$t pls go hit someone else", "i cant get one second", "everyone targets me",
                    "im one hit leave me alone", "ofc im the one getting chased", "can i heal for once", "$t pls"))
        }

        fun status(leader: BotDialogue.Fighter): String? {
            val n = ref(leader)
            return say("status", listOf("$n still has ${leader.lives} lives", "$n is winning somehow", "${leader.lives} lives $n?? ok"),
                listOf("$n is up with ${leader.lives} lives", "$n leading rn with ${leader.lives}"),
                listOf("how does $n still have ${leader.lives} lives", "$n has ${leader.lives} lives its so over", "nobody ever hits $n"))
        }

        fun won(): String? = say("won", listOf("gg ez", "ez", "ggez", "gg", "too ez", "ez game", "gg get good"), listOf("gg", "gg all", "ggs", "gg wp", "that was fun gg"),
            listOf("finally", "gg finally", "about time i won one", "gg that was stressful", "ok finally a win"))

        /** [streak]: how many matches in a row with this bot in them [winner] has now won, as far as it remembers. */
        fun lost(winner: BotDialogue.Fighter, camped: Boolean, streak: Int = 0): String? {
            val w = ref(winner)
            val again = streak >= 2
            return say("lost",
                listOf("gg", "gg i guess", "ok $w", "whatever gg", "$w got lucky", "rematch", "run it back", "bs game") +
                    (if (camped) listOf("gg $w nice camping", "camped the whole game and won gg") else emptyList()) +
                    (if (again) listOf("$streak in a row ok $w", "$w again whatever", "someone beat $w pls", "$w wins again wow") else emptyList()),
                listOf("gg $w", "gg wp", "wp $w", "ggs", "gg that was close") +
                    (if (again) listOf("$w again gg", "$streak in a row $w wp", "$w is on a streak gg") else emptyList()),
                listOf("gg i guess", "ofc", "i never win", "this game hates me", "gg whatever i had the worst luck", "i was lagging the whole game",
                    "everyone targeted me all game", "i hate this kit", "im done") +
                    (if (camped) listOf("$w camped all game ofc", "cant win when $w hides the whole time") else emptyList()) +
                    (if (again) listOf("$w wins every game", "$streak in a row how", "ofc $w again", "does $w ever lose") else emptyList()))
        }

        /** A second bot backing up what another bot just said. */
        fun agree(about: String): String? = when (about) {
            "hold" -> say("agree.hold", listOf("fr", "fr fr", "real", "ikr", "just ignore him", "let him camp $lol", "so annoying", "$bro fr", "facts"),
                listOf("lol", "its a good spot tbh", "fr", "lets go get him"),
                listOf("fr its so annoying", "ikr i cant reach either", "same", "fr im so sick of it", "every game"))
            "burst" -> say("agree.burst", listOf("fr", "i saw that", "yea thats sus", "sus", "reported too"), listOf("that was clean", "cracked", "fr"),
                listOf("same thing happened to me", "fr its not fair", "ikr u cant do anything"))
            else -> say("agree.kill", listOf("rip", "f", "lol", "oof"), listOf("rip", "f", "oof", "gf"), listOf("rip", "f", "thatll be me next", "oof"))
        }

        // ------------------------------------------------------------ bots talking to each other
        //
        // What makes a mixed lobby: one bot's line gets an answer from another bot, in that bot's
        // own personality. [about] is what the first line was on: "kill", "death", "hold", "spam",
        // "burst", "retreat", or "beef" for an argument that is already going.

        /** Answering a line that was aimed at this bot: a salty bot claps back, a chill one shrugs it off. */
        fun comeback(to: BotDialogue.Fighter, about: String): String? {
            val n = ref(to)
            val score = when {
                to.lives < bot.lives -> listOf("u have ${to.lives} lives $n", "ur on ${to.lives} lives and typing", "im up ${bot.lives} to ${to.lives} keep talking")
                to.lives > bot.lives -> listOf("its not over $n", "${to.lives} to ${bot.lives} isnt over $bro")
                else -> listOf("were both on ${bot.lives} $n relax")
            }
            val heated = when (about) {
                "kill" -> listOf("one kill $n relax", "u got lucky", "do it again then", "lucky hit and now ur typing", "wow one kill")
                "hold", "spam" -> listOf("its called strategy", "then come get me", "im not camping ur just slow", "cry about it $n", "works on u every time $lol")
                "burst" -> listOf("im not hacking ur just bad", "its called a combo $n", "get good", "report me then $lol", "everyone who hits u is hacking?")
                "death" -> listOf("ya i killed u and", "stay mad $n", "it hit bc ur slow", "skill issue", "dodge next time")
                "retreat" -> listOf("no", "run then $lol", "nah ur dead", "come back $n", "ur one hit ofc im chasing")
                else -> emptyList()
            }
            val easygoing = when (about) {
                "kill" -> listOf("gf $n", "ya u got me $lol", "nice one $n", "ill get u back $lol")
                "hold", "spam" -> listOf("my bad $lol", "ok ok im coming", "sorry $n its a good spot", "fair $lol")
                "burst" -> listOf("no hacks i promise $lol", "just got lucky $n", "ty i guess $lol")
                "death" -> listOf("gf $n", "sorry $lol", "it was close tho", "unlucky $n")
                "retreat" -> listOf("sorry $n $lol", "gotta finish it sorry", "gf im still coming tho")
                else -> emptyList()
            }
            return say("comeback.$about",
                listOf("shut up $n", "ok $n", "nobody asked $n", "$n talks so much", "1v1 me then $n", "cry", "$bro shut up $lol", "keep typing $n") + score + heated,
                listOf("lol ok $n", "relax $n its a game", "all good $n", "its not that serious $n $lol") + easygoing,
                listOf("leave me alone $n", "why is everyone on me", "ok i get it $n", "u dont have to rub it in", "wow thx $n",
                    "it wasnt even my fault", "stop $n im already mad", "omg $n stop") + when (about) {
                    "kill" -> listOf("u got lucky and u know it", "i was already low $n", "i was fighting 2 ppl", "i had no jump left")
                    "hold", "spam" -> listOf("what else am i supposed to do", "its the only thing that works for me", "im not camping i just keep dying",
                        "every time i go in i die $n")
                    "burst" -> listOf("im not hacking i finally hit something", "the one time i land a combo", "so im not allowed to hit u")
                    "death" -> listOf("so im not allowed to hit u", "u do it to me all game", "ok sorry for playing", "now u know how it feels")
                    "retreat" -> listOf("u chased me all game", "now u know how it feels", "nobody lets me heal either")
                    else -> emptyList()
                })
        }

        /** A chill bot sticking up for [victim] (or just telling [bully] to calm down) after a salty line. */
        fun defend(victim: BotDialogue.Fighter?, bully: BotDialogue.Fighter, about: String): String? {
            val b = ref(bully)
            val v = victim?.takeIf { it.id != bot.id }?.let(::ref)
            val forThem = if (v == null) emptyList() else when (about) {
                "kill" -> listOf("be nice $b", "$v played fine", "leave $v alone $lol", "that was a good fight tho")
                "hold", "spam" -> listOf("let $v play", "$v isnt camping ur just missing", "its a legit strat $b", "leave $v alone")
                "burst" -> listOf("$v isnt hacking $lol", "thats just a good combo $b", "$v is just good", "not everything is hacks $b")
                "death" -> listOf("$v got u fair $b", "that was clean tho", "$v outplayed u its ok", "it happens $b")
                else -> listOf("leave $v alone $lol")
            }
            return pick("defend.$about", listOf("chill $b", "$b relax its a game", "$b is so mad $lol", "calm down $b", "$bro its not that deep",
                "$b breathe $lol", "why so salty $b") + forThem, BotPersonality.CHILL)
        }

        /** The salty bot turning on whoever just told it to calm down. */
        fun rebuke(defender: BotDialogue.Fighter): String? {
            val d = ref(defender)
            return pick("rebuke", listOf("nobody asked $d", "ok $d", "$d stay out of it", "who asked", "$d defending everyone $lol", "ur next $d",
                "$d u have ${defender.lives} lives worry about that", "im not mad", "not mad just right", "ok so ur teaming with them", "$bro who asked"),
                BotPersonality.TOXIC)
        }

        /** A chill bot suggesting everyone goes after [bully] first. */
        fun rally(bully: BotDialogue.Fighter): String? {
            val b = ref(bully)
            return pick("rally", listOf("lets team on $b $lol", "everyone get $b", "ok who wants to get $b first", "$b first then we fight",
                "truce til $b is out?", "all on $b $lol", "can we all just get $b"), BotPersonality.CHILL)
        }

        /** Another chill bot taking the first one up on it. */
        fun joinIn(bully: BotDialogue.Fighter): String? {
            val b = ref(bully)
            return pick("joinin", listOf("bet", "im down", "omw", "say less", "on it $lol", "ya get $b", "deal", "finally $lol"), BotPersonality.CHILL)
        }

        /** A chill bot with nobody to team with going after [bully] alone. */
        fun duel(bully: BotDialogue.Fighter): String? {
            val b = ref(bully)
            return pick("duel", listOf("ok im coming for u $b", "$b ur first $lol", "fine come here $b", "ok $b lets go then", "u talk a lot $b come fight"),
                BotPersonality.CHILL)
        }

        /** The salty bot finding [count] fighters have turned on it. */
        fun ganged(count: Int): String? = pick("ganged", listOf("teaming?? ok", "yall teaming is crazy", "${count}v1 nice", "wow ${count}v1 so brave",
            "reported for teaming $lol", "need $count of u to beat me $lol", "ok all of u come then", "teamers $lol"), BotPersonality.TOXIC)

        // ------------------------------------------------------------ Team-Ups: the lobby turning on a player

        /**
         * Calling for everybody to go after [target], who has been asking for it ([reason]).
         * [spammed] is the ability they keep pressing, [mine] how often they have killed this bot
         * lately, [team] how many bots are in on it.
         */
        fun rallyOn(target: BotDialogue.Fighter, reason: TeamUps.Reason, spammed: String?, mine: Int, team: Int): String? {
            val a = spammed?.let(::move)
            val t = ref(target)
            return when {
                reason == TeamUps.Reason.SPAM && a != null -> say("teamup.spam",
                    listOf("everyone get the $a spammer", "all on $t til the $a stops", "$t spams we team", "truce / everyone on $t",
                        "$t only presses $a so all of us on $t", "ok whos sick of $a / get $t", "$a $a $a ok everybody on $t"),
                    listOf("ok thats a lot of $a / lets all get $t", "sorry $t the $a has to stop $lol", "truce til $t stops with the $a?",
                        "ok $t we coming for u and ur $a $lol"),
                    listOf("pls everyone get $t i cant take the $a anymore", "team on $t til the $a stops pls", "im so tired of $a / all on $t",
                        "can someone help me kill $t the $a is nonstop", "i cant move bc of $a / everybody get $t"))
                reason == TeamUps.Reason.FARMING -> say("teamup.farm",
                    listOf("ok $t thinks this is ez / everyone on $t", "$t keeps farming kills team up", "all on $t $lol", "can we all just kill $t first",
                        "truce til $t loses a life", "$t is getting too comfy everyone on $t", "kill $t first then we fight", "$t has ${target.lives} lives still get them"),
                    listOf("$t is too good we gotta team $lol", "ok $t is carrying / truce til we get them?", "lets even it out / all on $t",
                        "ok $t ur too strong we teaming $lol"),
                    listOf("ok thats it / everyone on $t", "$t keeps killing me help", "im not dying to $t again someone team with me",
                        "why does $t only kill me / get them", "can we pls team on $t") +
                        if (mine >= 2) listOf("$t killed me $mine times can we team on them", "$mine times $t has killed me / everybody get them") else emptyList())
                reason == TeamUps.Reason.MOUTH -> say("teamup.mouth",
                    listOf("$t talks too much everyone get $t", "ok $t has a lot to say / all on $t", "lets shut $t up real quick", "everyone on the loud one $lol",
                        "$t typed their way into a ${team}v1", "ok $t said ez / everybody get $t"),
                    listOf("ok $t asked for it $lol", "$t u kinda earned this one / all on $t", "ok lets humble $t real quick $lol"),
                    listOf("$t wont stop talking / get them", "ok $t wants to talk / everyone on $t", "im so done with $t typing / team on them"))
                else -> say("teamup.camp",
                    listOf("ok everyone get $t", "truce til $t is dead", "all on $t $lol", "$t camps we team simple", "new plan everyone jump $t",
                        "stop fighting each other and get $t", "who wants to gang up on the camper", "everyone on the camper",
                        "$t wants to camp so we all go $t", "truce / everyone on $t", "yo forget each other get $t"),
                    listOf("ok $t we gotta come get u now $lol", "truce til $t comes out of there?", "sorry $t but this is taking forever / all on $t",
                        "ok lets all just go get $t", "$t ur making us team $lol"),
                    listOf("can we pls all get $t", "im so sick of the camping / everyone get $t", "someone help me get $t out of there",
                        "team on $t pls i cant reach them alone", "$t has been hiding all game can we team", "ok im done / all on $t"))
            }
        }

        /** Telling [target] what is coming if they keep it up: the last thing a lobby says before it teams. */
        fun warnTeam(target: BotDialogue.Fighter, reason: TeamUps.Reason, spammed: String?): String? {
            val a = spammed?.let(::move)
            val t = ref(target)
            val doing = when {
                reason == TeamUps.Reason.SPAM && a != null -> "spamming $a"
                reason == TeamUps.Reason.FARMING -> "farming"
                reason == TeamUps.Reason.MOUTH -> "talking"
                else -> "camping"
            }
            return say("teamup.warn",
                listOf("$t keep $doing and we all jump u", "$t ur about to get teamed on", "last warning $t", "one more minute of $doing and its all of us on u $t",
                    "$t u really want a ${fighters.count { it.bot && it.lives > 0 }}v1?", "keep $doing $t see what happens", "$t ur making this a team game"),
                listOf("$t come on dont make us team $lol", "$t careful ur making everyone mad $lol", "$t maybe ease up on the $doing $lol",
                    "$t they gonna team on u if u keep $doing"),
                listOf("$t if u dont stop $doing im getting everyone", "im this close to teaming on $t", "dont make me get the others $t",
                    "$t stop $doing or i swear we team", "ok $t one more and im asking them to team"))
        }

        /** Taking another bot up on its call to go after [target]. */
        fun joinTeam(target: BotDialogue.Fighter): String? {
            val t = ref(target)
            return say("teamup.join",
                listOf("bet", "say less", "omw", "finally", "on it", "ya get $t", "been waiting for this $lol", "im in", "lets go", "$t is done", "ez"),
                listOf("ok fine", "ok just this once $lol", "alright alright", "ok ya its been long enough", "sorry $t $lol", "ok im in but thats it", "fair enough"),
                listOf("finally", "yes pls", "thank u", "ya im so done with $t", "about time", "im in", "fr get $t", "ok ya"))
        }

        /** A chill bot that wants no part in teaming on [target]. */
        fun declineTeam(target: BotDialogue.Fighter): String? {
            val t = ref(target)
            return pick("teamup.decline", listOf("nah not teaming", "im good $lol", "teaming is lame tho", "nah let $t play", "ill fight $t 1v1",
                "not my thing", "u do that im out", "nah thats weak", "no teaming for me gl tho", "nah id rather win fair", "not doing that sorry"),
                BotPersonality.CHILL)
        }

        /** The team-up on [target] is over. */
        fun teamDone(target: BotDialogue.Fighter, how: TeamUps.End): String? {
            val t = ref(target)
            return when (how) {
                TeamUps.End.KILLED -> say("teamup.done",
                    listOf("ez", "get teamed on $lol", "thats what u get $t", "ok truce over", "ok back to ffa", "stay out of that spot $t",
                        "ok everyone for themselves again", "bye $t $lol"),
                    listOf("ok thats enough", "ok we good now $t $lol", "sorry $t gf", "truce over gl all", "ok back to normal", "ok no more teaming"),
                    listOf("finally", "ok now we fight normal", "that felt good", "truce over i guess", "dont do that again $t", "about time"))
                TeamUps.End.TIMEOUT -> say("teamup.bored",
                    listOf("ok forget it", "whatever truce over", "$t isnt worth it", "ok im bored / ffa again", "this is taking too long"),
                    listOf("ok lets just play", "truce over $lol", "eh not worth it", "ok back to ffa"),
                    listOf("this isnt working", "ok i give up on $t", "we cant even get $t together", "whatever im done"))
                TeamUps.End.GONE -> say("teamup.gone", listOf("$t is out $lol", "bye $t", "gg $t $lol", "and $t is gone"), listOf("gg $t", "ok gl everyone"),
                    listOf("finally $t is gone", "ok good"))
                else -> null
            }
        }

        /** A toxic bot turning on [partner] the moment the team-up is over. */
        fun turnOn(partner: BotDialogue.Fighter): String? {
            val x = ref(partner)
            return pick("teamup.turn", listOf("and now ur next $x", "truce over $x $lol", "thx for the help $x / ur next", "never said i was ur friend $x",
                "ok $x ur turn", "nothing personal $x", "$x u really thought $lol"), BotPersonality.TOXIC)
        }

        /** Finding the partner of a moment ago, [snake], has turned on this bot. */
        fun turnedOn(snake: BotDialogue.Fighter): String? {
            val s = ref(snake)
            return say("teamup.turned",
                listOf("knew it $lol", "wow ok", "bet come here", "snake", "$s u snake"),
                listOf("lol ok fair", "saw that coming $lol", "ok ok gf", "that was fast $lol"),
                listOf("are u serious", "we literally just teamed", "i helped u??", "this is why i dont team", "ofc u did"))
        }

        /** [player] complains about being teamed on, to a bot that is in on it. */
        fun whyTeamed(player: BotDialogue.Fighter, reason: TeamUps.Reason, spammed: String?, mine: Int, team: Int): String? {
            val a = spammed?.let(::move)
            val t = ref(player)
            val toxic = when {
                reason == TeamUps.Reason.SPAM && a != null -> listOf("stop spamming $a then", "u spam we team", "$a spam gets u teamed $lol", "press another button then")
                reason == TeamUps.Reason.FARMING -> listOf("u were winning too hard $lol", "get good at ${team}v1 then", "ur the one with all the kills", "ur too comfy thats why")
                reason == TeamUps.Reason.MOUTH -> listOf("u talked ur way into it $lol", "less typing next time", "u said ez so", "u asked for it in chat")
                else -> listOf("then stop camping", "come out and we stop $lol", "u camp we team", "cry about it", "thats what campers get", "should have fought us")
            }
            return say("teamup.why", toxic,
                listOf("sorry $t its the only way to get u $lol", "ya sorry about that", "we stop when u come fight", "my bad it was getting old tho",
                    "one life then we stop promise"),
                listOf("u started it", "its the only way to get u", "now u know how it feels", "well stop then", "what did u expect") +
                    if (mine >= 2) listOf("u killed me $mine times what did u expect") else emptyList())
        }

        /** [player] complains about being teamed on, to a bot that stayed out of it. */
        fun notTeaming(player: BotDialogue.Fighter) = say("teamup.notme", listOf("im not even teaming thats them", "wasnt me $lol", "i didnt sign up for that"),
            listOf("im not teaming on u thats them $lol", "i told them no", "not me ${ref(player)}"))

        /** A chill bot letting [player] off after an apology. */
        fun truceTaken(player: BotDialogue.Fighter): String? {
            val t = ref(player)
            return pick("teamup.forgive", listOf("ok all good", "ok im out of this", "np truce", "ok ok we cool", "ok im done chasing u $lol", "ok $t we good"),
                BotPersonality.CHILL)
        }

        /** A bot that is not letting [player] off for saying sorry. [mine] is how often they have killed it lately. */
        fun truceRefused(player: BotDialogue.Fighter, mine: Int) = say("teamup.nope",
            listOf("too late $lol", "nah", "should have thought of that", "no", "sorry doesnt work here $lol"),
            listOf("one life then we good ${ref(player)}", "almost done $lol"),
            listOf("now ur sorry", "nope", "u had ur chance", "too late") + if (mine >= 2) listOf("after u killed me $mine times? no") else emptyList())

        // ------------------------------------------------------------ Two teams: a player on this bot's side asked for something

        /**
         * This bot says it heard what [player], who is on its side, asked of it ([TeamTalk]) and is
         * doing it. [target] is the opponent a FOCUS is on, when one could be made out.
         */
        fun teamAck(player: BotDialogue.Fighter, ask: TeamTalk.Ask, target: BotDialogue.Fighter?): String? {
            val p = ref(player)
            val t = target?.let(::ref)
            return when (ask) {
                TeamTalk.Ask.COME -> say("team.come",
                    listOf("omw", "coming", "ok omw", "on my way", "k coming", "bet omw", "1 sec omw", "ok coming to u", "omw dont die"),
                    listOf("omw", "coming", "ok on my way", "got u omw", "be right there", "ok coming $lol", "on my way $p", "ok 1 sec"),
                    listOf("ok ok omw", "fine coming", "omw", "coming hold on", "ok 1 sec", "k omw dont die", "ya ya coming"))
                TeamTalk.Ask.FOCUS -> if (t == null) say("team.who",
                    listOf("who", "which one", "who tho", "get who"), listOf("who do u mean", "which one", "who $lol"), listOf("who", "which one tho"))
                else say("team.focus",
                    listOf("ok im on $t", "bet $t is done", "on $t", "k going $t", "$t first got it", "say less im on $t", "ok $t then"),
                    listOf("ok going for $t", "on $t", "got it $t first", "ok ill help with $t", "ok $t it is", "k im on $t"),
                    listOf("ok on $t", "fine $t first", "k $t", "ok ok going $t", "finally ya get $t"))
                TeamTalk.Ask.RETREAT -> say("team.retreat",
                    listOf("ok backing up", "k falling back", "fine im out", "ok ok running", "k pulling out", "bet backing off"),
                    listOf("ok backing off", "good call falling back", "ok im out", "ok running", "k backing up"),
                    listOf("ok ok im running", "ya lets back up", "k backing up", "finally ok", "ok im out of there"))
                TeamTalk.Ask.HOLD -> say("team.hold",
                    listOf("ok holding", "k staying here", "ok ill wait", "holding here", "ok not moving", "bet ill hold"),
                    listOf("ok ill hold here", "got it staying", "ok waiting", "k holding", "ok ill stay put"),
                    listOf("fine ill stay", "ok holding", "k waiting here", "ok ok not moving"))
                TeamTalk.Ask.PUSH -> say("team.push",
                    listOf("lets go", "bet going in", "ok pushing", "say less", "k going in", "ok we go"),
                    listOf("ok lets go", "going in", "ok pushing with u", "k lets get them", "ok im with u"),
                    listOf("ok going in", "finally lets go", "k pushing", "ok ok im going"))
                TeamTalk.Ask.FREE -> say("team.free",
                    listOf("ok", "k", "aight", "bet"), listOf("ok np", "all good", "ok", "k np"), listOf("ok", "k", "fine"))
                TeamTalk.Ask.UNCLEAR -> say("team.unclear",
                    listOf("wdym", "what do u need", "huh", "say that again", "what u want me to do"),
                    listOf("what do u need", "wdym", "whats the plan", "say again", "what should i do $p"),
                    listOf("what", "wdym", "what do u want me to do", "huh"))
            }
        }

        /** Asked for something by a teammate while dead: it will do it once it is back. */
        fun teamDead(ask: TeamTalk.Ask): String? = say("team.dead",
            listOf("im dead rn 1 sec", "respawning gimme a sec", "dead rn", "1 sec im respawning", "just died hold on") +
                if (ask == TeamTalk.Ask.COME) listOf("dead rn omw after", "respawning then omw") else emptyList(),
            listOf("just died 1 sec", "respawning 1 sec", "im dead rn $lol give me a sec", "back in a sec") +
                if (ask == TeamTalk.Ask.COME) listOf("respawning then omw") else emptyList(),
            listOf("im dead rn", "i just died give me a sec", "dead again 1 sec", "respawning"))

        /** Asked to come to a teammate it has found no way to: it says so and fights on where it is. */
        fun teamCant(): String? = say("team.cant",
            listOf("cant get to u", "no way to u from here", "cant reach u there", "how do i even get there", "theres no way up there for me"),
            listOf("cant get to u from here sorry", "no way up there for me", "cant reach u there $lol", "i cant get there come to me"),
            listOf("i cant get there", "theres no way to u", "cant reach u", "how am i supposed to get there"))

        /** Asked for something by a teammate with no lives left to do it with. */
        fun teamOut(): String? = say("team.out",
            listOf("im out of lives", "cant im out", "im out $lol", "no lives left its on u"),
            listOf("im out sorry", "no lives left gl tho", "im out u got this", "cant im out gl"),
            listOf("im out", "i have no lives left", "cant im dead dead", "im out its all u"))

        /** A salty bot annoyed that [nice] is being friendly about a fight. */
        fun sneer(nice: BotDialogue.Fighter, about: String): String? {
            val n = ref(nice)
            val topic = when (about) {
                "burst" -> listOf("that wasnt a combo that was luck", "dont hype it up $n")
                "hold" -> listOf("its not a nice spot its camping", "$n stop encouraging it")
                "death" -> listOf("u died and said gf $lol", "just say u got outplayed $n")
                else -> listOf("gf?? ok", "less gf more fighting")
            }
            return pick("sneer.$about", listOf("stop being nice $n its a fight", "$n complimenting the enemy $lol", "why r u so nice $n",
                "$n is too friendly", "nobody says that $n $lol") + topic, BotPersonality.TOXIC)
        }

        /** A chill bot not taking the bait. */
        fun brushOff(to: BotDialogue.Fighter): String? {
            val n = ref(to)
            return pick("brushoff", listOf("lol relax $n", "its a game $n", "someones mad $lol", "ok $n $lol", "u good $n?", "just having fun $n",
                "$n needs a snack $lol"), BotPersonality.CHILL)
        }

        /** A toxic bot going after [whiner], who just complained: it wants them madder. [mine] is true when this bot killed them. */
        fun needle(whiner: BotDialogue.Fighter, about: String, mine: Boolean): String? {
            val n = ref(whiner)
            val topic = when (about) {
                "death" -> listOf("u died bc ur bad $n", "nothing is ever ur fault huh $n", "its not the game its u $n") +
                    if (mine) listOf("ez", "ez $n", "ez / stay mad", "that was so free $n", "u make it too ez $n") else emptyList()
                "hold", "spam" -> listOf("then go hit them $n", "u cant reach bc ur slow", "sounds like a u problem $n")
                "burst" -> listOf("its called a combo $n", "u stood still and got hit $lol", "learn to dodge $n")
                "retreat" -> listOf("run $n run $lol", "$n running again", "low again $n? $lol")
                else -> emptyList()
            }
            return pick("needle.$about", listOf("cry more $n", "$n is so mad $lol", "$n always whining", "stay mad $n", "skill issue $n",
                "excuses $lol", "$n complaining again", "u complain more than u fight $n", "someone get $n a tissue $lol", "mad?",
                "$n types more than $n hits") + topic, BotPersonality.TOXIC)
        }

        /** The salty bot losing it at [heckler] and going after them. */
        fun snap(heckler: BotDialogue.Fighter): String? {
            val n = ref(heckler)
            return pick("snap", listOf("shut up $n", "im so sick of u $n", "ok thats it $n", "$n ur dead", "im coming for u $n", "say that again $n",
                "u wont be laughing in a sec $n", "ok $n ur first", "omg shut up $n", "i swear $n ur next", "shut up / im coming for u $n",
                "$n i am so done with u"), BotPersonality.SALTY)
        }

        /** The toxic bot loving that [angry] took the bait. */
        fun taunt(angry: BotDialogue.Fighter): String? {
            val n = ref(angry)
            return pick("taunt", listOf("come then $lol", "ok come get me $n", "im so scared $lol", "u cant even hit me $n", "$n so mad $lol",
                "bet", "pull up then $n", "ur gonna lose that too $n", "aw $n is mad", "come die again $n"), BotPersonality.TOXIC)
        }

        /** A chill bot trying to cheer [sad] up after a complaint. */
        fun console(sad: BotDialogue.Fighter): String? {
            val n = ref(sad)
            return pick("console", listOf("its ok $n", "unlucky $n", "u got this $n", "happens to everyone $n", "ur doing fine $n",
                "that was unlucky ngl", "next one $n", "dont worry about it $n", "it was close tho $n"), BotPersonality.CHILL)
        }

        /** The salty bot not really cheered up by [kind]. */
        fun grumble(kind: BotDialogue.Fighter): String? {
            val n = ref(kind)
            return pick("grumble", listOf("thx i guess", "easy for u to say", "ya ya", "im still mad", "doesnt help $n", "ok but its still bs",
                "u have ${kind.lives} lives ofc ur fine", "ty $n but im so done"), BotPersonality.SALTY)
        }

        /** One salty bot recognising another's pain. */
        fun sameHere(): String? = pick("samehere", listOf("same", "fr this game", "ikr im so done too", "happens to me every time",
            "fr nothing works", "literally me all game", "at least its not just me"), BotPersonality.SALTY)

        /** A salty bystander laughing at another bot's excuse for dying. [fell] is true when it fell off the map. */
        fun mock(dead: BotDialogue.Fighter, fell: Boolean): String? {
            val n = ref(dead)
            return pick("mock", listOf("skill issue", "$lol", "nice one $n", "sure $n", "excuses", "$n always has an excuse") +
                if (fell) listOf("nice jump $n", "u fell $lol", "lag sure $lol", "void diff") else listOf("get good $n", "u got outplayed $n"),
                BotPersonality.TOXIC)
        }

        // ------------------------------------------------------------ answering chat

        fun gg(p: BotDialogue.Fighter) = ref(p).let { n -> say("gg", listOf("gg", "gg $n", "gf", "ggs"), listOf("gg", "gg $n", "gg wp", "gf $n", "ggs")) }
        fun greet(p: BotDialogue.Fighter) = ref(p).let { n ->
            say("greet", listOf("yo", "hi", "sup $n", "gl u need it $lol", "yo $n"), listOf("hey $n", "yo gl", "hi gl hf", "sup"))
        }
        fun thanks(p: BotDialogue.Fighter) = say("thanks", listOf("ik", "ty", "thx $lol", "i know"), listOf("ty", "thx ${ref(p)}", "ty u too"))
        fun forgive(p: BotDialogue.Fighter) = say("forgive", listOf("ur fine", "its fine", "ok", "sure $lol"), listOf("np", "all good", "ur good ${ref(p)}", "no worries"))
        fun kit() = listOf("$myKit", "im $myKit", "$myKit rn", "playing $myKit").let { say("kit", it, it) }
        fun lives(p: BotDialogue.Fighter) =
            listOf("u have ${p.lives} i have ${bot.lives}", "${p.lives} to ${bot.lives}", "im on ${bot.lives} ur on ${p.lives}").let { say("lives", it, it) }

        fun sameTeam(p: BotDialogue.Fighter) = say("team", listOf("were on the same team $lol", "$bro im ur teammate", "wasnt me were teamed"),
            listOf("were teammates ${ref(p)}", "not me im on ur team"))
        fun neverHit(p: BotDialogue.Fighter) = say("neverhit",
            listOf("i havent even hit u", "i didnt touch u", "wasnt me", "$bro i havent hit u once", "im not even near u"),
            listOf("wasnt me ${ref(p)}", "i havent hit u", "not me $lol"))
        fun manyHit(p: BotDialogue.Fighter, attackers: Int) = say("manyhit",
            listOf("$attackers of us hit u its ffa", "its ffa $bro", "ur just in the middle of it $lol", "$attackers ppl hit u thats not a team thats ffa",
                "everyone hits everyone its not teaming"),
            listOf("its just a messy fight ${ref(p)}", "$attackers of us were there not teaming", "ffa things $lol"))
        fun opponents(p: BotDialogue.Fighter) = say("opponents",
            listOf("its ffa", "ur the closest", "im allowed to hit u $lol", "thats the game $bro", "cry", "stop being close to me then", "nobody else is near me"),
            listOf("ur just closest $lol", "nothing personal ${ref(p)}", "its ffa sorry"))

        fun ownSpam(ability: String, count: Int) = move(ability).let { a ->
            say("ownspam", listOf("it works", "$a is my kit $bro", "its called a kit", "and it keeps hitting u", "dodge it then",
                "used it $count times and u still get hit $lol", "skill issue", "cope"),
                listOf("ya i used $a a lot $lol", "fair $lol", "my bad its all i got", "$a is fun tho"))
        }
        fun theirSpam(ability: String, count: Int) = move(ability).let { a ->
            say("theirspam", listOf("u used $a $count times $lol", "says the $a spammer", "$count ${a}s and im the spammer", "look whos talking", "u literally spam $a"),
                listOf("u use $a a lot too $lol", "we both spam tbh"))
        }
        fun notCamping(p: BotDialogue.Fighter) = say("notcamping",
            listOf("im not camping", "come here then", "im right here", "what spam", "then fight me", "u come to me"),
            listOf("im coming $lol", "ok lets fight", "not camping just lost $lol"))

        fun fightWhileTheyAreOut() = say("fight.theyout", listOf("ur dead $lol", "ur not even alive rn", "respawn first"), listOf("when ur back", "after u respawn $lol"))
        fun fightWhileOut() = say("fight.imout", listOf("im dead rn chill", "let me respawn", "one sec im dead"), listOf("one sec respawning", "brb"))
        fun fight(p: BotDialogue.Fighter) = say("fight",
            listOf("ok", "bet", "come here then", "im coming", "ok bet", "say less", "pull up", "$myKit vs ${BotSlang.kit(p.kit)} lets go", "dont run then", "u first"),
            listOf("ok coming", "lets go", "omw", "sure ${ref(p)}", "bet"))

        fun oneKill(ability: String) = move(ability).let { a ->
            say("onekill", listOf("one kill and ur typing", "u got one kill relax", "ok and", "congrats on the one kill", "wow one kill") +
                if (named(ability)) listOf("$a is carrying u", "u hit one $a", "lucky $a") else emptyList(),
                listOf("ya u got me", "fair $lol", "that was a good one") + if (named(ability)) listOf("nice $a") else emptyList())
        }
        fun youDied(ability: String) = move(ability).let { a ->
            say("youdied", listOf("u just died to me", "didnt u just die", "ur the one who died $lol", "says the dead guy") +
                if (named(ability)) listOf("$bro u died to $a", "i just killed u with $a") else emptyList(),
                listOf("i did just get u tho $lol", "gf tho", "we both got hits in"))
        }
        fun youAreOut() = say("youout", listOf("ur out", "ur literally dead", "ur spectating $lol", "talking from spectator $lol", "0 lives and still typing"),
            listOf("gg u played well", "ggs"))
        fun iAmOut(p: BotDialogue.Fighter) = say("imout", listOf("ya im out whatever", "ok u won that", "rematch", "run it back", "gg i guess"),
            listOf("u got me gg", "gg wp ${ref(p)}"))
        fun behind(p: BotDialogue.Fighter) = say("behind",
            listOf("ur up ${p.lives} to ${bot.lives} relax", "${p.lives} to ${bot.lives} its not over", "ok ur ahead by ${p.lives - bot.lives}",
                "${p.lives} to ${bot.lives} game isnt over $bro"),
            listOf("ya ur ahead ${p.lives} to ${bot.lives}", "ur winning rn ${p.lives} to ${bot.lives} nice"))
        fun ahead(p: BotDialogue.Fighter) = say("ahead",
            listOf("ur down ${p.lives} to ${bot.lives} $lol", "${p.lives} lives to my ${bot.lives} and its ez?", "u have ${p.lives} lives $bro",
                "ez? ur losing ${p.lives} to ${bot.lives}"),
            listOf("im up ${bot.lives} to ${p.lives} but its close", "its ${bot.lives} to ${p.lives} still anyones game"))
        fun tied() = say("tied",
            listOf("were both on ${bot.lives}", "its tied ${bot.lives} to ${bot.lives} $bro", "${bot.lives} to ${bot.lives} relax", "both on ${bot.lives} win first then talk"),
            listOf("were tied ${bot.lives} each", "tied on ${bot.lives} rn gl"))

        fun notHacking() = say("nothacking",
            listOf("im legit ur just bad", "$lol no", "not hacking", "blame the hitbox", "get good", "everyone who kills u is hacking?", "report me then", "cope", "its called aim"),
            listOf("nah just lucky", "no hacks just $myKit", "lol no", "wish i was that good"))
        fun calledBot() = say("calledbot", listOf("ur the bot $lol", "beep boop", "says the bot", "ok bot"), listOf("beep boop $lol", "maybe", "$lol"))
        fun kitAgain(p: BotDialogue.Fighter) = say("kitagain", listOf("im on $myKit deal with it", "$myKit is fun", "ya $myKit", "and ur on ${BotSlang.kit(p.kit)}"),
            listOf("ya i like $myKit", "$myKit this round"))
        fun notFun() = say("notfun", listOf("im having fun", "winning is fun", "sounds like a u problem", "then leave $lol", "its fun for me"),
            listOf("we can switch kits next round", "sorry $lol", "its just a game"))

        fun aboutSpam(ability: String) = move(ability).let { a -> say("about.spam", listOf("the $a", "im talking about $a", "$a $bro", "u know what"), listOf("the $a $lol", "i meant $a")) }
        fun aboutTargeting() = say("about.target", listOf("bc its ffa", "bc ur there", "nothing personal"), listOf("just the fight", "no hard feelings"))
        fun aboutFighting() = say("about.fight", listOf("then come", "stop typing and fight", "less typing more fighting", "im waiting"), listOf("ready when u are", "lets go then"))
        fun aboutFun() = say("about.fun", listOf("to win", "same reason as u", "bc winning"), listOf("were both trying to win $lol"))
        fun aboutScore(p: BotDialogue.Fighter) = say("about.score", listOf("this round ${p.lives} to ${bot.lives}", "the score ${p.lives} to ${bot.lives}"),
            listOf("this round i mean ${p.lives} to ${bot.lives}", "the score ${p.lives} to ${bot.lives}"))

        // ------------------------------------------------------------ somebody it has played before ([Acquaintance])
        //
        // Every number in these is one the bot remembers: how often they killed each other in the
        // last match they were both in, and who won it. [fresh] is whether that was a moment ago.

        /** The match is starting and [p] is in it again. */
        fun startKnown(p: BotDialogue.Fighter, a: Acquaintance, fresh: Boolean): String? {
            val n = ref(p); val k = a.killedMe; val mine = a.killedThem
            val last = if (fresh) "last game" else "last time"
            return when {
                // (Toxic and salty ones mean it: see [BotDialogue.grudge]. Every line of theirs here says so.)
                a.farmedMe -> say("known.farmed",
                    listOf("$n ur first", "im coming for u first $n", "payback time $n", "$n u killed me $k times $last ur first",
                        "not this time $n", "$n again ok bet / ur first", "run $n", "$k times $last $n / not happening again"),
                    listOf("gl $n go easy this time $lol", "$n pls not $k times again $lol", "round 2 $n gl", "ok $n im ready this time", "gl again $n"),
                    listOf("ok $n u killed me $k times $last ur first", "im not letting $n farm me again", "$n im so done with u / ur first",
                        "not again $n im coming for u", "$k times $last $n / never again", "$n ur not doing that to me twice"))
                a.farmedThem -> say("known.owned",
                    listOf("$n back for more $lol", "another free one", "$n again ez", "u want another L $n", "$mine times $last $n / want more",
                        "$n didnt learn $lol", "free kills are back"),
                    listOf("gl $n rematch", "round 2 $n", "gl again $n", "gl $n ill go easy $lol", "rematch time gl"),
                    listOf("ok maybe i win again", "pls let it go like $last", "$n again ok that went fine $last", "gl i guess"))
                a.theyWon && a.theirStreak >= 2 -> say("known.streak",
                    listOf("$n isnt winning again", "someone stop $n", "everyone get $n first", "${a.theirStreak} in a row $n / not today",
                        "$n has won ${a.theirStreak} in a row get them", "ok $n ur streak ends here"),
                    listOf("$n going for ${a.theirStreak + 1} in a row $lol gl", "gl $n ur on a streak", "can someone beat $n this time $lol", "gl all $n is on fire"),
                    listOf("$n wins every game", "watch $n win again", "$n has won ${a.theirStreak} in a row its so over", "how does $n keep winning",
                        "not $n again"))
                a.theyWon -> say("known.lost",
                    listOf("rematch $n", "u got lucky $last $n", "$n again ok", "not losing to $n twice", "run it back $n", "$n won one game and came back $lol"),
                    listOf("gg $last $n gl", "gl again $n", "rematch gl $n", "$n again gl hf", "yo $n gl"),
                    listOf("not $n again", "$n again great", "pls no repeat of $last", "ugh $n won $last", "$n pls let someone else win"))
                a.iWon -> say("known.won",
                    listOf("another one for me", "$n wants a rematch $lol", "back for more $n", "same result as $last gl $lol", "ez $last ez now"),
                    listOf("gg $last gl all", "gl $n rematch", "gl again", "that was fun $last gl"),
                    listOf("ok i won $last pls again", "one more win pls", "gl i guess", "finally a lobby i can win in"))
                else -> say("known.again",
                    listOf("$n again", "oh its $n", "$n again $lol", "yo $n", "this guy again", "gl $n u need it"),
                    listOf("yo $n again gl", "hey $n gl", "gl again $n", "$n gl hf", "oh hey $n"),
                    listOf("$n again", "oh no $n", "gl i guess $n", "pls be nice this time $n", "not this lobby again"))
            }
        }

        /** This bot has just killed [p], who it had a score to settle with: they ran their mouth last time, or kept killing it. */
        fun payback(p: BotDialogue.Fighter, a: Acquaintance, fresh: Boolean): String? {
            val n = ref(p); val last = if (fresh) "last game" else "last time"
            return if (a.mouthed) say("payback.mouth",
                listOf("whos trash now $n", "still ez $n?", "say ez again $n", "u were saying $n", "not so loud now $n", "type something now $n $lol",
                    "all that talk $last $n"),
                listOf("got u back $n $lol", "there we go gf $n", "that one was for $last $lol", "ok were even $n"),
                listOf("thats for $last $n", "not so funny now $n", "see $n", "finally shut $n up", "u had a lot to say $last $n"))
            else say("payback.farm",
                listOf("thats for $last $n", "payback $n", "how does it feel $n", "told u $n", "not so ez this time $n", "one back $n"),
                listOf("got u back $n $lol", "finally got one on u $n", "ok were even $lol", "gf $n that was for $last"),
                listOf("finally", "finally got $n", "thats for $last", "see i can kill u too $n", "one back for the ${a.killedMe} $last"))
        }

        // ------------------------------------------------------------ the end of a match

        /** The match is over and somebody has said so already: a word after them. [won] is this bot's own result. */
        fun after(won: Boolean): String? = if (won) say("after.won",
                listOf("gg", "ggs", "ez", "gg ez", "too ez", "gg $lol"), listOf("gg", "ggs", "gg wp", "gg all", "gf all", "that was fun"),
                listOf("gg", "finally", "gg finally", "ok gg"))
            else say("after.lost",
                listOf("gg", "ggs", "gg i guess", "whatever", "bs", "lucky", "k"),
                listOf("gg", "ggs", "gg wp", "wp", "gg all", "gf", "close one gg"),
                listOf("gg", "gg i guess", "ofc", "im so bad", "ugh", "nvm gg"))

        /** Asking for another one. */
        fun rematch(): String? = say("rematch",
            listOf("rematch", "run it back", "again", "one more", "1 more", "rematch that was bs", "again i wasnt trying"),
            listOf("one more?", "again?", "rematch?", "that was fun one more", "1 more?", "go again"),
            listOf("one more i wasnt ready", "rematch that didnt count", "again", "1 more and no camping", "one more i need a win", "again pls"))

        /** This one is not staying for the next match. */
        fun leaving(): String? = say("leaving",
            listOf("gtg", "im out", "gtg gg", "im off", "bye $lol", "last one for me", "gotta go"),
            listOf("gtg gg all", "last one for me gg", "gotta go cya", "gtg cya", "im off gn", "gg cya all"),
            listOf("im done", "im done with this game", "nah im off", "gtg", "im out", "last one im so done", "ok im leaving"))

        /** What it thinks of the map that was just played. */
        fun mapMoan(map: String): String? = map.lowercase().let { m -> say("mapmoan",
            listOf("$m is so bad", "who picked $m", "trash map", "never $m again", "$m $lol"),
            listOf("$m is fun", "i like $m", "good map", "$m again?"),
            listOf("i hate $m", "this map is so bad", "pls not $m again", "i always lose on $m", "who keeps picking $m")) }

        // ------------------------------------------------------------ the lobby, between matches

        /** [p] has come into the lobby this bot is standing in. [a] is what it remembers of them, if anything. */
        fun lobbyHello(p: BotDialogue.Fighter, a: Acquaintance?, fresh: Boolean): String? {
            val n = ref(p)
            if (a == null) return say("lobby.hello",
                listOf("yo", "sup", "yo $n", "hi", "sup $n", "new guy $lol", "finally someone"),
                listOf("hi", "hey $n", "yo", "hi $n", "hey", "welcome $n", "sup", "hey hey"),
                listOf("hi", "yo", "hey", "hi $n", "pls be a normal lobby", "finally someone"))
            val last = if (fresh) "last game" else "last time"
            return when {
                a.farmedMe -> say("lobby.hello.farmed",
                    listOf("$n again", "oh its $n", "rematch $n", "$n im getting u back", "ur not farming me again $n"),
                    listOf("oh hey $n", "yo $n go easy on me $lol", "hey $n rematch?", "wb $n", "$n $lol hi"),
                    listOf("not $n again", "oh no its $n", "ugh $n", "$n pls dont target me this time", "$n killed me ${a.killedMe} times $last"))
                a.theyWon -> say("lobby.hello.lost",
                    listOf("$n again", "rematch $n", "u got lucky $last $n", "oh look who it is", "yo $n ur not winning again"),
                    listOf("yo $n gg $last", "hey $n", "wb $n", "oh hey $n", "hi $n rematch?"),
                    listOf("oh its $n", "$n again", "hi $n", "ugh $n won $last", "not $n again"))
                a.farmedThem || a.iWon -> say("lobby.hello.won",
                    listOf("$n back for more $lol", "yo $n want another L", "oh $n again $lol", "$n $lol", "back already $n"),
                    listOf("hey $n gg $last", "yo $n", "wb $n", "hi $n again", "hey $n rematch?"),
                    listOf("hi $n", "oh $n", "yo $n", "$n again ok", "hi again"))
                else -> say("lobby.hello.again",
                    listOf("yo $n", "$n again", "sup $n", "oh its $n", "back again $n"),
                    listOf("hey $n", "wb $n", "yo $n", "hi again $n", "oh hey $n"),
                    listOf("hi $n", "$n again", "oh hi", "yo"))
            }
        }

        /** A second bot saying hello after the first. */
        fun lobbyHi(): String? = say("lobby.hi", listOf("yo", "sup", "hi", "hey"), listOf("hi", "hey", "yo", "hello", "heyy"), listOf("hi", "yo", "hey"))

        /**
         * Back in the lobby after a match this bot was in. [winner] is who won it (null: this bot
         * did), [kills] and [deaths] its own, and [nemesis] whoever killed it most, [times] times.
         */
        fun lobbyAfter(winner: BotDialogue.Fighter?, kills: Int, deaths: Int, nemesis: BotDialogue.Fighter?, times: Int): String? {
            val w = winner?.let(::ref)
            val x = nemesis?.takeIf { times >= 2 && it.id != winner?.id }?.let(::ref)
            val toxic = (if (w == null) listOf("ez game", "that was free", "too ez", "carried that lobby", "yall made that ez") +
                    (if (kills >= 2) listOf("$kills kills $lol") else emptyList())
                else listOf("$w got lucky", "$w only won bc everyone fought me", "rematch $w", "$w wont win again", "that game was bs")) +
                if (x != null) listOf("$x only went for me", "$x killed me $times times / ur first next game", "why u targeting me $x") else emptyList()
            val chill = (if (w == null) listOf("that was fun", "that was a good one", "ggs all", "close game")
                else listOf("gg $w", "$w was cracked", "wp $w that was clean", "$w deserved that one", "good game $w")) +
                if (x != null) listOf("$x got me $times times $lol", "$x u had my number") else emptyList()
            val salty = (if (w == null) listOf("finally won one", "ok that felt good", "about time", "finally a good game")
                else listOf("i was so close", "$w always wins", "i had no chance", "that game was so bad") +
                    (if (deaths >= 2) listOf("i died $deaths times how") else emptyList())) +
                if (x != null) listOf("$x killed me $times times", "$x wouldnt leave me alone", "$x targeted me all game", "why always me $x") else emptyList()
            return say("lobby.after", toxic, chill, salty)
        }

        /** Standing about in the lobby waiting for [host] to start the next one. */
        fun lobbyWait(host: BotDialogue.Fighter): String? {
            val h = ref(host)
            return say("lobby.wait",
                listOf("start", "start already", "$h start", "can we go", "start the game $h", "who are we waiting on", "im winning this one",
                    "hurry up $h", "go", "this lobby is slow"),
                listOf("ready when u are", "ready", "im ready", "gl next one everyone", "whenever ur ready $h", "lets go", "r"),
                listOf("can we start", "start pls", "$h pls start", "i need a win", "pls nobody target me this time", "im gonna lose again arent i",
                    "ready i guess", "can we go already"))
        }

        /** About the map: [last] is the one that was just played, if one was. */
        fun lobbyMap(last: String?): String? = last?.lowercase().let { m ->
            if (m == null) say("lobby.map", listOf("what map", "pick a good map", "no trash maps pls"), listOf("what map we playing", "any map is fine", "what map"),
                listOf("what map", "pls a small map", "pls not a huge map", "not a void map pls"))
            else say("lobby.map.last", listOf("not $m again", "different map this time", "$m again and im leaving $lol", "any map but $m"),
                listOf("$m again?", "different map?", "$m was fun", "new map this time?"),
                listOf("pls not $m again", "anything but $m", "if its $m again im done", "i hate $m pick something else"))
        }

        /** A word from a second bot after something another one said in the lobby. [sour]: that was a complaint or a boast. */
        fun lobbyEcho(sour: Boolean): String? = if (sour) say("lobby.echo.sour",
                listOf("cope", "$lol", "nobody asked", "skill issue", "cry", "sure $lol", "excuses"),
                listOf("lol", "it was close tho", "next one", "gg tho", "u played fine", "happens"),
                listOf("same", "fr", "same here", "ikr", "this game $lol"))
            else say("lobby.echo", listOf("fr", "same", "real", "go", "ya start", "$lol"), listOf("same", "ya", "lol", "fr", "ready too"), listOf("same", "fr", "ya pls", "ikr"))

        /** [p] asked whether everybody is ready, or for another match. */
        fun lobbyReady(p: BotDialogue.Fighter): String? = ref(p).let { n -> say("lobby.ready",
            listOf("ready", "been ready", "start it", "go", "bet", "r", "ya start", "im ready hurry up", "lets go $n"),
            listOf("ready", "yep", "im ready", "lets go", "ya go ahead", "ready $n", "r"),
            listOf("ready i guess", "ok", "ya", "fine one more", "ok but no camping", "ready", "k go")) }

        /** [p] is talking trash in the lobby. [a] is what this bot remembers of them. */
        fun lobbyComeback(p: BotDialogue.Fighter, a: Acquaintance?, fresh: Boolean): String? {
            val n = ref(p); val last = if (fresh) "last game" else "last time"
            val facts: Triple<List<String>, List<String>, List<String>> = when {
                a == null -> Triple(emptyList(), emptyList(), emptyList())
                a.killedThem > a.killedMe && a.killedThem >= 2 -> Triple(
                    listOf("i killed u ${a.killedThem} times $last", "u died to me ${a.killedThem} times relax", "${a.killedThem} to ${a.killedMe} $last $n"),
                    listOf("i did get u ${a.killedThem} times $last tho $lol"),
                    listOf("i killed u ${a.killedThem} times $last so", "u died to me ${a.killedThem} times and im the bad one"))
                a.theyWon -> Triple(
                    listOf("one win relax", "do it again then", "u won one game $n", "lucky game and now ur typing"),
                    listOf("ya u won $last gg", "u were good $last ngl", "fair u did win $lol"),
                    listOf("u got lucky $last", "ok u won one game", "ya u won $last i know"))
                a.iWon -> Triple(
                    listOf("i won $last $lol", "who won $last again", "says the one who lost $last"),
                    listOf("i did win $last tho $lol"),
                    listOf("i literally won $last", "i won $last and im still getting this"))
                else -> Triple(emptyList(), emptyList(), emptyList())
            }
            return say("lobby.comeback",
                listOf("ok $n", "talk in game $n", "say that after the game", "shut up $n $lol", "we will see", "keep typing $n", "1v1 me $n", "prove it") + facts.first,
                listOf("lol ok $n", "we will see $lol", "relax $n its a game", "ok ok $lol", "gl then $n") + facts.second,
                listOf("leave me alone $n", "ok $n", "why are u like this", "we havent even started", "wow ok", "whatever $n") + facts.third)
        }

        /** [asker] asked who won the last match. [winner] is null when this bot did. */
        fun lobbyWhoWon(winner: BotDialogue.Fighter?, asker: BotDialogue.Fighter): String? {
            if (winner == null) return say("lobby.whowon.me", listOf("me", "me $lol", "i did", "me obviously"), listOf("me $lol", "i did somehow", "me"),
                listOf("me finally", "i did", "me for once"))
            if (winner.id == asker.id) return say("lobby.whowon.you", listOf("u did relax", "u", "u won one game calm down"), listOf("u did gg", "u $lol gg", "u wp"),
                listOf("u did", "u ofc", "u again"))
            val w = ref(winner)
            return say("lobby.whowon", listOf("$w", "$w got lucky", "$w somehow"), listOf("$w did", "$w gg", "$w won"), listOf("$w", "$w ofc", "not me"))
        }

        /** Somebody asked what this bot is going to play. */
        fun lobbyKit(): String? = say("lobby.kit", listOf("not telling", "u will see", "something that beats u", "idk yet", "random"),
            listOf("idk yet", "random prob", "not sure yet", "whatever i get"), listOf("idk", "whatever doesnt lose", "random i guess", "idk yet"))

        /** [p] says they are going. */
        fun lobbyBye(p: BotDialogue.Fighter): String? = ref(p).let { n -> say("lobby.bye", listOf("bye", "cya", "bye $lol", "later", "finally $lol"),
            listOf("cya $n", "bye $n", "gn", "cya gg", "later $n"), listOf("bye", "cya", "ok bye", "everyone leaves")) }

        /** This bot has just walked into a lobby where the others have come back from a match. */
        fun lobbyJoined(): String? = say("lobby.joined", listOf("yo", "sup", "who wants to lose", "room for one more", "yo whats this lobby"),
            listOf("hi", "hey all", "yo", "hi all", "hey can i join", "room for one more?"), listOf("hi", "yo", "hey", "hi is this lobby any good"))

        /** Somebody said something to this bot by name that it can make nothing of. */
        fun lobbyWhat(): String? = say("lobby.what", listOf("?", "what", "wdym", "huh"), listOf("?", "wdym", "what $lol", "hm?"), listOf("what", "?", "huh"))
    }
}
