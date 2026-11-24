package dev.betrix.superSmashMobsBrawl.bots

/**
 * What a language model wrote, before a bot says it. A model is not a script: it can run long, slip
 * into proper grammar, answer as the wrong person, make up a number, or come out with something
 * nobody on this server is to read. Whatever does not pass here is thrown away, and the scripted
 * line is said instead (or nothing, where the script had none).
 *
 * The rules are the ones the scripted lines were written to ([BotSlang]): lower case, no commas,
 * apostrophes or periods, short, about the game. Trash talk stays on the game: no slurs, nothing
 * sexual, nothing about anybody's real life, nobody told to hurt themselves.
 *
 * @param banned words and phrases that are never said, one to an entry, in any case. A word of four
 *   letters or more in it also bans the words that begin with it.
 * @param swearing the word filter is switched off (Bot Chat in Misc Settings): the plain swear
 *   words in [banned] may be said ([SWEARS]). That is all that switch lets through. Slurs, anything
 *   sexual, name-calling, anybody's family and telling somebody to hurt themselves stay out
 *   whatever it is set to, as they were asked to from the first line the bots ever said.
 */
class ChatFilter(banned: Collection<String> = emptyList(), swearing: Boolean = false) {
    sealed interface Verdict
    /** It can be said, and this is how it is typed. */
    data class Line(val text: String) : Verdict
    /** The model had the bot say nothing. */
    data object Silent : Verdict
    data class Rejected(val why: String) : Verdict

    private fun single(entry: String) = entry.all { it in 'a'..'z' || it in '0'..'9' }
    private val listed = (banned.map { it.trim().lowercase() } + if (swearing) emptyList() else CENSORED)
        .filter { it.isNotEmpty() && it !in ALLOWED && !(swearing && it in SWEARS) }.toSet()
    private val words = (listed + ALWAYS).filter(::single).toSet()
    // (Only the list's own words ban what begins with them: "hello" is not to go because of a four-letter word in [ALWAYS].)
    private val stems = listed.filter { single(it) && it.length >= 4 }
    private val phrases = (listed + ALWAYS).filterNot(::single)

    /**
     * A banned word or phrase in [text], or null. Also what a player typed is put through this
     * before a model is shown it. [names] are the people there: what somebody is called is on
     * everybody's screen anyway, and is not a word the bot chose.
     */
    fun banned(text: String, names: Collection<String> = emptyList()): String? {
        val low = text.lowercase()
        val flat = spaces.replace(low.replace(notWord, " "), " ").trim()
        // (A name, and the pieces a name with marks in it falls into.)
        val called = names.flatMap { name -> name.lowercase().let { listOf(it) + it.split(notWord) } }.toSet()
        for (token in flat.split(' ')) {
            if (token in ALLOWED || token in called) continue
            if (token in words) return token
            stems.firstOrNull { token.startsWith(it) }?.let { return it }
        }
        return phrases.firstOrNull { it in flat || it in low }
    }

    /**
     * [raw] as the bot called [speaker] may say it. [others] are the names of everybody else there
     * (a line that begins "kin:" is the model answering as kin). [facts] is everything the model was
     * told: a number in the line has to be in there.
     */
    fun check(raw: String, speaker: String, others: Collection<String>, facts: String): Verdict {
        var line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return Silent
        // "KinTheInfinite: ez" is the line "ez"; "HotCold: ez" from kin is somebody else's.
        val label = Regex("^([A-Za-z0-9_]{2,20})\\s*[:>]\\s*").find(line)
        if (label != null) {
            val who = label.groupValues[1]
            if (who.equals(speaker, true)) line = line.substring(label.range.last + 1)
            else if (others.any { it.equals(who, true) }) return Rejected("speaks as $who")
        }
        line = line.trim().trim('"', '“', '”', '*', '`').trim()
        if (line.isEmpty() || silence.matches(line)) return Silent
        val low = line.lowercase()
        leaks.firstOrNull { it in low }?.let { return Rejected("not a player's words (\"$it\")") }
        if (links.containsMatchIn(low)) return Rejected("a link or an address")
        // Typed the way they type: what is not a letter, a digit or one of a few marks goes, emoji included.
        // (And without the words a model likes and these players do not type: told not to say
        // "literally", the model went on saying it in one line in five.)
        val text = BotSlang.style(padding.replace(dropped.replace(low, " "), " "))
        if (text.isEmpty()) return Silent
        if (text.length > MAX_LENGTH || text.count { it == ' ' } >= MAX_WORDS) return Rejected("too long (${text.length} characters)")
        // (A bare number is an answer too, "2016" to "since when": whether it is a true one is looked at below.)
        // ("?" and "??" are whole messages in these lobbies.)
        if (!text.all { it == '?' } && text.count { it.isLetterOrDigit() } < (text.length + 1) / 2) return Rejected("not words")
        banned(text, others + speaker)?.let { return Rejected("a word that is not said here") }
        // Nobody here knows who is a he and who is a she: people are called by name, by kit, or "u".
        // (Told so, the model still wrote "finally got him".)
        if (gendered.containsMatchIn(text)) return Rejected("calls somebody he or she")
        // A number is a claim: lives, kills, seconds. It has to be one the model was given. ("1v1" is a challenge, not a count.)
        val known = numbers.findAll(facts).map { it.value }.toSet()
        numbers.findAll(text.replace("1v1", "")).map { it.value }.firstOrNull { it !in known }?.let { return Rejected("a number it was not given ($it)") }
        return Line(text)
    }

