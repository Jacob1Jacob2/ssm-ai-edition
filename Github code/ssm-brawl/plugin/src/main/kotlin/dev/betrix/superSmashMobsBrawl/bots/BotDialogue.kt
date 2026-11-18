package dev.betrix.superSmashMobsBrawl.bots

import java.util.UUID
import kotlin.random.Random

/** Local, event-driven dialogue. No network requests, and no claims without match evidence.
 * All state belongs to one match; Bukkit is kept outside this class for deterministic testing.
 * This class decides who speaks, when, and about what; [BotLines] holds the wording.
 *
 * @param reactions how often bots voice optional reactions (kills, deaths, crystals, the match
 *   starting). Real players do not comment on everything; 1.0 makes them (used by tests). */
class BotDialogue(private val random: Random = Random.Default, memory: LineMemory = LineMemory.shared,
                  private val reactions: Double = 0.65) {
    data class Fighter(val id: UUID, val name: String, val kit: String, val lives: Int,
                       val bot: Boolean, val fighting: Boolean = true)
    data class Reply(val speaker: UUID, val text: String)
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
                                val grudge: UUID? = null, val allies: List<UUID> = emptyList())
    private val lines = BotLines(random, memory)
    private val casts = ArrayDeque<Cast>()
    private val hits = ArrayDeque<Hit>()
    private val deaths = ArrayDeque<Death>()
    private val topics = mutableMapOf<UUID, Topic>()
    private val lastBot = mutableMapOf<UUID, Long>()
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
        if (fresh.isNotEmpty()) cast += BotPersonality.deal(fresh, cast, random)
    }

    private fun voiced(share: Double) = reactions >= 1.0 || random.nextDouble() < reactions * share

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
        if (voiced(1.0)) offer("death", victim, "", now, 1)
    }
    fun chat(player: UUID, message: String, now: Long): Boolean {
        // Do not retain a chat transcript, parse formatting, or execute anything from chat.
        return offer("chat", player, message.take(256).lowercase().replace('’', '\''), now, 3)
    }
    fun crystal(player: UUID, tier: String, now: Long) = voiced(0.9) && offer("crystal", player, tier.take(48), now, 1)
    fun start(now: Long) = voiced(0.55) && offer("start", null, "", now, 0)
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
    fun clear() {
        pending = null; followups.clear(); casts.clear(); hits.clear(); deaths.clear(); topics.clear(); lastBot.clear()
        recentLines.clear(); ambient.clear(); callouts.clear(); quietUntil.clear(); cast.clear(); grudges.clear()
    }

    private fun offer(kind: String, actor: UUID?, detail: String, now: Long, priority: Int,
                      other: UUID? = null, count: Int = 0): Boolean {
        if (mode == BotPersonality.SILENT) return false
        val key = "$kind:$actor"
        if (kind != "chat" && now - (ambient[key] ?: -100_000L) < 35_000) return false
        if (pending != null && pending!!.priority >= priority) return false
        val due = maxOf(now + random.nextLong(1300, 2801), lastSpoke + 5000)
        pending = Pending(kind, actor, detail, due, now + if(kind == "chat") 12000 else 8000, priority, other, count)
        return true
    }

    private fun names(f: Fighter, text: String): Boolean {
        fun word(w: String) = Regex("(?<![a-z0-9_])${Regex.escape(w)}(?![a-z0-9_])").containsMatchIn(text)
        val nick = BotSlang.nick(f.name)
        return word(f.name.lowercase()) || (nick.length >= 4 && word(nick))
    }

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
                return send(speaker, next.text, now)
            }
            // The conversation stalled (its speaker left, or the server hung): drop the rest of it.
            followups.clear()
        }
        val request = pending ?: return null
        if (now > request.expires) { pending = null; return null }
        if (now < request.due || now - lastSpoke < 5000) return null
        pending = null
        val actor = fighters.find { it.id == request.actor }
        val bots = fighters.filter { it.bot && talks(it.id) && now - (lastBot[it.id] ?: -100_000L) >= if(request.kind == "chat") 5000 else 12000 }
        if (bots.isEmpty() || (request.actor != null && actor == null)) return null
        val mentioned = bots.find { names(it, request.detail) }
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
            // Telling someone to come out and fight is for a bot that is still in the match.
            "hold" -> (involved ?: relevant)?.takeIf { it.lives > 0 }
                ?: bots.filter { it.lives > 0 && it.id != actor?.id }.randomOrNull(random) ?: return null
            else -> mentioned ?: relevant ?: deathSpeaker ?: bots.random(random)
        }
        val say = lines.speaker(speaker, fighters, personalityOf(speaker.id))
        // What the line is on and who it is about, for any bot that wants to answer it.
        var about: String? = null
        var who: Fighter? = null
        var level = 0
        var fell = false
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
            "burst" -> if (enemies(speaker.id, actor!!.id)) say.burst(actor, request.count).also { about = "burst"; who = actor } else null
            "trap" -> if (enemies(speaker.id, actor!!.id)) say.trapped(actor, request.count) else null
            "retreat" -> if (speaker.fighting) {
                val threat = fighters.find { it.id == request.other && it.fighting }
                about = "retreat"; who = threat
                say.retreat(threat)
            } else null
            "start" -> say.start(fighters.firstOrNull { !it.bot })
            "status" -> fighters.filter { it.fighting }.maxByOrNull { it.lives }?.let(say::status)
            else -> null
        } ?: return null
        ambient["${request.kind}:${request.actor}"] = now
        val reply = send(speaker, text, now) ?: return null
        about?.let { banter(speaker, it, who, level, fell, fighters, now, enemies) }
        return reply
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
        fun queue(f: Fighter, text: String?, grudge: UUID? = null, allies: List<UUID> = emptyList()): Boolean {
            if (text == null || followups.size >= MAX_CONVERSATION) return false
            at += random.nextLong(1500, 3501)
            followups.addLast(Followup(f.id, text, at, grudge, allies))
            return true
        }
        val talkers = fighters.filter { it.bot && it.id != speaker.id && talks(it.id) }
        // The bot the line was about answers for itself, even from the respawn screen.
        val aimedAt = who?.let { w -> talkers.find { it.id == w.id } }
        // Bystanders: still in the match (a respawning bot can type), not the subject, and not
        // fresh out of another exchange.
        val idle = talkers.filter { it.lives > 0 && it.id != who?.id && now - (lastBot[it.id] ?: -100_000L) >= 12000 }
        val calm = idle.filter { mood(it) == BotPersonality.CHILL }
        val mean = idle.filter { mood(it) == BotPersonality.TOXIC }
        val sour = idle.filter { mood(it) == BotPersonality.SALTY }
        /** [a] says it is going after [b]: true when the two can actually fight each other. */
        fun feud(a: Fighter, b: Fighter) = a.lives > 0 && b.lives > 0 && enemies(a.id, b.id)
        /**
         * [angry] has had enough of [heckler] and says so; the heckler enjoys it. Both lines come
         * with a grudge, so in a free-for-all the two of them leave everyone else and fight it out.
         */
        fun blowUp(angry: Fighter, heckler: Fighter) {
            val real = feud(angry, heckler)
            if (queue(angry, voice(angry).snap(heckler), heckler.id.takeIf { real }) && roll(0.7))
                queue(heckler, voice(heckler).taunt(angry), angry.id.takeIf { real })
        }
        // A lobby that argues about every single hit is as fake as one that never does.
        val fresh = now - lastBanter >= ARGUMENT_GAP_MS
        val humanDied = about == "kill" && who?.bot == false

        /** Someone on the speaker's wavelength backs it up in a word or two. */
        fun pileOn(from: List<Fighter>) {
            val chance = when { about == "hold" && level >= 1 -> 0.3; about == "burst" -> 0.3; humanDied -> 0.12; else -> 0.0 }
            val other = from.randomOrNull(random) ?: return
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
                answered = queue(aimedAt, voice(aimedAt).comeback(speaker, about))
                if (answered && mood(aimedAt) == BotPersonality.TOXIC && roll(0.5)) queue(speaker, voice(speaker).comeback(aimedAt, "beef"))
            }
            // 2. A chill bystander steps in, gets told off, and may decide enough is enough.
            val defender = calm.randomOrNull(random)
            if (defender != null && about != "retreat" && fresh && roll(0.65) && queue(defender, voice(defender).defend(who, speaker, about))) {
                lastBanter = now
                if (roll(0.7) && queue(speaker, voice(speaker).rebuke(defender))) teamUp(speaker, defender)
                return
            }
            if (answered) { lastBanter = now; return }
            // 3. Otherwise another toxic bot laughs at the excuse, or piles on.
            val heckler = mean.randomOrNull(random)
            if (about == "death" && heckler != null && fresh && roll(0.3) && queue(heckler, voice(heckler).mock(speaker, fell))) {
                lastBanter = now
                if (roll(0.5)) queue(speaker, voice(speaker).comeback(heckler, "beef"))
            } else pileOn(mean)
        } else if (mood(speaker) == BotPersonality.SALTY) {
            // A complaint (or a grudging "finally" after a kill).
            // 1. A toxic bot rubs it in: the one it was about if there is one, else a bystander.
            //    The salty one snaps and the two go for each other.
            val heckler = aimedAt?.takeIf { mood(it) == BotPersonality.TOXIC } ?: mean.randomOrNull(random)
            if (heckler != null && about != "kill" && roll(if (fresh) 0.65 else 0.25) &&
                queue(heckler, voice(heckler).needle(speaker, about, about == "death" && heckler.id == who?.id))) {
                lastBanter = now
                if (roll(0.8)) blowUp(speaker, heckler)
                return
            }
            // 2. A chill bot tries to cheer it up, which does not really work.
            val friend = aimedAt?.takeIf { mood(it) == BotPersonality.CHILL } ?: calm.randomOrNull(random)
            if (friend != null && about != "kill" && fresh && roll(0.45) && queue(friend, voice(friend).console(speaker))) {
                lastBanter = now
                if (roll(0.5)) queue(speaker, voice(speaker).grumble(friend))
                return
            }
            // 3. Whoever it was about answers for themselves.
            if (aimedAt != null && roll(if (fresh) 0.45 else 0.2)) {
                if (queue(aimedAt, voice(aimedAt).comeback(speaker, about))) lastBanter = now
                return
            }
            // 4. Or another salty bot knows the feeling.
            val fellow = sour.randomOrNull(random)
            if (fellow != null && about != "kill" && roll(0.3)) queue(fellow, voice(fellow).sameHere())
        } else {
            // A friendly line. 1. A toxic bot cannot stand it, and the chill one does not take the bait.
            val hater = (mean + listOfNotNull(aimedAt?.takeIf { mood(it) == BotPersonality.TOXIC })).distinctBy { it.id }.randomOrNull(random)
            if (hater != null && about in setOf("kill", "death", "burst", "hold") && fresh && roll(0.45) &&
                queue(hater, voice(hater).sneer(speaker, about))) {
                lastBanter = now
                if (roll(0.65) && queue(speaker, voice(speaker).brushOff(hater)) && roll(0.4)) teamUp(hater, speaker)
                return
            }
            // 2. Or the fighter it was about simply answers.
            if (aimedAt != null && roll(if (fresh) 0.45 else 0.2)) {
                val answer = voice(aimedAt).let { if (about == "burst") it.thanks(speaker) else it.comeback(speaker, about) }
                if (queue(aimedAt, answer)) lastBanter = now
                return
            }
            pileOn(calm)
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
        val bot = fighters.filter { it.bot && talks(it.id) }.let { list -> list.find { it.id in winners } ?: list.randomOrNull(random) } ?: return null
        val say = lines.speaker(bot, fighters, personalityOf(bot.id))
        val text = (if (bot.id in winners) say.won() else say.lost(winner, (callouts[winner.id] ?: 0) > 0)) ?: return null
        return send(bot, text, now)
    }

    /** Styles a line and sends its first message; a second message after " / " follows a moment later. */
    private fun send(speaker: Fighter, raw: String, now: Long): Reply? {
        val parts = raw.split(" / ").map(BotSlang::style).filter { it.isNotEmpty() }
        val text = parts.firstOrNull() ?: return null
        // The same words twice running reads as a script.
        if (text in recentLines.takeLast(2)) return null
        // The rest of this bot's own line comes before anybody else's answer to it.
        if (parts.size > 1) followups.addFirst(Followup(speaker.id, parts.drop(1).joinToString(" / "), now + random.nextLong(900, 2201)))
        lastSpoke = now; lastBot[speaker.id] = now
        recentLines.addLast(text); while (recentLines.size > 12) recentLines.removeFirst()
        return Reply(speaker.id, text)
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
        if (has("\\b(stop|why|quit)\\b.*\\b(target|targeting|attack|attacking|chasing|chase|focus|focusing|hitting)\\b|\\b(teaming|teamers|teamer|2v1|targeting me)\\b")) {
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
        if (has("\\b(hack|hacks|hacker|hacking|cheat|cheats|cheater|cheating|aimbot|reach|kill ?aura|client)\\b")) return say.notHacking()
        if (has("\\b(ez+|easy|trash|bad|suck|sucks|noob|nub|cope|skill issue|washed|loser|bozo|garbage|dogwater|get good|git gud|mad|cry|lmao|lmfao)\\b|^l+(?:\\s|$)|\\b(shut up|stfu|you lost|u lost|you'?re dead|ur dead|i win|i won|winning)\\b")) {
            topic("score")
            if (death?.victim == bot.id) return say.oneKill(death.ability)
            if (death?.victim == player.id) return say.youDied(death.ability)
            return when {
                player.lives <= 0 -> say.youAreOut()
                bot.lives <= 0 -> say.iAmOut(player)
                player.lives > bot.lives -> say.behind(player)
                player.lives < bot.lives -> say.ahead(player)
                else -> say.tied()
            }
        }
        if (has("\\b(gg|ggs|good game|gf|good fight|wp)\\b")) return say.gg(player)
        if (has("\\b(come|fight|melee|1v1|run|running|hiding|hide|scared|coward)\\b")) {
            topic("fight")
            return if (!player.fighting) say.fightWhileTheyAreOut()
                else if (!bot.fighting) say.fightWhileOut()
                else say.fight(player)
        }
        if (has("\\b(bot|bots|ai|npc)\\b")) return say.calledBot()
        if (has("\\b(again|always)\\b") && has("\\b(chicken|skeleton|wolf|pig|guardian|phantom|bear|kit)\\b")) return say.kitAgain(player)
        if (has("\\b(fun|boring|annoying|bored)\\b")) { topic("fun"); return say.notFun() }
        if (has("\\b(nice|good|great) (shot|hit|aim|combo)\\b|\\bns\\b")) return say.thanks(player)
        if (has("\\b(sorry|sry|my bad|mb)\\b")) return say.forgive(player)
        if (has("\\b(gl|glhf|good luck|hello|hey|hi|yo|sup)\\b")) return say.greet(player)
        if (previous != null && has("^(why|how|what|wdym|nah|no|yes|yeah|ya|ok|okay|sure|to win|because|bc|cuz|lol|bruh|bro|\\?+)(?![a-z])")) {
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
        /** Minimum time between two arguments in one lobby. */
        const val ARGUMENT_GAP_MS = 18_000L
        /** The most lines a conversation between bots can queue up. */
        const val MAX_CONVERSATION = 7
    }

    private fun prune(now: Long) {
        grudges.entries.removeIf { now >= it.value.second }
        while(casts.firstOrNull()?.let { now - it.time > 45000 } == true) casts.removeFirst()
        while(hits.firstOrNull()?.let { now - it.time > 20000 } == true) hits.removeFirst()
        while(deaths.firstOrNull()?.let { now - it.time > 20000 } == true) deaths.removeFirst()
        topics.entries.removeIf { now - it.value.time > 20000 }
        ambient.entries.removeIf { now - it.value > 60000 }
    }
}
