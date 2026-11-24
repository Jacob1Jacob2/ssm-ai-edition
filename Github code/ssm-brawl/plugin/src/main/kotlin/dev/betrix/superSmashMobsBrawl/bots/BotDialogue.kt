package dev.betrix.superSmashMobsBrawl.bots

import java.util.UUID
import kotlin.random.Random

/** Local, event-driven dialogue. No network requests, and no claims without match evidence.
 * All state belongs to one match; Bukkit is kept outside this class for deterministic testing.
 * This class decides who speaks, when, and about what; [BotLines] holds the wording.
 *
 * @param reactions how often bots voice optional reactions (kills, deaths, crystals, the match
 *   starting). Real players do not comment on everything; 1.0 makes them (used by tests).
 * @param tire how ready a bot that has this moment typed a line is to type another of its own
 *   accord ([fresh]); 1.0 for the bots as they were, when one of them could do most of the talking. */
class BotDialogue(private val random: Random = Random.Default, memory: LineMemory = LineMemory.shared,
                  private val reactions: Double = 0.65, private val tire: Double = TIRE) {
    /**
     * [side]: in a match of two teams, which one this fighter is on; nothing in a free-for-all,
     * where everybody is on their own. What a bot says has to fit it: seven of them against one
     * player have nothing to call unfair, and they do not turn on each other over it.
     */
    data class Fighter(val id: UUID, val name: String, val kit: String, val lives: Int,
                       val bot: Boolean, val fighting: Boolean = true, val side: String? = null)
    /**
     * What a line is about, in plain words, for a language model to put into the speaker's own
     * ([ChatPrompt]); the scripted line stays what is said when there is no model, or it writes
     * something that cannot be said. [event] holds nothing but what happened. [open]: the script
     * has no line for this at all (something a player typed that it does not understand), so
     * without a model nothing is said.
     */
    data class Brief(val event: String, val kind: Kind = Kind.REMARK) {
        /**
         * What the line is to the script, from what only a model can do to what the script does
         * well by itself. How far down this a model is let ([ChatModelService.Settings.scope])
         * depends on how good the model is: a small one answers what a player types better than no
         * answer, and rewords a hand-written taunt worse than it was.
         */
        enum class Kind {
            /** Something a player typed that the script has no line for: without a model, nothing is said. */
            OPEN,
            /** The same, said to the bot by name: the script's line is only a "what?". */
            UNSURE,
            /** The script's answer to something a player typed. */
            ANSWER,
            /** What a bot says of its own accord about the match. */
            REMARK,
            /**
             * What one bot answers another: a comeback, a "calm down", a "same here". The hand-written
             * ones are short and to the point, and a model that reworded them lost the thread of who
             * had said what to whom ("no way i was camping ducksicle", from the one who had been
             * accused of nothing; the user's match of 2026-11-24). So no model is let this far
             * unless it is asked for by name ([ChatModelService.Settings.scope] "banter").
             */
            BANTER
        }
        val open: Boolean get() = kind == Kind.OPEN
    }
    /**
     * [urgent]: somebody asked for this answer and is waiting for it, so it is typed now, not after the lines before it.
     * [brief]: only while [wordsmith] is on. [text] is empty for an open brief.
     */
    data class Reply(val speaker: UUID, val text: String, val urgent: Boolean = false, val brief: Brief? = null)
    data class Death(val victim: UUID, val killer: UUID?, val ability: String, val time: Long)
    private data class Cast(val player: UUID, val ability: String, val time: Long)
    private data class Hit(val attacker: UUID, val victim: UUID, val time: Long)
    private data class Topic(val bot: UUID, val subject: String, val time: Long)
    /** [other] is a second fighter involved (who speaks, or who is spoken about); [count] a measured number. */
    private data class Pending(val kind: String, val actor: UUID?, val detail: String,
                               val due: Long, val expires: Long, val priority: Int,
                               val other: UUID? = null, val count: Int = 0)
    /**
     * The second half of a two-message line, or the next line of a conversation between bots.
     * When it is sent, [speaker] and [allies] start going after [grudge] in the actual fight.
     */
    private data class Followup(val speaker: UUID, val text: String, val due: Long,
                                val grudge: UUID? = null, val allies: List<UUID> = emptyList(),
                                /** An answer somebody is waiting for (a teammate who asked for something): it does not queue behind other lines. */
                                val urgent: Boolean = false,
                                /** What the line is for, when a model may word it ([Brief]). */
                                val brief: Brief? = null)
    private val lines = BotLines(random, memory)
    private val casts = ArrayDeque<Cast>()
    private val hits = ArrayDeque<Hit>()
    private val deaths = ArrayDeque<Death>()
    private val topics = mutableMapOf<UUID, Topic>()
    private val lastBot = mutableMapOf<UUID, Long>()
    /** When each bot said each of its last lines, for [fresh]. */
    private val spoke = HashMap<UUID, ArrayDeque<Long>>()
    private val recentLines = ArrayDeque<String>()
    private val ambient = mutableMapOf<String, Long>()
    /** How many times each fighter has been called out for camping, and when they may be again. */
    private val callouts = mutableMapOf<UUID, Int>()
    private val quietUntil = mutableMapOf<UUID, Long>()
    private var pending: Pending? = null
    /** Lines already decided on, in the order they will be sent. */
    private val followups = ArrayDeque<Followup>()
    val hasPending: Boolean get() = pending != null || followups.isNotEmpty()
    private var lastSpoke = -100_000L
    private var lastBanter = -100_000L
    /** In a Random match, the personality each bot was dealt. */
    private val cast = mutableMapOf<UUID, BotPersonality>()
    /** Bot -> the fighter it said it would go after, and until when. */
    private val grudges = mutableMapOf<UUID, Pair<UUID, Long>>()
    /** What the bot called so remembers of a player from the matches before this one ([BotMemory]); nothing, unless it is given. */
    var recall: (String, UUID) -> Acquaintance? = { _, _ -> null }
    /** The time of day, in milliseconds since 1970: what "last game" is measured against (the match clock starts anywhere). */
    var clock: () -> Long = System::currentTimeMillis
    /** Bots that have had their say about getting a remembered player back, this match. */
    private val settled = HashSet<UUID>()
    /** This match's team-ups (see [TeamUps]) when it is played in that mode: who is teaming on whom decides some answers. */
    var teamUps: TeamUps? = null
    /**
     * A language model is there to word lines ([ChatPrompt]), and this is how far down
     * [Brief.Kind] it is let; null for no model. A line it words comes with a [Brief] and is one
     * message (the model writes one), and what a player types that the script has no answer to is
     * handed on for the model to answer.
     */
    var wordsmith: Brief.Kind? = null
    private fun worded(kind: Brief.Kind) = wordsmith?.let { kind <= it } == true
    /** How many times the first fighter has killed the second in this match; nothing, unless it is given. */
    var tally: (UUID, UUID) -> Int = { _, _ -> 0 }
    /** Kills not long ago, for a run of them: who, and when. */
    private val runs = ArrayDeque<Pair<UUID, Long>>()
    /** What each player typed last, with the names taken out: the same thing after every kill is a macro. */
    private val typed = HashMap<UUID, ArrayDeque<String>>()
    /** Players whose "L name" after every kill has been noticed to be automatic, and whether somebody has said so. */
    private val macros = HashMap<UUID, Boolean>()
    /** The answer being made is a word of politeness ("gg", "hi", "np"): said as scripted even with a model. */
    private var smallTalk = false
    /** The match's setting: one personality for every bot, or Random for one each. */
    var mode: BotPersonality = BotPersonality.SILENT
        set(value) {
            if (field != value) { pending = null; followups.clear(); topics.clear(); cast.clear(); grudges.clear() }
            field = value
        }

    /** How this bot talks. A bot that has not been dealt a personality yet says nothing. */
    fun personalityOf(bot: UUID): BotPersonality =
        if (mode != BotPersonality.RANDOM) mode else cast[bot] ?: BotPersonality.SILENT
    private fun talks(bot: UUID) = personalityOf(bot).let { it == BotPersonality.TOXIC || it == BotPersonality.SALTY || it == BotPersonality.CHILL }
    /** The personalities dealt so far in a Random match (empty in the other modes). */
    fun personalities(): Map<UUID, BotPersonality> = cast.toMap()
    /** Who [bot] announced it is going after, while that still stands. Chat has consequences. */
    fun grudge(bot: UUID, now: Long): UUID? = grudges[bot]?.takeIf { now < it.second }?.first

    /** Fixes one bot's personality in a Random match instead of leaving it to the deal. */
    fun assign(bot: UUID, personality: BotPersonality) { cast[bot] = personality }

    private fun enroll(fighters: List<Fighter>) {
        if (mode != BotPersonality.RANDOM) return
        val fresh = fighters.filter { it.bot && it.id !in cast }.map { it.id }
        // Everybody is who they always are ([BotCharacter]): the same bot is not toxic in one match and friendly in the next.
        if (fresh.isNotEmpty()) cast += BotCharacter.deal(fighters.filter { it.id in fresh }.map { it.id to it.name })
    }

    private fun voiced(share: Double) = reactions >= 1.0 || random.nextDouble() < reactions * share

    /** [id] types a line at [now]. */
    private fun says(id: UUID, now: Long) {
        lastSpoke = now; lastBot[id] = now
        val times = spoke.getOrPut(id) { ArrayDeque() }
        // (The second message of a two-message line is the same thing being said.)
        if (times.isEmpty() || now - times.last() > 4000) times.addLast(now)
        while (times.isNotEmpty() && now - times.first() > TIRE_MS) times.removeFirst()
    }

    /**
     * How ready [id] is to say something more of its own accord: 1 for somebody who has not said a
     * word lately, and less for every line of theirs in the last three minutes, the newest
     * counting the most. Nobody in a lobby of eight does most of the talking, and a bot did: the one
     * that kept dying was the one every death line belonged to, and it said 8 of the 14 lines of a
     * five-minute match (the user's, 2026-11-24). With this somebody else says the next one, or
     * nobody does. It does not count against answering to one's own name.
     */
    private fun fresh(id: UUID, now: Long): Double {
        val weight = spoke[id]?.sumOf { (1.0 - (now - it).toDouble() / TIRE_MS).coerceIn(0.0, 1.0) } ?: return 1.0
        return Math.pow(tire, weight)
    }

    fun cast(player: UUID, ability: String, now: Long) {
        prune(now)
        casts.addLast(Cast(player, ability.take(64), now))
        while (casts.size > 256) casts.removeFirst()
    }
    fun damage(attacker: UUID, victim: UUID, now: Long) {
        if (attacker == victim) return
        prune(now)
        hits.addLast(Hit(attacker, victim, now))
        while (hits.size > 256) hits.removeFirst()
    }
    fun death(victim: UUID, killer: UUID?, ability: String, now: Long) {
        prune(now)
        deaths.addLast(Death(victim, killer, ability.take(64), now))
        while (deaths.size > 32) deaths.removeFirst()
        // A run of kills by one fighter is something the whole lobby notices, more than any one death in it.
        runs.removeAll { it.first == victim }
        if (killer != null && killer != victim) {
            runs.addLast(killer to now)
            val run = runs.count { it.first == killer }
            if (run >= RUN_KILLS && offer("run", killer, "", now, 2, victim, run)) return
        }
        if (voiced(1.0)) offer("death", victim, "", now, 1)
    }
    fun chat(player: UUID, message: String, now: Long): Boolean {
        // Do not retain a chat transcript, parse formatting, or execute anything from chat.
        val text = message.take(256).lowercase().replace('’', '\'')
        // The same word straight after every kill, three kills running, is not typed by hand.
        val afterKill = deaths.lastOrNull { it.killer == player }?.let { now - it.time <= MACRO_MS } == true
        val log = typed.getOrPut(player) { ArrayDeque() }
        log.addLast(if (afterKill) text.substringBefore(' ') else "")
        while (log.size > MACRO_LINES) log.removeFirst()
        if (player !in macros && log.size == MACRO_LINES && log.first().isNotEmpty() && log.all { it == log.first() }) macros[player] = false
        return offer("chat", player, text, now, 3)
    }
    fun crystal(player: UUID, tier: String, now: Long) = voiced(0.9) && offer("crystal", player, tier.take(48), now, 1)
    fun start(now: Long) = offer("start", null, "", now, 0)
    fun camp(player: UUID, now: Long) = now >= (quietUntil[player] ?: 0L) && offer("camp", player, "", now, 0)
    fun prompt(now: Long) = offer("status", null, "", now, 0)
    /** [attacker] landed [hits] hits on the bot [victim] that took a large share of its health at once. */
    fun burst(victim: UUID, attacker: UUID, hits: Int, now: Long) = offer("burst", attacker, "", now, 2, victim, hits)
    /** [player] has stayed on one level for [seconds] while the bot [hunter] could not get a hit in.
     * [height] is 1 when they are above the hunter, -1 when below, 0 when level. */
    fun holding(player: UUID, hunter: UUID, seconds: Int, height: Int, now: Long) =
        now >= (quietUntil[player] ?: 0L) && offer("hold", player, height.toString(), now, 1, hunter, seconds)
    /** The bot [bot] has died to [camper] sitting in one spot [deaths] times and is not walking into it again. */
    fun trapped(bot: UUID, camper: UUID, deaths: Int, now: Long) = offer("trap", camper, "", now, 2, bot, deaths)
    /** The bot [bot] is backing off at low health, chased by [threat] if anyone. */
    fun retreat(bot: UUID, threat: UUID?, now: Long) = voiced(0.75) && offer("retreat", bot, "", now, 0, threat)
    /**
     * Only a few are still in it and the rest are watching. Those who are out have their hands
     * free, and in a real lobby they are the ones who talk: now and then one of them does.
     */
    fun watching(now: Long): Boolean {
        if (now < nextWatch) return false
        nextWatch = now + random.nextLong(WATCH_GAP_MS, WATCH_GAP_MS * 2)
        return offer("watch", null, "", now, 0)
    }
    private var nextWatch = 0L
    fun clear() {
        pending = null; followups.clear(); casts.clear(); hits.clear(); deaths.clear(); topics.clear(); lastBot.clear(); spoke.clear()
        recentLines.clear(); ambient.clear(); callouts.clear(); quietUntil.clear(); cast.clear(); grudges.clear(); settled.clear()
        runs.clear(); typed.clear(); macros.clear(); nextWatch = 0L
    }

    private fun offer(kind: String, actor: UUID?, detail: String, now: Long, priority: Int,
                      other: UUID? = null, count: Int = 0): Boolean {
        if (mode == BotPersonality.SILENT) return false
        val key = "$kind:$actor"
        if (kind != "chat" && now - (ambient[key] ?: -100_000L) < 35_000) return false
        if (pending != null && pending!!.priority >= priority) return false
        // Nobody answers what is typed at them after the same two seconds every time: some have the
        // answer out at once, most take a moment, and some only get to it when the fight lets them.
        val wait = if (kind != "chat" || reactions >= 1.0) random.nextLong(1300, 2801) else when (random.nextInt(100)) {
            in 0..14 -> random.nextLong(600, 1301)
            in 15..74 -> random.nextLong(1500, 3501)
            else -> random.nextLong(4000, 9001)
        }
        val due = maxOf(now + wait, lastSpoke + 5000)
        pending = Pending(kind, actor, detail, due, maxOf(now + if(kind == "chat") 12000 else 8000, due + 4000), priority, other, count)
        return true
    }

    private fun names(f: Fighter, text: String) = BotSlang.names(f.name, text)


    /** Generate at delivery time, so lives, kits and online participants are never stale. */
    fun poll(now: Long, fighters: List<Fighter>, enemies: (UUID, UUID) -> Boolean): Reply? {
        prune(now)
        if (mode == BotPersonality.SILENT) { pending = null; followups.clear(); return null }
        enroll(fighters)
        followups.firstOrNull()?.takeIf { now >= it.due }?.let { next ->
            followups.removeFirst()
            val speaker = fighters.find { it.id == next.speaker && it.bot }
            if (speaker != null && now - next.due < 6000) {
                next.grudge?.let { foe -> (next.allies + speaker.id).forEach { grudges[it] = foe to now + GRUDGE_MS } }
                return send(speaker, next.text, now, next.brief)?.copy(urgent = next.urgent)
            }
            // The conversation stalled (its speaker left, or the server hung): drop the rest of it.
            followups.clear()
        }
        val request = pending ?: return null
        if (now > request.expires) { pending = null; return null }
        if (now < request.due || now - lastSpoke < 5000) return null
        pending = null
        val actor = fighters.find { it.id == request.actor }
        val able = fighters.filter { it.bot && talks(it.id) && now - (lastBot[it.id] ?: -100_000L) >= if(request.kind == "chat") 5000 else 12000 }
        if (able.isEmpty() || (request.actor != null && actor == null)) return null
        val mentioned = able.find { names(it, request.detail) }
        // Some hardly say a word ([BotCharacter.chatty]): they answer to their name, and little else.
        val crowd = fighters.count { it.bot }
        // And whoever has just been talking leaves the next one to somebody else ([fresh]).
        val bots = if (reactions >= 1.0) able else able.filter { it.id == mentioned?.id || random.nextDouble() < BotCharacter.chattyAmong(it.name, crowd) * fresh(it.id, now) }
        if (bots.isEmpty()) return null
        // The match starting: with somebody in it that one of them has played before there is more to say than "gl".
        if (request.kind == "start" && !voiced(if (bots.any { b -> fighters.any { !it.bot && recall(b.name, it.id) != null } }) 1.3 else 0.55)) return null
        // If a message names a bot on cooldown, don't answer as an unrelated bot instead.
        if (request.kind == "chat" && mentioned == null && fighters.any { it.bot && names(it, request.detail) }) return null
        val relevant = actor?.let { a ->
            deaths.lastOrNull { it.victim == a.id || it.killer == a.id }?.takeIf { now - it.time < 20000 }?.let { d ->
                bots.find { it.id == if(d.victim == a.id) d.killer else d.victim }
            } ?: topics[a.id]?.takeIf { now - it.time < 20000 }?.let { t -> bots.find { it.id == t.bot } }
              ?: hits.lastOrNull { it.victim == a.id || it.attacker == a.id }?.let { h -> bots.find { it.id == if(h.victim == a.id) h.attacker else h.victim } }
        }
        val deathSpeaker = if(request.kind == "death") bots.find { it.id == actor?.id } else null
        // Combat reactions belong to the bot they happened to; nobody else speaks for it.
        val involved = bots.find { it.id == request.other }
        val speaker = when (request.kind) {
            "burst", "trap" -> involved ?: return null
            "retreat" -> bots.find { it.id == actor?.id } ?: return null
            // The end of a match seen from outside it: one of those who are out.
            "watch" -> BotCharacter.pick(bots.filter { it.lives <= 0 }, random) { it.name } ?: return null
            // A run of kills: the one it has just been done to says so, or else anybody it is being done to.
            "run" -> involved?.takeIf { enemies(it.id, actor!!.id) }
                ?: BotCharacter.pick(bots.filter { enemies(it.id, actor!!.id) }, random) { it.name } ?: return null
            // Telling someone to come out and fight is for a bot that is still in the match.
            "hold" -> (involved ?: relevant)?.takeIf { it.lives > 0 }
                ?: bots.filter { it.lives > 0 && it.id != actor?.id }.randomOrNull(random) ?: return null
            // The match starting: somebody who has played one of the players before has the most to say about it.
            "start" -> bots.filter { b -> fighters.any { !it.bot && recall(b.name, it.id) != null } }.takeIf { it.isNotEmpty() && (reactions >= 1.0 || random.nextDouble() < 0.8) }
                ?.let { BotCharacter.pick(it, random) { b -> b.name } } ?: BotCharacter.pick(bots, random) { it.name }!!
            // (Anybody could say it: the talkative ones do more often than the quiet ones.)
            else -> mentioned ?: relevant ?: deathSpeaker ?: BotCharacter.pick(bots, random) { it.name }!!
        }

        val say = lines.speaker(speaker, fighters, personalityOf(speaker.id))
        // Somebody this bot says it is coming for, in a line about a match before this one.
        var owed: UUID? = null
        // What the line is on and who it is about, for any bot that wants to answer it.
        var about: String? = null
        var who: Fighter? = null
        var level = 0
        var fell = false
        smallTalk = false
        val text = when (request.kind) {
            "chat" -> answer(speaker, say, actor!!, request.detail, now, enemies)
            "death" -> deathLine(speaker, say, actor!!, fighters, now)?.also {
                val death = deaths.lastOrNull { it.victim == actor.id && now - it.time < 8000 }
                if (speaker.id == actor.id) {
                    about = "death"; who = fighters.find { it.id == death?.killer }
                    fell = death?.killer == null && death?.ability == "Void"
                } else { about = "kill"; who = actor }
            }
            "crystal" -> if (speaker.id != actor!!.id && speaker.fighting && actor.fighting && enemies(speaker.id, actor.id))
                say.crystal(actor, request.detail) else null
            "camp" -> if (actor!!.fighting && speaker.fighting && enemies(speaker.id, actor.id)) {
                repeated(actor.id)?.let { (ability, count) ->
                    topics[actor.id] = Topic(speaker.id, "spam:$ability", now)
                    calledOut(actor.id, now)
                    about = "spam"; who = actor
                    say.spam(actor, ability, count)
                }
            } else null
            "hold" -> if (speaker.id != actor!!.id && actor.fighting && enemies(speaker.id, actor.id)) {
                topics[actor.id] = Topic(speaker.id, "fight", now)
                level = calledOut(actor.id, now)
                about = "hold"; who = actor
                say.hold(actor, request.count, request.detail.toIntOrNull() ?: 0, level, repeated(actor.id)?.first)
            } else null
            // (Not about somebody who has died since: "that is so unfair" from the bot that then got the kill is nonsense.)
            "burst" -> if (enemies(speaker.id, actor!!.id) && actor.fighting && deaths.none { it.victim == actor.id && now - it.time < 8000 })
                say.burst(actor, request.count).also { about = "burst"; who = actor } else null
            "trap" -> if (enemies(speaker.id, actor!!.id)) say.trapped(actor, request.count) else null
            "run" -> if (enemies(speaker.id, actor!!.id)) say.run(actor, request.count).also { about = "run"; who = actor } else null
            "watch" -> fighters.filter { it.lives > 0 }.takeIf { it.size in 1..WATCHED }?.let(say::watching)
            "retreat" -> if (speaker.fighting) {
                val threat = fighters.find { it.id == request.other && it.fighting }
                about = "retreat"; who = threat
                say.retreat(threat)
            } else null
            "start" -> fighters.firstOrNull { !it.bot && recall(speaker.name, it.id) != null }?.let { human ->
                val a = recall(speaker.name, human.id)!!
                // A toxic or a salty one that was farmed last time means what it says about it.
                if (a.farmedMe && personalityOf(speaker.id) != BotPersonality.CHILL && human.fighting && enemies(speaker.id, human.id)) owed = human.id
                say.startKnown(human, a, a.fresh(clock()))
            } ?: say.start(fighters.firstOrNull { !it.bot })
            // (About somebody it is up against: "nobody ever hits kin" is not said of a teammate.)
            "status" -> fighters.filter { it.fighting }.maxByOrNull { it.lives }
                ?.takeIf { it.id == speaker.id || enemies(speaker.id, it.id) }?.let(say::status)
            else -> null
        }
        // (Heard, and on purpose not answered.)
        if (text != null && text.isEmpty()) return null
        // What it is about, where a model is to word it or may have to answer in the script's place.
        // (A "gg" or a "hi" back is said as scripted: there is nothing in it for a model to add.)
        val kind = if (request.kind == "chat") Brief.Kind.ANSWER else Brief.Kind.REMARK
        val event = if (wordsmith != null && !smallTalk && (text == null || worded(kind))) eventOf(request, speaker, actor, fighters, now, enemies) else null
        if (text == null) {
            if (request.kind != "chat") return null
            // The script has no line for what was typed. Somebody spoken to by name still answers,
            // if only with "what": saying nothing at all to one's own name is what a bot does.
            if (mentioned?.id == speaker.id) {
                if (event == null && reactions < 1.0 && random.nextDouble() < 0.3) return null
                return send(speaker, say.lobbyWhat() ?: return null, now, event?.let { Brief(it, Brief.Kind.UNSURE) })
            }
            // Said to nobody in particular: with a model to write an answer one of them may give
            // one, to a question more often than to a remark; without one it is left alone.
            if (event == null) return null
            if (reactions < 1.0 && random.nextDouble() >= (if (OPEN_QUESTION.containsMatchIn(request.detail)) 0.6 else 0.2)) return null
            says(speaker.id, now)
            return Reply(speaker.id, "", brief = Brief(event, Brief.Kind.OPEN))
        }
        ambient["${request.kind}:${request.actor}"] = now
        val reply = send(speaker, text, now, event?.let { Brief(it, kind) }) ?: return null
        owed?.let { grudges[speaker.id] = it to now + OWED_MS }
        about?.let { banter(speaker, it, who, level, fell, fighters, now, enemies) }
        return reply
    }

    /**
     * What happened, in plain words and nothing but what did, for a model to word [speaker]'s
     * line from ([Brief]). The numbers in it are the match's own: a model that is told "twice"
     * does not say "one kill".
     */
    private fun eventOf(request: Pending, speaker: Fighter, actor: Fighter?, fighters: List<Fighter>, now: Long,
                        enemies: (UUID, UUID) -> Boolean): String? {
        fun how(ability: String) = when (ability) {
            "Attack" -> "a melee hit"; "Projectile" -> "a projectile"; "Explosion" -> "an explosion"; "Slam" -> "a slam"
            "Void" -> "a knock off the map"; "the environment" -> "the map itself"; else -> ability.lowercase()
        }
        fun lives(n: Int) = if (n == 1) "1 life" else "$n lives"
        fun score(other: Fighter): String {
            val theirs = tally(other.id, speaker.id); val mine = tally(speaker.id, other.id)
            return if (theirs + mine == 0) "" else " Kills between you two this match: ${other.name} $theirs, you $mine."
        }
        return when (request.kind) {
            "chat" -> actor?.let { p ->
                val to = if (names(speaker, request.detail)) " to you" else ""
                val side = if (!enemies(speaker.id, p.id)) " ${p.name} is on your team." else ""
                val death = deaths.lastOrNull { (it.victim == p.id && it.killer == speaker.id) || (it.victim == speaker.id && it.killer == p.id) }
                    ?.takeIf { now - it.time <= 20000 }
                val just = when (death?.victim) {
                    speaker.id -> " ${p.name} killed you a moment ago with ${how(death.ability)}."
                    p.id -> " You killed ${p.name} a moment ago with ${how(death.ability)}."
                    else -> ""
                }
                val run = runs.count { it.first == p.id }.takeIf { it >= RUN_KILLS }?.let { " ${p.name} has killed $it players in under a minute without dying." } ?: ""
                val macro = if (p.id in macros) " ${p.name} types the same thing straight after every kill: it is clearly an automatic message (a macro), not typed by hand." else ""
                "${p.name} typed \"${request.detail}\" in chat$to.$side$just${score(p)}$run$macro"
            }
            "death" -> actor?.let { victim ->
                val death = deaths.lastOrNull { it.victim == victim.id && now - it.time < 8000 } ?: return null
                val killer = fighters.find { it.id == death.killer }
                when (speaker.id) {
                    victim.id -> {
                        val left = if (speaker.lives <= 0) " That was your last life: you are out of the match." else " You have ${lives(speaker.lives)} left."
                        when {
                            killer == null && death.ability == "Void" -> "You just fell off the map and lost a life.$left"
                            killer == null -> "You just died to the map itself, not to a player, and lost a life.$left"
                            else -> "${killer.name} just killed you with ${how(death.ability)}.$left${score(killer)}" +
                                if (repeated(killer.id)?.first == death.ability) " ${killer.name} uses ${how(death.ability)} over and over." else ""
                        }
                    }
                    killer?.id -> "You just killed ${victim.name} with ${how(death.ability)}." +
                        (if (victim.lives <= 0) " That was their last life: ${victim.name} is out of the match." else " ${victim.name} has ${lives(victim.lives)} left.") +
                        score(victim)
                    else -> null
                }
            }
            "run" -> actor?.let { "${it.name} has killed ${request.count} players in under a minute without dying once." +
                (if (request.other == speaker.id) " You were the latest." else "") + score(it) }
            "watch" -> fighters.filter { it.lives > 0 }.let { left ->
                "You are out of the match and watching the end of it. Still in it: " + left.joinToString { "${it.name} (${it.kit.lowercase()}, ${lives(it.lives)})" } +
                    ". Nothing new has happened: you just have something to say about it, or to one of them." }
            "crystal" -> actor?.let { "${it.name} just picked up a smash crystal: a super move that can kill everybody near them." }
            "camp" -> actor?.let { a -> repeated(a.id)?.let { (ability, count) -> "${a.name} keeps using ${ability.lowercase()}: $count times in a row." } }
            "hold" -> actor?.let { a ->
                val where = when (request.detail.toIntOrNull() ?: 0) { 1 -> "up high"; -1 -> "down below"; else -> "away from you" }
                "${a.name} has stayed $where, where you cannot get a hit in, for about ${request.count} seconds." }
            "burst" -> actor?.let { "${it.name} just hit you ${request.count} times in about a second and took a big part of your health." }
            "trap" -> actor?.let { "You have now died ${request.count} ${if (request.count == 1) "time" else "times"} going at ${it.name}, who stays in one spot." }
            "retreat" -> "You are nearly dead and backing off to heal." + (fighters.find { it.id == request.other && it.fighting }?.let { " ${it.name} is chasing you." } ?: "")
            "start" -> "The match is starting." + (fighters.firstOrNull { !it.bot && recall(speaker.name, it.id) != null }?.let { human ->
                val a = recall(speaker.name, human.id)!!
                " You remember ${human.name} from ${if (a.fresh(clock())) "a match a few minutes ago" else "a match some days ago"}: " +
                    "kills between you two then were ${human.name} ${a.killedMe}, you ${a.killedThem}, and " +
                    (if (a.theyWon) "${human.name} won it" else if (a.iWon) "you won it" else "neither of you won it") + "." +
                    (if (a.theirStreak >= 2) " ${human.name} has won ${a.theirStreak} matches in a row with you in them." else "") +
                    (if (a.mouthed) " ${human.name} talked trash at you then." else "")
            } ?: "")
            "status" -> fighters.filter { it.fighting }.maxByOrNull { it.lives }?.let { "${it.name} is ahead with ${lives(it.lives)}." }
            else -> null
        }
    }

    /**
     * Lets the other bots answer the line [speaker] just sent, each in its own personality. This is
     * where a mixed lobby comes alive: the bot that was trash-talked answers back, a chill bot
     * tells a toxic one to calm down and gets told to stay out of it, and if the chill ones have
     * had enough they agree to go after the toxic one together, and then really do
     * (see [grudge]). A salty bot complains, a toxic one rubs it in until the salty one snaps, and
     * the two of them go for each other. With every bot on one personality it reduces to the
     * occasional short exchange.
     *
     * [about] is the subject of the line and [who] the fighter it was about, if any.
     */
    private fun banter(speaker: Fighter, about: String, who: Fighter?, level: Int, fell: Boolean,
                       fighters: List<Fighter>, now: Long, enemies: (UUID, UUID) -> Boolean) {
        fun mood(f: Fighter) = personalityOf(f.id)
        fun voice(f: Fighter) = lines.speaker(f, fighters, mood(f))
        fun roll(chance: Double) = random.nextDouble() < chance
        var at = followups.lastOrNull()?.due ?: now
        // ([why]: what the line is for, in plain words, for a model to word it from. A line that
        // announces something the bots then do, like teaming up on somebody, has none and stays as scripted.)
        fun queue(f: Fighter, text: String?, grudge: UUID? = null, allies: List<UUID> = emptyList(), why: String? = null): Boolean {
            if (text == null || followups.size >= MAX_CONVERSATION) return false
            at += random.nextLong(1500, 3501)
            // (One bot answering another: as it was written by hand, unless a model is let that far, [Brief.Kind.BANTER].)
            followups.addLast(Followup(f.id, text, at, grudge, allies, brief = why?.takeIf { worded(Brief.Kind.BANTER) }?.let { Brief(it, Brief.Kind.BANTER) }))
            return true
        }
        val said = "${speaker.name}'s last message"
        val crowd = fighters.count { it.bot }
        val talkers = fighters.filter { it.bot && it.id != speaker.id && talks(it.id) &&
            (reactions >= 1.0 || random.nextDouble() < BotCharacter.chattyAmong(it.name, crowd) * fresh(it.id, now)) }
        // The bot the line was about answers for itself, even from the respawn screen.
        val aimedAt = who?.let { w -> talkers.find { it.id == w.id } }
        // Bystanders: still in the match (a respawning bot can type), not the subject, and not
        // fresh out of another exchange.
        val idle = talkers.filter { it.lives > 0 && it.id != who?.id && now - (lastBot[it.id] ?: -100_000L) >= 12000 }
        val calm = idle.filter { mood(it) == BotPersonality.CHILL }
        val mean = idle.filter { mood(it) == BotPersonality.TOXIC }
        val sour = idle.filter { mood(it) == BotPersonality.SALTY }
        // Having a go at each other is for bots that are fighting each other. Two on one team do
        // not: seven of them against one player bickering over who got hit reads as nonsense.
        // (In a free-for-all that is everybody, and nothing changes.)
        fun rival(f: Fighter) = enemies(f.id, speaker.id)
        val calmRivals = calm.filter(::rival)
        val meanRivals = mean.filter(::rival)
        val mates = idle.filter { !rival(it) }
        /** [a] says it is going after [b]: true when the two can actually fight each other. */
        fun feud(a: Fighter, b: Fighter) = a.lives > 0 && b.lives > 0 && enemies(a.id, b.id)
        /**
         * [angry] has had enough of [heckler] and says so; the heckler enjoys it. Both lines come
         * with a grudge, so in a free-for-all the two of them leave everyone else and fight it out.
         */
        fun blowUp(angry: Fighter, heckler: Fighter) {
            val real = feud(angry, heckler)
            if (queue(angry, voice(angry).snap(heckler), heckler.id.takeIf { real },
                    why = "${heckler.name} keeps winding you up. You have had enough and snap at ${heckler.name}.") && roll(0.7))
                queue(heckler, voice(heckler).taunt(angry), angry.id.takeIf { real },
                    why = "${angry.name} just snapped at you. You enjoy that and wind ${angry.name} up some more.")
        }
        // A lobby that argues about every single hit is as fake as one that never does.
        val fresh = now - lastBanter >= ARGUMENT_GAP_MS
        val humanDied = about == "kill" && who?.bot == false

        /** Someone on the speaker's wavelength backs it up in a word or two. */
        fun pileOn(from: List<Fighter>) {
            val chance = when { about == "hold" && level >= 1 -> 0.3; about == "burst" -> 0.3; about == "run" -> 0.5; humanDied -> 0.12; else -> 0.0 }
            val other = from.randomOrNull(random) ?: return
            // (As scripted, model or no model: "fr" and "rip" are the whole of it.)
            if (roll(chance)) queue(other, voice(other).agree(if (humanDied) "kill" else about))
        }

        /** The chill bots agree to deal with [bully] first. Whoever agrees goes after them for real. */
        fun teamUp(bully: Fighter, starter: Fighter) {
            if (!bully.fighting) return
            val posse = (calm + starter + listOfNotNull(aimedAt)).distinctBy { it.id }
                .filter { mood(it) == BotPersonality.CHILL && it.fighting && enemies(it.id, bully.id) }
            if (posse.isEmpty() || !roll(0.55)) return
            val leader = posse.find { it.id == starter.id } ?: posse.first()
            if (posse.size == 1) { queue(leader, voice(leader).duel(bully), bully.id); return }
            val rest = posse.filter { it.id != leader.id }
            if (!queue(leader, voice(leader).rally(bully), bully.id, rest.map { it.id })) return
            rest.shuffled(random).take(2).forEach { queue(it, voice(it).joinIn(bully)) }
            if (roll(0.6)) queue(bully, voice(bully).ganged(posse.size))
        }

        if (mood(speaker) == BotPersonality.TOXIC) {
            // 1. Whoever it was aimed at answers back; two toxic bots go another round, and a salty
            //    one that was just gloated at loses it completely.
            var answered = false
            if (aimedAt != null && mood(aimedAt) == BotPersonality.SALTY && roll(if (fresh) 0.75 else 0.35)) {
                blowUp(aimedAt, speaker)
                lastBanter = now
                return
            }
            if (aimedAt != null && roll(if (fresh) 0.6 else 0.3)) {
                answered = queue(aimedAt, voice(aimedAt).comeback(speaker, about), why = "$said was aimed at you. You answer back.")
                if (answered && mood(aimedAt) == BotPersonality.TOXIC && roll(0.5)) queue(speaker, voice(speaker).comeback(aimedAt, "beef"),
                    why = "${aimedAt.name} answered you back. You do not let it go.")
            }
            // 2. A chill bystander steps in, gets told off, and may decide enough is enough.
            val defender = calmRivals.randomOrNull(random)
            if (defender != null && about != "retreat" && fresh && roll(0.65) && queue(defender, voice(defender).defend(who, speaker, about),
                    why = "${speaker.name} is being nasty${who?.takeIf { it.id != defender.id }?.let { " about ${it.name}" } ?: ""}. You tell ${speaker.name} to calm down.")) {
                lastBanter = now
                if (roll(0.7) && queue(speaker, voice(speaker).rebuke(defender),
                        why = "${defender.name} told you to calm down. You tell ${defender.name} to stay out of it.")) teamUp(speaker, defender)
                return
            }
            if (answered) { lastBanter = now; return }
            // 3. Otherwise another toxic bot laughs at the excuse, or piles on.
            val heckler = meanRivals.randomOrNull(random)
            if (about == "death" && heckler != null && fresh && roll(0.3) && queue(heckler, voice(heckler).mock(speaker, fell),
                    why = "${speaker.name} just died and made an excuse for it. You laugh at the excuse.")) {
                lastBanter = now
                if (roll(0.5)) queue(speaker, voice(speaker).comeback(heckler, "beef"), why = "${heckler.name} laughed at you for dying. You snap back.")
            } else pileOn(mean)
        } else if (mood(speaker) == BotPersonality.SALTY) {
            // A complaint (or a grudging "finally" after a kill).
            // 1. A toxic bot rubs it in: the one it was about if there is one, else a bystander.
            //    The salty one snaps and the two go for each other.
            val heckler = aimedAt?.takeIf { mood(it) == BotPersonality.TOXIC && rival(it) } ?: meanRivals.randomOrNull(random)
            if (heckler != null && about != "kill" && roll(if (fresh) 0.65 else 0.25) &&
                queue(heckler, voice(heckler).needle(speaker, about, about == "death" && heckler.id == who?.id),
                    why = "${speaker.name} is complaining. You rub it in.")) {
                lastBanter = now
                if (roll(0.8)) blowUp(speaker, heckler)
                return
            }
            // 2. A chill bot tries to cheer it up, which does not really work.
            val friend = aimedAt?.takeIf { mood(it) == BotPersonality.CHILL } ?: calm.randomOrNull(random)
            if (friend != null && about != "kill" && fresh && roll(0.45) && queue(friend, voice(friend).console(speaker),
                    why = "${speaker.name} is having a bad time and complaining. You try to cheer ${speaker.name} up.")) {
                lastBanter = now
                if (roll(0.5)) queue(speaker, voice(speaker).grumble(friend), why = "${friend.name} tried to cheer you up. You are still annoyed.")
                return
            }
            // 3. Whoever it was about answers for themselves.
            if (aimedAt != null && roll(if (fresh) 0.45 else 0.2)) {
                if (queue(aimedAt, voice(aimedAt).comeback(speaker, about), why = "$said was about you. You answer it.")) lastBanter = now
                return
            }
            // 4. Or another salty bot knows the feeling.
            val fellow = sour.randomOrNull(random)
            if (fellow != null && about != "kill" && roll(0.3)) queue(fellow, voice(fellow).sameHere(),
                why = "${speaker.name} is complaining. Your own game is going just as badly and you say so.")
            // 5. Or somebody on its own team backs it up.
            else pileOn(mates)
        } else {
            // A friendly line. 1. A toxic bot cannot stand it, and the chill one does not take the bait.
            val hater = (meanRivals + listOfNotNull(aimedAt?.takeIf { mood(it) == BotPersonality.TOXIC && rival(it) })).distinctBy { it.id }.randomOrNull(random)
            if (hater != null && about in setOf("kill", "death", "burst", "hold") && fresh && roll(0.45) &&
                queue(hater, voice(hater).sneer(speaker, about), why = "${speaker.name} is being friendly about a fight. You think that is soft and say so.")) {
                lastBanter = now
                if (roll(0.65) && queue(speaker, voice(speaker).brushOff(hater), why = "${hater.name} had a go at you for being friendly. You laugh it off.") &&
                    roll(0.4)) teamUp(hater, speaker)
                return
            }
            // 2. Or the fighter it was about simply answers.
            if (aimedAt != null && roll(if (fresh) 0.45 else 0.2)) {
                val answer = voice(aimedAt).let { if (about == "burst") it.thanks(speaker) else it.comeback(speaker, about) }
                if (queue(aimedAt, answer, why = "$said was about you, and it was friendly. You answer it.")) lastBanter = now
                return
            }
            pileOn(calm)
        }
    }

    // ------------------------------------------------------------------ Team-Ups

    /** Queues one line of a conversation about a team-up, a moment after the line before it. */
    private fun teamLine(f: Fighter?, text: String?, now: Long, grudge: UUID? = null) {
        if (f == null || text == null || followups.size >= MAX_CONVERSATION) return
        val after = maxOf(now, followups.lastOrNull()?.due ?: now)
        followups.addLast(Followup(f.id, text, after + random.nextLong(900, 2601), grudge))
    }

    /**
     * Bots have agreed to go after a player together ([TeamUps.Formed]). The one whose idea it was
     * says so, a couple of the others say they are in, and a chill bot that was asked may say what
     * it thinks of teaming.
     */
    fun teamedUp(pact: TeamUps.Pact, declined: List<UUID>, fighters: List<Fighter>, now: Long) {
        if (mode == BotPersonality.SILENT) return
        enroll(fighters)
        val target = fighters.find { it.id == pact.target } ?: return
        fun talker(id: UUID) = fighters.find { it.id == id && it.bot && talks(it.id) }
        fun voice(f: Fighter) = lines.speaker(f, fighters, personalityOf(f.id))
        val members = pact.members.mapNotNull(::talker)
        val leader = talker(pact.leader) ?: members.firstOrNull() ?: return
        val ups = teamUps
        teamLine(leader, voice(leader).rallyOn(target, pact.reason, ups?.spammed(target.id, now),
            ups?.killsOf(target.id, leader.id, now) ?: 0, pact.members.size), now)
        members.filter { it.id != leader.id }.shuffled(random).take(2).forEach { teamLine(it, voice(it).joinTeam(target), now) }
        declined.mapNotNull(::talker).filter { personalityOf(it.id) == BotPersonality.CHILL }.randomOrNull(random)
            ?.takeIf { random.nextDouble() < 0.75 }?.let { teamLine(it, voice(it).declineTeam(target), now) }
        lastBanter = now
    }

    /** A bot that is about to call for a team-up on a player says so first ([TeamUps.Warned]). */
    fun teamWarned(bot: UUID, target: UUID, reason: TeamUps.Reason, fighters: List<Fighter>, now: Long) {
        if (mode == BotPersonality.SILENT) return
        enroll(fighters)
        val speaker = fighters.find { it.id == bot && it.bot && talks(it.id) } ?: return
        val player = fighters.find { it.id == target } ?: return
        teamLine(speaker, lines.speaker(speaker, fighters, personalityOf(speaker.id)).warnTeam(player, reason, teamUps?.spammed(target, now)), now)
    }

    /**
     * Two teams: the bot [bot] answers [player], who is on its side and asked something of it
     * ([TeamTalk]). It goes out next, ahead of whatever else was about to be said and without the
     * usual pause between lines: a teammate is waiting for it. False when the bot does not talk
     * (it does what was asked all the same).
     */
    fun teamAnswer(bot: UUID, player: UUID, ask: TeamTalk.Ask, target: UUID?, fighters: List<Fighter>, now: Long): Boolean {
        if (mode == BotPersonality.SILENT) return false
        enroll(fighters)
        val speaker = fighters.find { it.id == bot && it.bot && talks(it.id) } ?: return false
        val asker = fighters.find { it.id == player } ?: return false
        val say = lines.speaker(speaker, fighters, personalityOf(speaker.id))
        val text = when {
            speaker.lives <= 0 -> say.teamOut()
            !speaker.fighting && ask != TeamTalk.Ask.UNCLEAR && ask != TeamTalk.Ask.FREE -> say.teamDead(ask)
            else -> say.teamAck(asker, ask, fighters.find { it.id == target })
        } ?: return false
        // Whatever the line would otherwise have set off (a comeback to the word "spam" in it, say) is dropped.
        if (pending?.kind == "chat" && pending?.actor == player) pending = null
        followups.addFirst(Followup(speaker.id, text, now + random.nextLong(600, 1500), urgent = true))
        return true
    }

    /** Two teams: [bot] was asked to come to a teammate and has found no way there. It says so, once. */
    fun teamCant(bot: UUID, fighters: List<Fighter>, now: Long): Boolean {
        if (mode == BotPersonality.SILENT) return false
        enroll(fighters)
        val speaker = fighters.find { it.id == bot && it.bot && talks(it.id) } ?: return false
        val text = lines.speaker(speaker, fighters, personalityOf(speaker.id)).teamCant() ?: return false
        followups.addFirst(Followup(speaker.id, text, now + random.nextLong(400, 1200), urgent = true))
        return true
    }

    /** Bots that took the player's apology and left the pact ([TeamUps.Left]) say so. */
    fun teamLeft(pact: TeamUps.Pact, quitters: List<UUID>, fighters: List<Fighter>, now: Long) {
        if (mode == BotPersonality.SILENT) return
        val target = fighters.find { it.id == pact.target } ?: return
        quitters.mapNotNull { id -> fighters.find { it.id == id && it.bot && talks(it.id) } }.shuffled(random).take(1)
            .forEach { teamLine(it, lines.speaker(it, fighters, personalityOf(it.id)).truceTaken(target), now) }
    }

    /**
     * The pact is over ([TeamUps.Ended]). One of the bots says so; and if a toxic one has turned on
     * a partner, it says that too and means it (see [grudge]), and the partner answers.
     */
    fun teamOver(pact: TeamUps.Pact, how: TeamUps.End, turncoat: UUID?, betrayed: UUID?, fighters: List<Fighter>, now: Long) {
        // A bot that never talks can still turn on its partner.
        if (turncoat != null && betrayed != null) grudges[turncoat] = betrayed to now + GRUDGE_MS
        if (mode == BotPersonality.SILENT) return
        // (Whatever of the call to arms has not been typed yet is out of date: nobody says
        // "everyone get them" about somebody who has just been got.)
        followups.clear()
        fun talker(id: UUID?) = fighters.find { it.id == id && it.bot && talks(it.id) }
        fun voice(f: Fighter) = lines.speaker(f, fighters, personalityOf(f.id))
        val target = fighters.find { it.id == pact.target }
        val members = pact.members.mapNotNull(::talker)
        if (target != null) {
            if (how == TeamUps.End.FORGIVEN) members.filter { personalityOf(it.id) == BotPersonality.CHILL }.randomOrNull(random)
                ?.let { teamLine(it, voice(it).truceTaken(target), now) }
            else members.filter { it.id != turncoat }.let { list -> list.find { it.id == pact.leader } ?: list.randomOrNull(random) }
                ?.let { teamLine(it, voice(it).teamDone(target, how), now) }
        }
        val snake = talker(turncoat)
        val partner = fighters.find { it.id == betrayed }
        if (snake != null && partner != null) {
            teamLine(snake, voice(snake).turnOn(partner), now, partner.id)
            talker(betrayed)?.let { teamLine(it, voice(it).turnedOn(snake), now, snake.id) }
        }
    }

    /** Counts a camping callout and keeps bots off that fighter's case for a while. Returns how many came before. */
    private fun calledOut(id: UUID, now: Long): Int {
        val level = callouts[id] ?: 0
        callouts[id] = level + 1
        quietUntil[id] = now + 30_000 + minOf(level, 4) * 12_000L + random.nextLong(0, 10_001)
        return level
    }

    fun finish(winners: Set<UUID>, fighters: List<Fighter>, now: Long): Reply? {
        pending = null; followups.clear()
        if (mode == BotPersonality.SILENT || winners.isEmpty() || now - lastSpoke < 2500) return null
        enroll(fighters)
        val winner = fighters.firstOrNull { it.id in winners } ?: return null
        val bot = fighters.filter { it.bot && talks(it.id) }.let { list -> list.find { it.id in winners } ?: BotCharacter.pick(list, random) { it.name } } ?: return null
        val say = lines.speaker(bot, fighters, personalityOf(bot.id))
        // (How many in a row that makes for a player this bot keeps meeting: it has not been told of this win yet.)
        val streak = if (winner.bot) 0 else (recall(bot.name, winner.id)?.theirStreak ?: 0) + 1
        val text = (if (bot.id in winners) say.won() else say.lost(winner, (callouts[winner.id] ?: 0) > 0, streak)) ?: return null
        return send(bot, text, now)
    }

    /**
     * The bot [bot] has had enough and is about to walk out of the match ([RageQuit]); [killer]
     * has just killed it. Its last words, if it has any: in a Silent match it leaves without one.
     */
    fun rageLine(bot: UUID, killer: UUID?, fighters: List<Fighter>, now: Long): Reply? {
        if (mode == BotPersonality.SILENT) return null
        enroll(fighters)
        val f = fighters.find { it.id == bot && it.bot && talks(it.id) } ?: return null
        val text = lines.speaker(f, fighters, personalityOf(f.id)).rageQuit(fighters.find { it.id == killer }) ?: return null
        // Whatever else was about to be said can wait: this is the last thing it types.
        pending = null
        val styled = BotSlang.style(text.substringBefore(" / ")).takeIf { it.isNotEmpty() } ?: return null
        says(f.id, now)
        recentLines.addLast(styled); while (recentLines.size > 12) recentLines.removeFirst()
        return Reply(f.id, styled, urgent = true)
    }

    /** One of the others has something to say about [quitter] having walked out (who is no longer among [fighters]). */
    fun quitReaction(quitter: Fighter, fighters: List<Fighter>, now: Long): Reply? {
        if (mode == BotPersonality.SILENT) return null
        enroll(fighters)
        if (reactions < 1.0 && random.nextDouble() > 0.7) return null
        val speaker = BotCharacter.pick(fighters.filter { it.bot && it.id != quitter.id && talks(it.id) }, random) { it.name } ?: return null
        val text = lines.speaker(speaker, fighters + quitter, personalityOf(speaker.id)).quitReaction(quitter) ?: return null
        return send(speaker, text, now, Brief("${quitter.name} has just rage quit: walked out of the match and off the server in the middle of it." +
            if (speaker.side != null && speaker.side == quitter.side) " ${quitter.name} was on your team." else ""))
    }

    /** One line of what is said when a match is over, and how long after the line before it. */
    data class Parting(val reply: Reply, val afterMs: Long)

    /**
     * Everything the bots say when a match is over: the line [finish] gives, and after it a few of
     * the others with a "gg" of their own, a second or so apart, the way a lobby of people does
     * (not everybody, and the quiet ones least). One of the losers may ask for another match, one
     * may have something to say about [map], and one of [leaving] (the bots that are not staying
     * for the next match) may say it is off.
     */
    fun finishAll(winners: Set<UUID>, fighters: List<Fighter>, now: Long, map: String = "", leaving: Set<UUID> = emptySet()): List<Parting> {
        val first = finish(winners, fighters, now) ?: return emptyList()
        val out = arrayListOf(Parting(first, 0))
        var asked = false
        var moaned = false
        for (f in fighters.filter { it.bot && talks(it.id) && it.id != first.speaker }.shuffled(random)) {
            if (out.size >= MAX_PARTING) break
            if (reactions < 1.0 && random.nextDouble() > BotCharacter.chattyAmong(f.name, fighters.count { it.bot }) * 0.7) continue
            val say = lines.speaker(f, fighters, personalityOf(f.id))
            val won = f.id in winners
            val raw = when {
                f.id in leaving && random.nextDouble() < 0.6 -> say.leaving()
                // (Nobody asks for another one and then walks off.)
                !won && !asked && f.id !in leaving && random.nextDouble() < 0.3 -> say.rematch().also { asked = true }
                !won && !moaned && map.isNotEmpty() && random.nextDouble() < 0.15 -> say.mapMoan(map).also { moaned = true }
                else -> say.after(won)
            } ?: continue
            val text = BotSlang.style(raw.substringBefore(" / "))
            if (text.isEmpty()) continue
            lastBot[f.id] = now
            out += Parting(Reply(f.id, text), random.nextLong(500, 1901))
        }
        return out
    }

    /** Styles a line and sends its first message; a second message after " / " follows a moment later. */
    private fun send(speaker: Fighter, raw: String, now: Long, brief: Brief? = null): Reply? {
        val parts = raw.split(" / ").map(BotSlang::style).filter { it.isNotEmpty() }
        val text = parts.firstOrNull() ?: return null
        val worded = brief?.takeIf { worded(it.kind) }
        // The same words twice running reads as a script. (A model that words the line will not say them twice.)
        if (worded == null && text in recentLines.takeLast(2)) return null
        // The rest of this bot's own line comes before anybody else's answer to it. (A model writes
        // one message: the second half of a scripted line would not follow from it.)
        if (parts.size > 1 && worded == null) followups.addFirst(Followup(speaker.id, parts.drop(1).joinToString(" / "), now + random.nextLong(900, 2201)))
        says(speaker.id, now)
        recentLines.addLast(text); while (recentLines.size > 12) recentLines.removeFirst()
        return Reply(speaker.id, text, brief = worded)
    }
    private fun repeated(id: UUID): Pair<String, Int>? = casts.filter { it.player == id }
        .groupingBy { it.ability }.eachCount().maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.let { it.key to it.value }

    private fun deathLine(bot: Fighter, say: BotLines.Speaker, victim: Fighter, fighters: List<Fighter>, now: Long): String? {
        val death = deaths.lastOrNull { it.victim == victim.id && now - it.time < 8000 } ?: return null
        val killer = fighters.find { it.id == death.killer }
        if (bot.id == victim.id) {
            if (killer != null) topics[killer.id] = Topic(bot.id, "death:${death.ability}", now)
            return say.diedTo(killer, death.ability, killer != null && repeated(killer.id)?.first == death.ability)
        }
        if (bot.id == killer?.id) {
            topics[victim.id] = Topic(bot.id, "kill:${death.ability}", now)
            // Somebody who ran their mouth at it last time, or kept killing it: this kill is the answer, once.
            if (!victim.bot && bot.id !in settled) recall(bot.name, victim.id)?.takeIf { it.mouthed || it.farmedMe }?.let { a ->
                settled += bot.id
                return say.payback(victim, a, a.fresh(clock()))
            }
            return say.killed(victim, death.ability)
        }
        return null
    }

    private fun answer(bot: Fighter, say: BotLines.Speaker, player: Fighter, text: String, now: Long,
                       enemies: (UUID, UUID) -> Boolean): String? {
        fun has(pattern: String) = Regex(pattern).containsMatchIn(text)
        fun topic(value: String) { topics[player.id] = Topic(bot.id, value, now) }
        val previous = topics[player.id]?.takeIf { it.bot == bot.id && now - it.time < 20000 }
        val death = deaths.lastOrNull { (it.victim == player.id && it.killer == bot.id) || (it.victim == bot.id && it.killer == player.id) }
            ?.takeIf { now - it.time <= 20000 }
        // Questions with a factual answer first, then accusations, then small talk.
        if (has("\\b(what|which|wat) kit\\b")) return say.kit()
        if (has("\\b(how many lives|your lives|ur lives|my lives|score)\\b")) return say.lives(player)
        // In a Team-Ups match the bots may really have a pact on this player: "stop teaming" and
        // "sorry" then get the truth for an answer.
        val pact = teamUps?.pact?.takeIf { it.target == player.id }
        if (pact != null && has(TEAMING)) {
            topic("target")
            return if (bot.id in pact.members) say.whyTeamed(player, pact.reason, teamUps?.spammed(player.id, now),
                    teamUps?.killsOf(player.id, bot.id, now) ?: 0, pact.members.size)
                else say.notTeaming(player)
        }
        if (pact != null && bot.id in pact.members && has("\\b(sorry|sry|my bad|mb|truce|peace)\\b"))
            return say.truceRefused(player, teamUps?.killsOf(player.id, bot.id, now) ?: 0)
        if (has(TEAMING)) {
            val attackers = hits.filter { it.victim == player.id && now - it.time < 15000 }.map { it.attacker }.distinct()
            topic("target")
            return if (!enemies(bot.id, player.id)) say.sameTeam(player)
                else if (bot.id !in attackers) say.neverHit(player)
                else if (attackers.size > 1) say.manyHit(player, attackers.size)
                else say.opponents(player)
        }
        if (has("\\b(camp|camping|camper|spam|spamming|spammer|spams)\\b")) {
            val theirs = repeated(player.id); val mine = repeated(bot.id); topic("spam:${mine?.first ?: theirs?.first ?: "abilities"}")
            return if (mine != null) say.ownSpam(mine.first, mine.second)
                else if (theirs != null) say.theirSpam(theirs.first, theirs.second)
                else say.notCamping(player)
        }
        if (has(BotSlang.CHEATS)) return say.notHacking()
        // The same word straight after every kill is a macro: somebody says so, once, and after
        // that it mostly gets what a macro gets, which is nothing.
        val macro = macros[player.id]
        if (macro != null && deaths.lastOrNull { it.killer == player.id }?.let { now - it.time <= MACRO_MS + 12_000 } == true &&
            typed[player.id]?.lastOrNull()?.isNotEmpty() == true) {
            if (!macro) { macros[player.id] = true; topic("macro"); return say.macro(player, typed[player.id]?.lastOrNull().orEmpty()) }
            if (reactions < 1.0 && random.nextDouble() < 0.75) return ""
        }
        if (has(BotSlang.INSULT)) {
            topic("score")
            // The answer to a taunt after a kill is about the kills there really were: one is "one
            // kill", the second on the same bot is not, and neither is the eighth in a row.
            if (death?.victim == bot.id) {
                val run = runs.count { it.first == player.id }
                val mine = tally(player.id, bot.id)
                return when {
                    run >= RUN_KILLS -> say.onRun(player, run)
                    mine >= 2 -> say.killedAgain(player, mine)
                    else -> say.oneKill(death.ability)
                }
            }
            if (death?.victim == player.id) return say.youDied(death.ability)
            return when {
                player.lives <= 0 -> say.youAreOut()
                bot.lives <= 0 -> say.iAmOut(player)
                player.lives > bot.lives -> say.behind(player)
                player.lives < bot.lives -> say.ahead(player)
                else -> say.tied()
            }
        }
        if (has("\\b(gg|ggs|good game|gf|good fight|wp)\\b")) { smallTalk = true; return say.gg(player) }
        if (has("\\b(come|fight|melee|1v1|run|running|hiding|hide|scared|coward)\\b")) {
            topic("fight")
            return if (!player.fighting) say.fightWhileTheyAreOut()
                else if (!bot.fighting) say.fightWhileOut()
                else say.fight(player)
        }
        if (has("\\b(bot|bots|ai|npc)\\b")) return say.calledBot()
        if (has("\\b(again|always)\\b") && has("\\b(chicken|skeleton|wolf|pig|guardian|phantom|bear|kit)\\b")) return say.kitAgain(player)
        if (has("\\b(fun|boring|annoying|bored)\\b")) { topic("fun"); return say.notFun() }
        if (has("\\b(nice|good|great) (shot|hit|aim|combo)\\b|\\bns\\b")) { smallTalk = true; return say.thanks(player) }
        if (has("\\b(sorry|sry|my bad|mb)\\b")) { smallTalk = true; return say.forgive(player) }
        if (has("\\b(gl|glhf|good luck|hello|hey|hi|yo|sup)\\b")) { smallTalk = true; return say.greet(player) }
        // (A word or two back at what the bot just said: "why", "how", "nah". A whole question that
        // happens to begin with "what" is a new one, and not about that.)
        if (previous != null && text.trim().count { it == ' ' } < 3 &&
            has("^(why|how|what|wdym|nah|no|yes|yeah|ya|ok|okay|sure|to win|because|bc|cuz|lol|bruh|bro|\\?+)(?![a-z])")) {
            return when {
                previous.subject.startsWith("spam:") -> say.aboutSpam(previous.subject.substringAfter(':'))
                previous.subject == "target" -> say.aboutTargeting()
                previous.subject == "fight" -> say.aboutFighting()
                previous.subject == "fun" -> say.aboutFun()
                else -> say.aboutScore(player)
            }
        }
        // Unrelated conversation is left alone instead of answered with a random insult.
        return null
    }
    companion object {
        /** How long a bot keeps going after the fighter it said it would. */
        const val GRUDGE_MS = 30_000L
        /** How long a bot that said at the start it was coming for somebody keeps at it. */
        const val OWED_MS = 45_000L

        /** Minimum time between two arguments in one lobby. */
        const val ARGUMENT_GAP_MS = 18_000L
        /** The most lines a conversation between bots can queue up. */
        const val MAX_CONVERSATION = 7
        /** At most this many lines when a match is over: a few people say gg, not the whole lobby one after another. */
        const val MAX_PARTING = 4
        /** With this many or fewer still in a match, those who are out may talk about what they are watching, at most this often. */
        const val WATCHED = 3
        const val WATCH_GAP_MS = 22_000L
        /** This many kills by one fighter inside [RUN_MS], without dying, is a run the lobby remarks on. */
        const val RUN_KILLS = 4
        const val RUN_MS = 45_000L
        /** A line typed this soon after the player's own kill, [MACRO_LINES] kills running with the same first word, is automatic. */
        const val MACRO_MS = 1500L
        const val MACRO_LINES = 3
        /**
         * A line a bot typed counts against its next one for this long, less and less ([fresh]), and
         * a line typed this moment leaves it [TIRE] as ready as it was. Measured on a bot killed
         * every 40 seconds for five minutes (ChatModelTest): it had a line for three deaths in four
         * as it was, and has one for about two in five now, while it answers to its name as often
         * as ever.
         */
        const val TIRE_MS = 180_000L
        const val TIRE = 0.25
        /** Something typed that reads as a question to whoever is there. */
        private val OPEN_QUESTION = Regex("\\?|^(who|whos|what|whats|why|how|when|where|does|do|did|is|are|can|anyone|any1|anybody|wanna|want|should|u guys|yall)\\b")
        /** A player asking why they are being targeted, or saying that they are being teamed on. */
        private const val TEAMING = "\\b(stop|why|quit)\\b.*\\b(target|targeting|attack|attacking|chasing|chase|focus|focusing|hitting)\\b|" +
            "\\b(teaming|teamers|teamer|teamed|team on|[2-9]v1|targeting me|ganging|gang up|all on me)\\b"
    }

    private fun prune(now: Long) {
        grudges.entries.removeIf { now >= it.value.second }
        while(casts.firstOrNull()?.let { now - it.time > 45000 } == true) casts.removeFirst()
        while(hits.firstOrNull()?.let { now - it.time > 20000 } == true) hits.removeFirst()
        while(deaths.firstOrNull()?.let { now - it.time > 20000 } == true) deaths.removeFirst()
        while(runs.firstOrNull()?.let { now - it.second > RUN_MS } == true) runs.removeFirst()
        topics.entries.removeIf { now - it.value.time > 20000 }
        ambient.entries.removeIf { now - it.value > 60000 }
    }
}
