package dev.betrix.superSmashMobsBrawl.bots

import java.util.UUID
import kotlin.random.Random

/**
 * What the bots standing in a lobby say between matches: hello to whoever comes in, a word about
 * the match that was just played, impatience while everybody waits, and an answer when a player
 * types something. Like [BotDialogue] it knows nothing of Bukkit: it is told who is standing in the
 * lobby and what was typed, and gives back the next line, if there is one.
 *
 * A real lobby is mostly quiet, and this one is too: a few lines after a match, one now and then
 * while waiting, with longer and longer gaps, and nothing more once nobody has answered for a
 * while.
 *
 * @param recall what the bot called so remembers of a player ([BotMemory])
 * @param clock the time of day in milliseconds since 1970, which is what memories are dated in
 *   (the times this class is polled with only have to run forward)
 * @param eager tests: every chance comes off
 */
class LobbyTalk(private val random: Random = Random.Default, memory: LineMemory = LineMemory.shared,
                private val recall: (String, UUID) -> Acquaintance? = { _, _ -> null },
                private val clock: () -> Long = System::currentTimeMillis,
                private val eager: Boolean = false) {
    /** How somebody did in the last match played out of this lobby. */
    /** [side]: the team it was on, in a match of two teams ([BotDialogue.Fighter.side]). */
    data class Score(val name: String, val bot: Boolean, val kills: Int, val deaths: Int, val won: Boolean, val side: String? = null)

    /**
     * The last match played out of this lobby: when it ended (on the clock [poll] is called
     * with), where, how everybody did, and who killed whom how often (killer to victim, names in
     * lower case).
     */
    data class LastMatch(val endedAt: Long, val map: String, val scores: List<Score>, val kills: Map<Pair<String, String>, Int>)

    private data class Line(val speaker: UUID, val text: String, val due: Long)
    private class Asked(val player: UUID, val text: String, val due: Long, val expires: Long)

    private val lines = BotLines(random, memory)
    /** The lobby's setting: one personality for every bot, or Random for everybody being themselves. */
    var mode: BotPersonality = BotPersonality.SILENT
        set(value) {
            if (field != value) { cast.clear(); queue.clear(); asked = null }
            field = value
        }
    private val cast = HashMap<UUID, BotPersonality>()
    private val queue = ArrayDeque<Line>()
    private var asked: Asked? = null
    private var last: LastMatch? = null
    /** Lines said about [last] since everybody came back from it. */
    private var matchTalk = 0
    /** Players standing in the lobby, and bots, each since when. */
    private val here = HashMap<UUID, Long>()
    private val seen = HashMap<UUID, Long>()
    /** When somebody who has just come in is due a hello, and when they last had one. */
    private val hello = HashMap<UUID, Long>()
    private val greetedAt = HashMap<UUID, Long>()
    /** When the players last came back from a match; and a bot that turned up after it without having been in it. */
    private var backAt = -10_000_000L
    private var newcomer: UUID? = null
    private var welcomed = false
    private var lastSpoke = -100_000L
    private var idleAt = Long.MAX_VALUE
    private var idleSaid = 0

    val hasPending: Boolean get() = queue.isNotEmpty() || asked != null || hello.isNotEmpty()
    /** Debug: told why something a player typed got the answer it did, or none. */
    var trace: ((String) -> Unit)? = null

    private fun chance(p: Double) = eager || random.nextDouble() < p
    private fun personalityOf(bot: UUID) = if (mode != BotPersonality.RANDOM) mode else cast[bot] ?: BotPersonality.SILENT
    private fun talks(bot: UUID) = personalityOf(bot).let { it != BotPersonality.SILENT && it != BotPersonality.RANDOM }
    private fun voice(f: BotDialogue.Fighter, members: List<BotDialogue.Fighter>) = lines.speaker(f, members, personalityOf(f.id))
    private fun pick(from: List<BotDialogue.Fighter>) = BotCharacter.pick(from, random) { it.name }

    /** A match played out of this lobby has ended; its fighters are on their way back. */
    fun matchOver(match: LastMatch) { last = match; matchTalk = 0; welcomed = false; newcomer = null }

    /** [player] typed [message] in the lobby. */
    fun chat(player: UUID, message: String, now: Long) {
        if (mode == BotPersonality.SILENT) return
        // (As in a match: no transcript is kept, and nothing in it is run or parsed for formatting.)
        asked = Asked(player, message.take(256).lowercase().replace('’', '\''), now + random.nextLong(1300, 2801), now + 12_000)
        // Somebody is talking: the lobby is awake again.
        idleSaid = 0
        if (idleAt == Long.MAX_VALUE) idleAt = now + random.nextLong(40_000, 100_001)
    }

    /** The next line, if one is due. [members] is everybody standing in the lobby at [now]: the bots, and the players waiting. */
    fun poll(now: Long, members: List<BotDialogue.Fighter>): BotDialogue.Reply? {
        val bots = members.filter { it.bot }
        val people = members.filter { !it.bot }
        here.keys.retainAll(people.map { it.id }.toSet())
        hello.keys.retainAll(here.keys)
        seen.keys.retainAll(bots.map { it.id }.toSet())
        cast.keys.retainAll(seen.keys)
        for (p in people) if (here.putIfAbsent(p.id, now) == null) arrived(p, now)
        // (After a match every bot in the lobby is new to it: the one that was not in the match is the newcomer.)
        for (b in bots) if (seen.putIfAbsent(b.id, now) == null && !welcomed && now - backAt < NEWCOMER_MS &&
            last?.scores?.none { it.name.equals(b.name, true) } == true) { newcomer = b.id; welcomed = true }
        if (mode == BotPersonality.SILENT || people.isEmpty()) {
            if (asked != null) trace?.invoke("what was typed is dropped: ${if (people.isEmpty()) "nobody is standing in the lobby" else "the bots are Silent"}")
            queue.clear(); asked = null; hello.clear(); return null
        }
        if (mode == BotPersonality.RANDOM) bots.filter { it.id !in cast }.takeIf { it.isNotEmpty() }
            ?.let { fresh -> cast += BotCharacter.deal(fresh.map { it.id to it.name }) }
        val talkers = bots.filter { talks(it.id) }
        if (talkers.isEmpty()) return null
        // Somebody new has walked in after the match: they may say hi to whoever is standing there.
        newcomer?.let { id ->
            newcomer = null
            talkers.find { it.id == id }?.let { b -> if (chance(0.6)) later(b, voice(b, members).lobbyJoined(), now, 2500, 6000) }
        }

        // 1. A line that was already decided on: the second half of somebody's, or an answer to it.
        queue.firstOrNull()?.takeIf { now >= it.due }?.let { next ->
            queue.removeFirst()
            val speaker = talkers.find { it.id == next.speaker }
            if (speaker != null && now - next.due < 6000) return send(speaker, next.text, now)
            // (Whoever was going to say it has left.)
            queue.clear()
        }
        if (queue.isNotEmpty() || now - lastSpoke < GAP_MS) return null
        // 2. Something a player typed.
        asked?.let { q ->
            if (now > q.expires) { asked = null; trace?.invoke("what was typed is too old to answer now") }
            else if (now < q.due) return null
            else {
                asked = null
                val player = people.find { it.id == q.player }
                if (player == null) trace?.invoke("whoever typed it has gone")
                else answer(player, q.text, talkers, members, now)?.let { return it } ?: trace?.invoke("no answer to it")
            }
        }
        // 3. Hello to somebody who has just come in.
        hello.entries.firstOrNull { now >= it.value }?.key?.let { id ->
            hello.remove(id)
            people.find { it.id == id }?.let { p -> greet(p, talkers, members, now)?.let { return it } }
        }
        // 4. Something to say while everybody stands about.
        if (now >= idleAt) return idle(people, talkers, members, now)
        return null
    }

    private fun arrived(p: BotDialogue.Fighter, now: Long) {
        val back = last?.let { m -> now - m.endedAt < BACK_MS && m.scores.any { !it.bot && it.name.equals(p.name, true) } } == true
        idleSaid = 0
        if (back) {
            // Out of the match that has just ended: everybody said gg there. What comes now is talk about it.
            matchTalk = 0
            backAt = now
            idleAt = now + random.nextLong(4000, 9001)
        } else {
            // (Somebody who only stepped out for a moment is not said hello to all over again.)
            if (now - (greetedAt[p.id] ?: -10_000_000L) > REGREET_MS && chance(0.85)) hello[p.id] = now + random.nextLong(2500, 6001)
            idleAt = now + random.nextLong(25_000, 60_001)
        }
    }

    private fun greet(p: BotDialogue.Fighter, talkers: List<BotDialogue.Fighter>, members: List<BotDialogue.Fighter>, now: Long): BotDialogue.Reply? {
        // The bots come into a lobby a moment after the player does: nobody says hello in the tick they appear.
        val ready = talkers.filter { now - (seen[it.id] ?: now) >= 1000 }
        if (ready.isEmpty()) { hello[p.id] = now + 1000; return null }
        // Somebody who has played them before has more to say than "hi".
        val knowing = ready.filter { recall(it.name, p.id) != null }
        val first = pick(knowing.takeIf { it.isNotEmpty() && chance(0.7) } ?: ready) ?: return null
        val a = recall(first.name, p.id)
        val text = voice(first, members).lobbyHello(p, a, a?.fresh(clock()) == true) ?: return null
        greetedAt[p.id] = now
        val reply = send(first, text, now)
        val second = pick(ready.filter { it.id != first.id })
        if (second != null && chance(0.35)) later(second, voice(second, members).lobbyHi(), now, 1200, 3000)
        return reply
    }

    private fun idle(people: List<BotDialogue.Fighter>, talkers: List<BotDialogue.Fighter>, members: List<BotDialogue.Fighter>, now: Long): BotDialogue.Reply? {
        // Nobody has answered for a while: the lobby goes quiet until somebody says something or a match is played.
        if (idleSaid >= MAX_IDLE) { idleAt = Long.MAX_VALUE; return null }
        val match = last?.takeIf { now - it.endedAt < MATCH_TALK_MS }
        // The match that was just played is for those who were in it to talk about.
        val played = talkers.filter { t -> match?.scores?.any { it.bot && it.name.equals(t.name, true) } == true }
        val speaker = pick(played.takeIf { it.isNotEmpty() && matchTalk < 2 && chance(0.75) } ?: talkers) ?: return null
        // (The quiet ones mostly keep it to themselves; somebody else may say it in a moment.)
        if (!eager && random.nextDouble() > BotCharacter.chattyAmong(speaker.name, talkers.size)) { idleAt = now + random.nextLong(8000, 15_001); return null }
        val say = voice(speaker, members)
        val mine = match?.scores?.find { it.bot && it.name.equals(speaker.name, true) }
        var sour = false
        val text = when {
            match != null && mine != null && matchTalk < 2 -> {
                matchTalk++; sour = true
                val winner = if (mine.won) null else match.scores.firstOrNull { it.won }?.let { person(it, members) }
                val worst = match.kills.filterKeys { it.second == speaker.name.lowercase() }.maxByOrNull { it.value }
                val nemesis = worst?.let { w -> match.scores.find { it.name.lowercase() == w.key.first } }?.let { person(it, members) }
                // (With nobody to name as the winner there is nothing true to say about losing to them.)
                // (Two teams: how many were on its side and on the other.)
                val sided = mine.side?.let { s -> match.scores.count { it.side == s } to match.scores.count { it.side != s } }
                if (!mine.won && winner == null) null else say.lobbyAfter(winner, mine.kills, mine.deaths, nemesis, worst?.value ?: 0, sided)
            }
            // (Somebody who was not in it has no opinion of the map it was on.)
            else -> say.lobbyWait(people.first())
        }
        idleSaid++
        // Longer and longer gaps: people run out of things to say to a lobby that is not going anywhere.
        idleAt = now + (random.nextLong(40_000, 100_001) * Math.pow(1.6, idleSaid.toDouble())).toLong()
        val reply = send(speaker, text ?: return null, now)
        // (What is said back about the match comes from somebody else who was in it.)
        val other = pick((if (sour) played else talkers).filter { it.id != speaker.id })
        val mate = mine?.side?.let { s -> match?.scores?.find { it.bot && it.name.equals(other?.name, true) }?.side == s } == true
        if (other != null && chance(0.35)) later(other, voice(other, members).lobbyEcho(sour, mate), now, 1500, 3500)
        return reply
    }

    /** Somebody of the last match as a fighter to speak of: the one standing here, or just their name if they have gone. */
    private fun person(score: Score, members: List<BotDialogue.Fighter>) = members.find { it.name.equals(score.name, true) }
        ?: BotDialogue.Fighter(UUID.nameUUIDFromBytes("gone:${score.name.lowercase()}".toByteArray()), score.name, "", 0, score.bot, false)

    private fun answer(player: BotDialogue.Fighter, text: String, talkers: List<BotDialogue.Fighter>, members: List<BotDialogue.Fighter>,
                       now: Long): BotDialogue.Reply? {
        fun has(pattern: String) = Regex(pattern).containsMatchIn(text)
        val named = talkers.find { BotSlang.names(it.name, text) }
        // A message to a bot that says nothing is not answered by somebody else for it.
        if (named == null && members.any { it.bot && BotSlang.names(it.name, text) }) return null
        val kind = when {
            has(BotSlang.INSULT) || has(BotSlang.CHEATS) -> "comeback"
            has("\\b(who|whos|who's) (won|win|wins)\\b") -> "whowon"
            has("\\b(what|which|wat) (kit|class|mob)s?\\b|\\bwho (are|r) (you|u|yall) (playing|going)\\b") -> "kit"
            has("\\b(what|which|wat) map\\b") -> "map"
            has("\\b(rematch|again|1 more|one more|run it back|another|ready|rdy|start|starting|lets go)\\b|^r\\??$") -> "ready"
            has("\\b(bot|bots|ai|npc)\\b") -> "bot"
            has("\\b(gg|ggs|good game|gf|wp)\\b") -> "gg"
            has("\\b(bye|cya|gtg|gn|goodnight|im off|leaving)\\b") -> "bye"
            has("\\b(sorry|sry|my bad|mb)\\b") -> "sorry"
            has("\\b(gl|glhf|good luck|hello|hey|hi|yo|sup|wsg)\\b") -> "hi"
            else -> null
        }
        trace?.invoke("typed: about ${kind ?: "nothing they know"}, to ${named?.name ?: "nobody by name"}; ${talkers.size} of ${members.count { it.bot }} bots talk")
        if (kind == null && named == null) return null
        // Not everything typed into a lobby gets an answer. Something said to a bot by name does.
        if (named == null && !chance(0.93)) { trace?.invoke("nobody bothers to answer (one time in fourteen)"); return null }
        val knowing = talkers.filter { recall(it.name, player.id) != null }
        val bot = named ?: pick(knowing.takeIf { it.isNotEmpty() && kind == "comeback" } ?: talkers) ?: return null
        val say = voice(bot, members)
        val a = recall(bot.name, player.id)
        val fresh = a?.fresh(clock()) == true
        val match = last
        val line = when (kind) {
            "comeback" -> say.lobbyComeback(player, a, fresh)
            "whowon" -> match?.let { m ->
                val mine = m.scores.find { it.bot && it.name.equals(bot.name, true) }
                if (mine?.won == true) say.lobbyWhoWon(null, player)
                else m.scores.firstOrNull { it.won }?.let { say.lobbyWhoWon(person(it, members), player) }
            } ?: say.lobbyWhat()
            "kit" -> say.lobbyKit()
            "map" -> say.lobbyMap(match?.map?.takeIf { it.isNotEmpty() })
            "ready" -> say.lobbyReady(player)
            "bot" -> say.calledBot()
            "gg" -> say.gg(player)
            "bye" -> say.lobbyBye(player)
            "sorry" -> say.forgive(player)
            "hi" -> say.greet(player)
            else -> say.lobbyWhat()
        } ?: return null
        val reply = send(bot, line, now)
        // A couple of things a second one says too: "ready", "gg", "hi".
        val other = pick(talkers.filter { it.id != bot.id })
        if (other != null && named == null) voice(other, members).let { v ->
            when (kind) {
                "ready" -> if (chance(0.4)) later(other, v.lobbyReady(player), now, 900, 2500)
                "gg" -> if (chance(0.4)) later(other, v.gg(player), now, 900, 2500)
                "hi" -> if (chance(0.3)) later(other, v.lobbyHi(), now, 1200, 3000)
            }
        }
        return reply
    }

    private fun later(speaker: BotDialogue.Fighter, text: String?, now: Long, from: Long, until: Long) {
        if (text == null || queue.size >= MAX_QUEUED) return
        val after = maxOf(now, queue.lastOrNull()?.due ?: now)
        queue.addLast(Line(speaker.id, text, after + random.nextLong(from, until + 1)))
    }

    /** Styles a line and sends its first message; a second message after " / " follows a moment later. */
    private fun send(speaker: BotDialogue.Fighter, raw: String, now: Long): BotDialogue.Reply? {
        val parts = raw.split(" / ").map(BotSlang::style).filter { it.isNotEmpty() }
        val text = parts.firstOrNull() ?: return null
        if (parts.size > 1) queue.addFirst(Line(speaker.id, parts.drop(1).joinToString(" / "), now + random.nextLong(900, 2201)))
        lastSpoke = now
        return BotDialogue.Reply(speaker.id, text)
    }

    companion object {
        /** Between one line landing and the next being started on. */
        const val GAP_MS = 2500L
        /** Somebody who comes into the lobby this soon after a match they were in has come back from it. */
        const val BACK_MS = 60_000L
        /** A match is talked about for this long after it. */
        const val MATCH_TALK_MS = 240_000L
        /** Nobody is said hello to twice within this. */
        const val REGREET_MS = 180_000L
        /** A bot that turns up this soon after the players are back from a match has come in for the next one. */
        const val NEWCOMER_MS = 20_000L
        /** Lines said to a lobby in which nobody answers, before it goes quiet. */
        const val MAX_IDLE = 5
        const val MAX_QUEUED = 4
    }
}
