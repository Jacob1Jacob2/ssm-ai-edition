package dev.betrix.superSmashMobsBrawl.bots

import java.util.UUID

/**
 * Two teams mode: what a player asks of the bots on their own side, read off a chat line.
 *
 * A teammate is somebody to work with, so what they type is first read as something they want
 * done ("come in the cave so we can spam", "get the pig", "fall back") and only then as small
 * talk. Nothing typed is executed: a line is matched against the handful of things a bot knows
 * how to do, and anything else is left to the ordinary chat.
 */
object TeamTalk {
    enum class Ask {
        /** Come to the player and stay with them. */
        COME,
        /** Go after one opponent first ([Order.target]; nobody named means the one the player is fighting). */
        FOCUS,
        /** Break off and get out of the fight. */
        RETREAT,
        /** Stay put and fight from there. */
        HOLD,
        /** Go in and fight. Ends whatever was asked before. */
        PUSH,
        /** Never mind: back to playing as the bot sees fit. */
        FREE,
        /** Said to this bot by name, but nothing it knows how to do: better to ask than to guess. */
        UNCLEAR,
    }

    /** A fighter as chat can refer to them: by name, a part of it, or the kit they play. */
    data class Named(val id: UUID, val name: String, val kit: String)

    /** [to] is who it was said to: the teammates named in the line, or every one of them when none is. */
    data class Order(val ask: Ask, val to: List<UUID>, val target: UUID? = null)

    /**
     * Something a bot was asked and is doing until [until] (the match clock, in milliseconds).
     * [leader] is who asked, [spot] where a HOLD is.
     */
    data class Standing(val ask: Ask, val leader: UUID, val target: UUID?, val until: Long,
                        val spot: Triple<Double, Double, Double>? = null)

    /** How long each thing is kept up when nothing replaces it, in milliseconds. */
    fun lasts(ask: Ask): Long = when (ask) {
        Ask.COME -> 60_000
        Ask.FOCUS -> 45_000
        Ask.HOLD -> 45_000
        Ask.RETREAT -> 10_000
        else -> 0
    }

    private val NOT_FOLLOW = Regex("\\b(dont|do not|stop|quit|no need to) (come|coming|follow|following|chasing|wait|waiting|hold|holding|stay|staying)\\b")
    private val FREE = Regex("\\b(nvm|nevermind|never mind|forget it|do (your|ur|you|u) (own )?thing|do whatever|go wild|split up|spread out|(youre|ur|u are|you are) free)\\b")
    private val RETREAT = Regex("\\b(run|retreat|fall back|back (up|off|out)|get (out|back|away)|leave (him|her|them|it)|dont fight|stop fighting|disengage|play (it )?safe|be careful|go heal|heal up|pull out|bail)\\b")
    private val COME = Regex("\\b(come(?! on\\b)|cmere|follow|to me|with me|on me|near me|by me|stick (with|together|to|close)|stay (with|close|near|by|together)|group|regroup|together|get (in|over|up|down) here|get (in|here)|pull up|this way|help|assist|back me up|cover me|protect me|save me|need (you|u)|where (are|r) (you|u)|(over|right|in|down|up) here|im alone|im getting (jumped|teamed|doubled)|2v1|(theyre|they are|both|all|two|2) (both |all )?on me)\\b")
    private val HOLD = Regex("\\b(stay( here| there| put| back)?|hold( here| position| on| up)?|wait (here|there|up|for me)|dont move|stop moving|camp( here| there)?|stand still)\\b|^wait$")
    private val FOCUS_VERB = Regex("\\b(target|focus|get|kill|attack|hit|rush|jump|push|go|team|all on|everyone on|both on|on|finish|chase|take|fight|spam|shoot|low|weak|one shot|almost dead|dying|first|help)\\b")
    private val FOCUS_THEM = Regex("\\b(get|kill|attack|hit|focus|target|rush|jump|spam|shoot|finish|chase|team on|all on|both on|help me (kill|with|get|on)) (him|her|them|that|this|it|em|my (guy|target|man|one)|the same( one| guy)?)\\b" +
        "|\\b(hes|shes|theyre|he is|she is|they are|its|this one is|that one is) (so |really |very |almost )?(low|weak|one shot|one hit|dead|dying|half)\\b")
    private val PUSH = Regex("\\b(go|push|attack|fight|all in|charge|send it|lets go|get (them|em))\\b|^now$")
    private val SMALL_TALK = Regex("\\b(gg|ggs|gf|wp|nice|lol|lmao|sorry|sry|ty|thanks|thx|hi|hey|hello)\\b")

