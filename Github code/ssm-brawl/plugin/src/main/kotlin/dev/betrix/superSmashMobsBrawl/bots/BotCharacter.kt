package dev.betrix.superSmashMobsBrawl.bots

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Who a bot is, in whichever lobby or match it turns up: fixed by its name, the way a player one
 * keeps meeting is the same person every time. Somebody who was all trash talk in one match and
 * all "gg wp" in the next is nobody at all.
 */
class BotCharacter private constructor(name: String) {
    private val r = Random(seedOf(name))

    /**
     * How it is in a lobby where every bot is itself (the Random setting). Nobody is Silent by
     * nature: a quiet one is somebody who hardly ever says anything ([chatty]), and when it does it
     * is in the same voice as always. (There used to be Silent ones, and a rule that no more than
     * half a lobby may be: the same bot then talked trash in one match and said nothing in the next.)
     */
    val nature: BotPersonality = r.nextInt(100).let {
        when {
            it < 34 -> BotPersonality.TOXIC
            it < 66 -> BotPersonality.SALTY
            else -> BotPersonality.CHILL
        }
    }

    /** The share of what it could remark on that it does: one in five hardly says a word, some never stop. */
    val chatty: Double = listOf(0.12, 0.12, 0.35, 0.55, 0.75, 0.9, 1.0, 1.0, 1.0, 1.0).random(r)

    /** How likely it is to stay in the lobby for another match when one is over. */
    val stays: Double = listOf(0.55, 0.65, 0.75, 0.8, 0.9).random(r)

    /** Where it stands in the range of levels of whatever difficulty it plays at: 0 the bottom, 1 the top. */
    val standing: Double = r.nextDouble()

    // What it says of itself when asked, the same every time ([ChatPrompt]). (These come last: the
    // ones above are rolled in this order, and a new one in between would change who everybody is.)
    /** The year it started playing. */
    val since: Int = 2014 + r.nextInt(9)
    /** Where it is from, as loosely as anybody says that to a stranger in a lobby. */
    val from: String = listOf("the us", "the us", "the us", "the us", "canada", "the uk", "the uk", "australia", "germany", "sweden").random(r)
    /** A way of typing that is its own, if it has one. */
    val habit: String = listOf("", "", "", "you stretch a word now and then, like nooo or brooo", "you mostly type just one or two words",
        "you ask short questions a lot", "you say ngl and fr a lot", "now and then you send just ? or ??", "you hardly ever use anybody's name").random(r)

    companion object {
        private val known = ConcurrentHashMap<String, BotCharacter>()

        fun of(name: String): BotCharacter = known.getOrPut(name.lowercase()) { BotCharacter(name.lowercase()) }

        /** The same number for the same name on every server and after every restart. */
        fun seedOf(name: String): Long = name.lowercase().fold(1125899906842597L) { h, c -> 31 * h + c.code }

        /** The personalities of [newcomers] (id and name) in a lobby where everybody is themselves. */
        fun deal(newcomers: List<Pair<UUID, String>>): Map<UUID, BotPersonality> = newcomers.associate { (id, name) -> id to of(name).nature }

        /**
         * How much of what it could say the bot called [name] does say with [bots] bots in the
         * lobby or match. With one or two there, even a quiet one says something now and then: a
         * lobby where nobody talks is no fun.
         */
        fun chattyAmong(name: String, bots: Int): Double = maxOf(of(name).chatty, when { bots <= 1 -> 0.75; bots == 2 -> 0.45; else -> 0.0 })

        /** One of [candidates], the talkative ones more often than the quiet ones. Null for nobody to pick from. */
        fun <T> pick(candidates: List<T>, random: Random, name: (T) -> String): T? {
            if (candidates.isEmpty()) return null
            val weights = candidates.map { of(name(it)).chatty.let { c -> c * c } }
            var roll = random.nextDouble() * weights.sum()
            for (i in candidates.indices) { roll -= weights[i]; if (roll <= 0) return candidates[i] }
            return candidates.last()
        }
    }
}
