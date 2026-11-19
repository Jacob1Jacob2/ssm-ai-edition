package dev.betrix.superSmashMobsBrawl.bots

import java.util.EnumMap
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The Team-Ups match mode: a free-for-all in which the bots may gang up on a player who makes a
 * nuisance of themselves, the way a real lobby does.
 *
 * Every player has a "heat" with the lobby. Camping where nobody can get a hit in, pressing one
 * ability over and over, farming the same fighter and running their mouth in chat all raise it;
 * time and, above all, dying cool it down. When it is high enough for one bot to say "everyone get
 * them" and for at least one more to agree, those bots have a pact: they leave each other alone
 * and go for that player together, until the player loses a life or the bots lose interest.
 *
 * How readily a bot teams is its personality. A Toxic one is the first to suggest it, a Salty one
 * does when it has a score to settle (being killed by that player is a grievance of its own), and
 * a Chill one thinks teaming is beneath it: it stays out unless the player has really been asking
 * for it all match. A pact with a Toxic bot in it may end with that bot turning on its partners.
 *
 * This class is the rules only: plain data in, plain events out, no Bukkit and no wording.
 * [BotDialogue] decides what gets said about it; the match asks [allied] and [targetOf].
 */
class TeamUps(private val random: Random = Random.Default) {
    data class Fighter(val id: UUID, val lives: Int, val bot: Boolean, val fighting: Boolean, val personality: BotPersonality)

    /** What a player is mostly held against. */
    enum class Reason { CAMPING, SPAM, FARMING, MOUTH }

    /** How a pact came to its end. */
    enum class End {
        /** The player lost a life: that was the point. */
        KILLED,
        /** Nobody got them in time, and the bots lost interest. */
        TIMEOUT,
        /** The player is out of the match (or left it). */
        GONE,
        /** Too few of the bots are left for it to be a team. */
        BROKEN,
        /** The player said sorry and the ones that take an apology took it. */
        FORGIVEN,
    }

    data class Pact(val target: UUID, val leader: UUID, val members: Set<UUID>, val reason: Reason, val since: Long, val until: Long)

    sealed interface Event
    /** [bot] is close to calling for a team-up on [target], and says so: a last chance to stop. */
    data class Warned(val bot: UUID, val target: UUID, val reason: Reason) : Event
    /** [declined] are the bots that were asked and would not. */
    data class Formed(val pact: Pact, val declined: List<UUID>) : Event
    /** [quitters] accepted the player's apology and left; the pact (now without them) goes on. */
    data class Left(val pact: Pact, val quitters: List<UUID>) : Event
    /** [turncoat], if any, has turned on [betrayed], one of its partners of a moment ago. */
    data class Ended(val pact: Pact, val how: End, val turncoat: UUID? = null, val betrayed: UUID? = null) : Event

    /** Off in every other match mode: nothing is counted and nobody teams. */
    var enabled = false
        set(value) { if (field && !value) clear(); field = value }

    var pact: Pact? = null
        private set

    private val heat = HashMap<UUID, Double>()
    private val why = HashMap<UUID, EnumMap<Reason, Double>>()
    private val casts = HashMap<UUID, ArrayDeque<Pair<String, Long>>>()
    private class Kill(val killer: UUID, val victim: UUID, val time: Long)
    private val kills = ArrayDeque<Kill>()
    private val traps = HashMap<Pair<UUID, UUID>, Long>()
    private val lastCamp = HashMap<UUID, Long>()
    private val lastUnreachable = HashMap<UUID, Long>()
    private val lastMouth = HashMap<UUID, Long>()
    private val lastSorry = HashMap<UUID, Long>()
    private val lastWarned = HashMap<UUID, Long>()
    private var roster: List<Fighter> = emptyList()
    private var polled = Long.MIN_VALUE
    private var restUntil = 0L
    private val out = ArrayList<Event>()

    fun clear() {
        pact = null; heat.clear(); why.clear(); casts.clear(); kills.clear(); traps.clear(); lastCamp.clear()
        lastUnreachable.clear(); lastMouth.clear(); lastSorry.clear(); lastWarned.clear(); roster = emptyList(); polled = Long.MIN_VALUE; restUntil = 0L
        out.clear()
    }

    /** How much the lobby has against [player] right now, 0 to [MAX]. */
    fun heatOf(player: UUID): Double = heat[player] ?: 0.0

    /** Are [a] and [b] partners in the pact, and so not to fight each other? */
    fun allied(a: UUID, b: UUID): Boolean = pact?.let { a in it.members && b in it.members } == true

    /** Who [bot] is after as a member of the pact. */
    fun targetOf(bot: UUID): UUID? = pact?.takeIf { bot in it.members }?.target

