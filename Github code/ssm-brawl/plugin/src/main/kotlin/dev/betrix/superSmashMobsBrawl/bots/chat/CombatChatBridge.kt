package dev.betrix.superSmashMobsBrawl.bots.chat

import dev.betrix.superSmashMobsBrawl.bots.BotDialogue
import dev.betrix.superSmashMobsBrawl.bots.combat.*
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Listens to a match's combat events and tells the Chatting AI when something worth talking about
 * has happened. This is the only link between the two systems: detectors here turn raw health,
 * position and targeting data into chat triggers, and [BotDialogue] decides whether and what a bot
 * actually says (personality, rate limits, wording).
 */
class CombatChatBridge(bus: CombatEventBus, private val dialogue: BotDialogue, private val isBot: (UUID) -> Boolean) {
    private data class Blow(val attacker: UUID, val amount: Double, val time: Long)
    private class Hold(val x: Double, val y: Double, val z: Double, val since: Long)
    private data class Pursuit(val target: UUID, val since: Long)

    private val blows = HashMap<UUID, ArrayDeque<Blow>>()
    private val holds = HashMap<UUID, Hold>()
    private val heights = HashMap<UUID, Double>()
    /** Bot -> the opponent it is currently trying to reach. */
    private val pursuits = HashMap<UUID, Pursuit>()
    private val lastHit = HashMap<Pair<UUID, UUID>, Long>()
    private val unreachable = HashMap<UUID, Pair<UUID, Long>>()

    init {
        bus.on<MatchStarted> { dialogue.start(it.time) }
        bus.on<AbilityCast> { dialogue.cast(it.player, it.ability, it.time) }
        bus.on<CrystalActivated> { dialogue.crystal(it.player, it.tier, it.time) }
        bus.on<DamageDealt>(::damage)
        bus.on<FighterDied> {
            dialogue.death(it.victim, it.killer, it.source, it.time)
            holds.remove(it.victim); blows.remove(it.victim)
            // Its chase ended with its life. (It picks someone again when it is back, if it is
            // coming back: a bot that is out of the match is not hunting anybody.)
            pursuits.remove(it.victim)
        }
        bus.on<FighterSample>(::sample)
        bus.on<BotTargetChanged> {
            if (it.to == null) pursuits.remove(it.bot) else pursuits[it.bot] = Pursuit(it.to, it.time)
        }
        bus.on<TargetUnreachable> { unreachable[it.target] = it.bot to it.time }
        bus.on<TrapLearned> { dialogue.trapped(it.bot, it.camper, it.deaths, it.time) }
        bus.on<BotStateChanged> {
            if (it.to == CombatState.FLEE) dialogue.retreat(it.bot, pursuits[it.bot]?.target, it.time)
        }
    }

    /** "Too much damage too fast": several hits from one attacker taking a large share of a bot's health. */
    private fun damage(e: DamageDealt) {
        dialogue.damage(e.attacker, e.victim, e.time)
        lastHit[e.attacker to e.victim] = e.time
        if (!isBot(e.victim) || e.attacker == e.victim || e.victimMaxHealth <= 0) return
        val recent = blows.getOrPut(e.victim) { ArrayDeque() }
        recent.addLast(Blow(e.attacker, e.amount, e.time))
        while (recent.firstOrNull()?.let { e.time - it.time > BURST_WINDOW_MS } == true) recent.removeFirst()
        val mine = recent.filter { it.attacker == e.attacker }
        if (mine.size >= BURST_HITS && mine.sumOf { it.amount } >= e.victimMaxHealth * BURST_SHARE) {
            dialogue.burst(e.victim, e.attacker, mine.size, e.time)
            recent.clear()
        }
    }

    /**
     * Camping: a fighter has stayed on one Y-level, in one small area, while a bot that wants to
     * fight them has not been able to land a hit. Standing still alone is never enough.
     */
    private fun sample(e: FighterSample) {
        heights[e.player] = e.y
        val hold = holds[e.player]
        if (hold == null || abs(e.y - hold.y) > HOLD_Y_BAND || hypot(e.x - hold.x, e.z - hold.z) > HOLD_RADIUS) {
            holds[e.player] = Hold(e.x, e.y, e.z, e.time)
            return
        }
        val held = e.time - hold.since
        // The dialogue only speaks about this if the fighter also kept casting the same ability.
        if (held >= SPAM_CAMP_MS) dialogue.camp(e.player, e.time)
        if (held < HOLD_MS) return
        val hunter = pursuits.entries.firstOrNull { (bot, pursuit) ->
            pursuit.target == e.player && e.time - pursuit.since >= FRUSTRATION_MS &&
                e.time - (lastHit[bot to e.player] ?: Long.MIN_VALUE / 2) >= FRUSTRATION_MS
        }?.key ?: unreachable[e.player]?.takeIf { e.time - it.second <= UNREACHABLE_MEMORY_MS }?.first ?: return
        val difference = e.y - (heights[hunter] ?: e.y)
        dialogue.holding(e.player, hunter, (held / 1000).toInt(), if (difference > 3) 1 else if (difference < -3) -1 else 0, e.time)
    }

    companion object {
        const val BURST_WINDOW_MS = 1500L
        const val BURST_SHARE = 0.45
        const val BURST_HITS = 2
        const val HOLD_Y_BAND = 2.0
        const val HOLD_RADIUS = 10.0
        const val HOLD_MS = 20_000L
        const val SPAM_CAMP_MS = 25_000L
        const val FRUSTRATION_MS = 10_000L
        const val UNREACHABLE_MEMORY_MS = 15_000L
    }
}