    companion object {
        const val MAX_LENGTH = 64
        const val MAX_WORDS = 13
        private val spaces = Regex("\\s+")
        private val notWord = Regex("[^a-z0-9]+")
        private val numbers = Regex("\\d+")
        private val gendered = Regex("\\b(he|she|him|her|hes|shes|his|hers|himself|herself)\\b")
        /** Everything but letters, digits, spaces and the few marks players use ([BotSlang.style] then takes the commas and periods). */
        private val dropped = Regex("[^a-z0-9 ?*/:()<>_,.'\\-]")
        private val padding = Regex("\\b(literally|honestly|actually|basically|seriously though)\\b")
        private val silence = Regex("^[-–—_. ]+$|^\\(?(nothing|silence|no reply|no response|none|n/a)\\)?\\.?$", RegexOption.IGNORE_CASE)
        private val links = Regex("https?:|www\\.|\\.(com|net|org|gg|io|tv)\\b|discord\\.|\\d{5,}")
        /** A model talking as itself, or about what it was asked to do. */
        private val leaks = listOf("as an ai", "an ai ", "language model", "assistant", "i cannot", "i can't help", "i cant help", "im sorry but",
            "i'm sorry, but", "chat message", "instructions", "system prompt", "roleplay", "role-play", "in character", "gemma", "openai", "chatgpt")
        /**
         * Mild enough for these lobbies though the word list has them, or only beginning like one of
         * its words: "Trade Scatter" is an ability.
         */
        private val ALLOWED = setOf("suck", "sucks", "sucked", "butt", "cocky", "scatter", "scattered")
        /** Swearing as such, at nobody: what the word filter being switched off lets through, and nothing else. */
        val SWEARS = setOf("ass", "shit", "shitty", "bullshit", "fuck", "fucking", "fucked", "fuckin", "piss", "pissed", "damn", "goddamn", "hell", "crap")
        /** The same with letters left out, which a list of whole words does not have. */
        private val CENSORED = listOf("fk", "fck", "fuk", "fking", "sht")
        /**
         * Never said, whatever list is loaded: telling somebody to hurt themselves, slurs a general
         * word list leaves out, anybody's family, and what a game lobby is not the place for.
         */
        private val ALWAYS = listOf("kys", "kms", "kill yourself", "kill urself", "kill ur self", "hang yourself", "hang urself", "neck yourself",
            "end yourself", "end urself", "go die", "die irl", "suicide", "suicidal", "self harm", "cut yourself", "cancer", "aids",
            "retard", "retards", "retarded", "tard", "autist", "autistic", "autism", "cripple", "downie", "gay", "gays", "lesbian", "queer", "homo",
            "trans", "nazi", "nazis", "hitler", "jew", "jews", "muslim", "muslims", "islam", "racist", "racism", "terrorist",
            "ur mom", "your mom", "ur mum", "your mum", "ur mother", "your mother", "ur dad", "your dad", "ur sister", "your sister",
            "ur family", "your family", "dox", "doxx", "doxxed", "ddos", "swat", "swatted", "ip address", "home address", "phone number",
            "snapchat", "instagram", "tiktok", "onlyfans", "paypal", "cashapp", "school shoot", "shoot up", "drugs", "weed", "vape", "drunk",
            // Calling somebody names is not talk about the game: "ur bad" is, "u idiot" is not.
            // (The first few are swear words turned on a person: out even where swearing is let through.)
            "asshole", "assholes", "asshat", "jackass", "fucker", "fuckers", "fuckface", "fucktard", "shithead", "shitface", "dipshit", "bitch", "bitches",
            // ("this is so dumb" is about the game, and stays; "ur dumb" is about a person.)
            "idiot", "idiots", "dumbass", "dummy", "dummies", "dimwit", "dunce", "imbecile", "moron", "morons", "ugly", "fat", "braindead", "brain dead",
            "clown", "worthless", "pathetic",
            "u stupid", "ur stupid", "you stupid", "youre stupid", "ur so stupid", "u r stupid", "stupid kid",
            "u dumb", "ur dumb", "you dumb", "youre dumb", "ur so dumb", "u r dumb", "dumb kid")
    }
}