    /** The ability [player] keeps pressing, if there is one. */
    fun spammed(player: UUID, now: Long): String? {
        val recent = casts[player]?.filter { now - it.second <= SPAM_WINDOW_MS } ?: return null
        return recent.groupingBy { it.first }.eachCount().maxByOrNull { it.value }?.takeIf { it.value >= SPAM_FROM }?.key
    }

    /** How often [killer] has killed [victim] lately. */
    fun killsOf(killer: UUID, victim: UUID, now: Long): Int = kills.count { it.killer == killer && it.victim == victim && now - it.time <= GRIEVANCE_MS }

    private fun human(id: UUID) = roster.isEmpty() || roster.any { it.id == id && !it.bot }

    private fun add(player: UUID, amount: Double, reason: Reason) {
        // Only a player draws a team: what the bots do to each other is their own business.
        if (!enabled || amount <= 0.0 || !human(player)) return
        heat[player] = min(MAX, heatOf(player) + amount)
        why.getOrPut(player) { EnumMap(Reason::class.java) }.merge(reason, amount, Double::plus)
    }

    private fun cool(player: UUID, share: Double, less: Double) {
        val before = heatOf(player)
        if (before <= 0.0) return
        val after = max(0.0, before * share - less)
        if (after <= 0.0) { heat.remove(player); why.remove(player); return }
        heat[player] = after
        why[player]?.replaceAll { _, part -> part * after / before }
    }

    // ------------------------------------------------------------------ what the match reports

    /**
     * [player] is holding one spot while a bot that wants them cannot get a hit in. Reported for
     * as long as that is so, a few times a second.
     */
    fun camping(player: UUID, now: Long) {
        val last = lastCamp[player]
        val seconds = if (last == null || now - last > CAMP_GAP_MS) 0.25 else (now - last) / 1000.0
        lastCamp[player] = now
        add(player, CAMP_PER_SECOND * seconds, Reason.CAMPING)
    }

    fun cast(player: UUID, ability: String, now: Long) {
        if (!enabled) return
        val mine = casts.getOrPut(player) { ArrayDeque() }
        mine.addLast(ability to now)
        while (mine.firstOrNull()?.let { now - it.second > SPAM_WINDOW_MS } == true) mine.removeFirst()
        // The same button again and again; three times is just playing the kit.
        if (mine.count { it.first == ability } > SPAM_FROM) add(player, SPAM_HEAT, Reason.SPAM)
    }

    fun death(victim: UUID, killer: UUID?, now: Long) {
        if (!enabled) return
        // Dying is what cools a lobby down: they got what was coming.
        cool(victim, DEATH_SHARE, DEATH_COOLS)
        if (pact?.target == victim) end(End.KILLED, now)
        if (killer == null || killer == victim) return
        kills.addLast(Kill(killer, victim, now))
        while (kills.size > 64 || kills.firstOrNull()?.let { now - it.time > GRIEVANCE_MS } == true) kills.removeFirst()
        if (!human(victim) || roster.isEmpty()) add(killer, KILL_HEAT, Reason.FARMING)
        // The same one over and over is farming them. (Meeting everybody again in the course of a match is not.)
        if (kills.count { it.killer == killer && it.victim == victim && now - it.time <= FARM_WINDOW_MS } >= 2) add(killer, FARM_HEAT, Reason.FARMING)
    }

    /** Debug: counts [amount] against [player] for [reason], as if they had earned it. */
    fun provoke(player: UUID, amount: Double, reason: Reason = Reason.CAMPING) = add(player, amount, reason)

    /** The bot [bot] has died to [camper] sitting in one spot often enough to stop walking into it. */
    fun trap(bot: UUID, camper: UUID, now: Long) {
        traps[bot to camper] = now
        add(camper, TRAP_HEAT, Reason.CAMPING)
    }

    /** The bot [bot] has no way at all to get to [target]. */
    fun unreachable(bot: UUID, target: UUID, now: Long) {
        if (now - (lastUnreachable[target] ?: Long.MIN_VALUE / 2) < UNREACHABLE_GAP_MS) return
        lastUnreachable[target] = now
        add(target, UNREACHABLE_HEAT, Reason.CAMPING)
    }

    /** Something [player] typed in chat ([text] in lower case). Trash talk heats a lobby up, an apology cools it. */
    fun said(player: UUID, text: String, now: Long) {
        if (!enabled) return
        if (APOLOGY.containsMatchIn(text)) { sorry(player, now); return }
        if (INSULT.containsMatchIn(text) && now - (lastMouth[player] ?: Long.MIN_VALUE / 2) >= MOUTH_GAP_MS) {
            lastMouth[player] = now
            add(player, MOUTH_HEAT, Reason.MOUTH)
        }
    }