    /** Words that are parts of some names but in a sentence are just words. */
    private val COMMON = setOf("just", "that", "this", "with", "here", "come", "kill", "they", "them", "your", "youre", "help",
        "back", "stay", "wait", "hold", "push", "team", "blue", "dont", "stop", "nice", "good", "what", "when", "then", "have",
        "been", "only", "play", "game", "dead", "life", "live", "lives", "kits", "fight", "cave", "spam", "over", "there", "where")

    /** Lowercase, apostrophes dropped ("don't" is "dont"), everything that is not a letter, digit or underscore a space. */
    internal fun normal(message: String): String = message.take(256).lowercase().replace("’", "").replace("'", "")
        .replace(Regex("[^a-z0-9_]+"), " ").trim()

    private fun plain(name: String) = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    /** The pieces a name is made of: "_EternalHorror_" is eternal and horror, "Charlie_Skirk" charlie and skirk. */
    private fun pieces(name: String): Set<String> =
        Regex("[A-Z]?[a-z]+|[A-Z]+(?![a-z])").findAll(name).map { it.value.lowercase() }.filter { it.length >= 4 && it !in COMMON }.toSet()

    /** Does one of [words] mean [who]? Their name, the start of it, or a piece of it that nobody else of [among] shares. */
    private fun refers(words: List<String>, who: Named, among: List<Named>): Boolean {
        val full = plain(who.name)
        if (full.isEmpty()) return false
        return words.any { raw ->
            val word = raw.filter { it != '_' }
            when {
                word.length < 3 -> false
                word == full -> true
                word.length < 4 || word in COMMON -> false
                word in pieces(who.name) -> among.count { word in pieces(it.name) } == 1
                else -> full.startsWith(word) && among.count { plain(it.name).startsWith(word) } == 1
            }
        }
    }

    /** "the pig", "golem": one of [words] is a word of the kit [who] plays. */
    private fun byKit(words: List<String>, who: Named): Boolean =
        who.kit.lowercase().split(' ').any { it.length >= 3 && it in words }

    /**
     * Reads [message], typed by a player whose bot teammates are [teammates] and whose opponents
     * are [opponents]. Null when it asks nothing of the team.
     */
    fun parse(message: String, teammates: List<Named>, opponents: List<Named>): Order? {
        if (teammates.isEmpty()) return null
        val text = normal(message)
        if (text.isEmpty()) return null
        val words = text.split(' ')
        val named = teammates.filter { refers(words, it, teammates) }
        // An opponent by name, or failing that by the one kit that is meant.
        val foe = opponents.filter { refers(words, it, opponents) }.singleOrNull()
            ?: opponents.filter { byKit(words, it) }.singleOrNull()
        fun has(pattern: Regex) = pattern.containsMatchIn(text)
        val ask = when {
            has(NOT_FOLLOW) || has(FREE) -> Ask.FREE
            // "get cqsey", "cqsey is low", or just the name: but not "gg cqsey".
            foe != null && (has(FOCUS_VERB) || words.size <= 3 && !has(SMALL_TALK)) -> Ask.FOCUS
            has(RETREAT) -> Ask.RETREAT
            has(COME) -> Ask.COME
            has(HOLD) -> Ask.HOLD
            has(FOCUS_THEM) -> Ask.FOCUS
            has(PUSH) -> Ask.PUSH
            named.isNotEmpty() -> Ask.UNCLEAR
            else -> return null
        }
        return Order(ask, named.ifEmpty { teammates }.map { it.id }, foe?.id.takeIf { ask == Ask.FOCUS })
    }
}