    private fun sorry(player: UUID, now: Long) {
        if (now - (lastSorry[player] ?: Long.MIN_VALUE / 2) < SORRY_GAP_MS) return
        lastSorry[player] = now
        cool(player, 1.0, APOLOGY_COOLS)
        val p = pact?.takeIf { it.target == player } ?: return
        // Only the ones that never wanted to team in the first place take it.
        val quitters = p.members.filter { m -> roster.find { it.id == m }?.personality == BotPersonality.CHILL }
        if (quitters.isEmpty()) return
        val rest = p.members - quitters.toSet()
        if (rest.size < 2) { end(End.FORGIVEN, now); return }
        val smaller = p.copy(members = rest, leader = if (p.leader in rest) p.leader else rest.first())
        pact = smaller
        out += Left(smaller, quitters)
    }

    // ------------------------------------------------------------------ the pact

    /** A bot's own temper: not every Toxic bot blows at exactly the same moment. Fixed by who it is. */
    private fun temper(bot: UUID) = Random(bot.mostSignificantBits xor bot.leastSignificantBits xor 0x7EA5L).nextDouble(-TEMPER, TEMPER)

    private fun joinsAt(f: Fighter) = temper(f.id) + when (f.personality) {
        BotPersonality.TOXIC -> 40.0
        BotPersonality.SALTY -> 50.0
        BotPersonality.CHILL -> 92.0
        else -> 60.0
    }

    private fun leadsAt(f: Fighter) = temper(f.id) + when (f.personality) {
        BotPersonality.TOXIC -> 50.0
        BotPersonality.SALTY -> 56.0
        BotPersonality.CHILL -> 104.0
        else -> 70.0
    }

    /** What [bot] has against [target] personally, on top of what everybody has. */
    private fun grievance(bot: UUID, target: UUID, now: Long): Double {
        val killed = killsOf(target, bot, now) * KILLED_ME
        val trapped = if (now - (traps[bot to target] ?: Long.MIN_VALUE / 2) <= GRIEVANCE_MS) TRAPPED_ME else 0.0
        return min(GRIEVANCE_MOST, killed + trapped)
    }

    private fun reasonFor(player: UUID): Reason = why[player]?.maxByOrNull { it.value }?.key ?: Reason.CAMPING

    /** Debug: where each bot stands with [target] right now, as "lives personality felt/leads-at/joins-at". */
    fun standing(target: UUID, now: Long, name: (UUID) -> String): String = roster.filter { it.bot }.joinToString(", ") { f ->
        "${name(f.id)} ${f.lives}hp ${f.personality.label.lowercase()} %.0f/%.0f/%.0f".format(heatOf(target) + grievance(f.id, target, now), leadsAt(f), joinsAt(f))
    } + (if (now < restUntil) "; resting ${(restUntil - now) / 1000}s" else "")

    /**
     * Moves the clock on: heat fades, a pact that has run its course ends, and a new one forms if
     * the lobby has had enough of somebody. Returns what happened since the last call, in order.
     */
    fun poll(now: Long, fighters: List<Fighter>): List<Event> {
        roster = fighters
        if (!enabled) return emptyList()
        val seconds = if (polled == Long.MIN_VALUE) 0.0 else (now - polled).coerceIn(0L, 2000L) / 1000.0
        polled = now
        // (No fading while they are at it: a camper does not get let off for camping patiently.)
        for (id in heat.keys.toList()) if (now - (lastCamp[id] ?: Long.MIN_VALUE / 2) > CAMP_GAP_MS) cool(id, 1.0, FADE_PER_SECOND * seconds)
        heat.keys.toList().filter { id -> fighters.none { it.id == id && !it.bot } }.forEach { heat.remove(it); why.remove(it) }

        pact?.let { p ->
            val target = fighters.find { it.id == p.target }
            val standing = p.members.count { m -> fighters.any { it.id == m && it.bot && it.lives > 0 } }
            when {
                target == null || target.lives <= 0 -> end(End.GONE, now)
                standing < 2 -> end(End.BROKEN, now)
                now >= p.until -> end(End.TIMEOUT, now)
            }
        }
        if (pact == null && now >= restUntil) form(now, fighters)
        return out.toList().also { out.clear() }
    }

    private fun form(now: Long, fighters: List<Fighter>) {
        val bots = fighters.filter { it.bot && it.lives > 0 }
        if (bots.size < 2) return
        val target = fighters.filter { !it.bot && it.lives > 0 }.maxByOrNull { heatOf(it.id) } ?: return
        val shared = heatOf(target.id)
        if (shared <= 0.0) return
        fun felt(bot: Fighter) = shared + grievance(bot.id, target.id, now)
        // Somebody has to say it first, and somebody has to agree: one bot is a grudge, not a team.
        val leader = bots.filter { felt(it) >= leadsAt(it) }.maxByOrNull { felt(it) - leadsAt(it) }
        if (leader == null) {
            // Not there yet, but one of them is close: it says what is coming, once, while the
            // player can still do something about it.
            val nearest = bots.maxByOrNull { felt(it) - leadsAt(it) } ?: return
            if (felt(nearest) >= leadsAt(nearest) - WARN_WITHIN && now - (lastWarned[target.id] ?: Long.MIN_VALUE / 2) >= WARN_GAP_MS) {
                lastWarned[target.id] = now
                out += Warned(nearest.id, target.id, reasonFor(target.id))
            }
            return
        }
        val joined = bots.filter { it.id != leader.id && felt(it) >= joinsAt(it) }
        if (joined.isEmpty()) return
        val formed = Pact(target.id, leader.id, (joined.map { it.id } + leader.id).toSet(), reasonFor(target.id), now, now + PACT_MS)
        pact = formed
        out += Formed(formed, bots.filter { it.id != leader.id && it !in joined }.map { it.id })
    }

    private fun end(how: End, now: Long) {
        val p = pact ?: return
        pact = null
        restUntil = now + REST_MS
        if (how == End.TIMEOUT) cool(p.target, 1.0, TIMEOUT_COOLS)
        var turncoat: UUID? = null
        var betrayed: UUID? = null
        if (how == End.KILLED || how == End.TIMEOUT) {
            // A truce with a Toxic bot lasts exactly as long as it is useful to the Toxic bot.
            val standing = p.members.filter { m -> roster.any { it.id == m && it.lives > 0 } }
            val snake = standing.filter { m -> roster.find { it.id == m }?.personality == BotPersonality.TOXIC }
                .shuffled(random).firstOrNull { random.nextDouble() < BETRAYAL }
            val victim = snake?.let { s -> (standing - s).randomOrNull(random) }
            if (snake != null && victim != null) { turncoat = snake; betrayed = victim }
        }
        out += Ended(p, how, turncoat, betrayed)
    }

    companion object {
        const val MAX = 120.0
        /** Heat lost a second by a player who is not camping at that moment. */
        const val FADE_PER_SECOND = 1.0
        const val CAMP_PER_SECOND = 2.5
        /** Reports of camping further apart than this are two spells of it, not one. */
        const val CAMP_GAP_MS = 2000L
        const val SPAM_WINDOW_MS = 45_000L
        /** Presses of one ability within [SPAM_WINDOW_MS] that are still just playing the kit. */
        const val SPAM_FROM = 3
        const val SPAM_HEAT = 5.0
        const val KILL_HEAT = 10.0
        const val FARM_HEAT = 10.0
        /** Killing the same fighter twice within this is farming them. */
        const val FARM_WINDOW_MS = 45_000L
        const val TRAP_HEAT = 25.0
        const val UNREACHABLE_HEAT = 6.0
        const val UNREACHABLE_GAP_MS = 8000L
        const val MOUTH_HEAT = 15.0
        const val MOUTH_GAP_MS = 10_000L
        const val APOLOGY_COOLS = 25.0
        const val SORRY_GAP_MS = 30_000L
        /** What is left of a player's heat when they die, and what comes off on top. */
        const val DEATH_SHARE = 0.4
        const val DEATH_COOLS = 10.0
        const val TIMEOUT_COOLS = 25.0
        /** How long a bot holds being killed or trapped against a player. */
        const val GRIEVANCE_MS = 120_000L
        const val KILLED_ME = 12.0
        const val TRAPPED_ME = 15.0
        const val GRIEVANCE_MOST = 30.0
        const val TEMPER = 8.0
        /** How long the bots stay on a player who does not die. */
        const val PACT_MS = 45_000L
        /** No new pact for this long after one has ended. */
        const val REST_MS = 30_000L
        /** A bot this close to calling for a team-up says so first, and not again for a while. */
        const val WARN_WITHIN = 14.0
        const val WARN_GAP_MS = 60_000L
        /** The chance that a Toxic member turns on a partner when the pact is over. */
        const val BETRAYAL = 0.35

        private val INSULT = Regex("\\b(ez+|easy|trash|bad|suck|sucks|noob|nub|cope|skill issue|washed|loser|bozo|garbage|dogwater|get good|git gud|cry|bots? (are|r) (bad|trash|dumb))\\b|^l+(?:\\s|$)")
        private val APOLOGY = Regex("\\b(sorry|sry|my bad|mb|ill stop|i'?ll stop|truce|peace|i give up|ok ok)\\b")
    }
}
