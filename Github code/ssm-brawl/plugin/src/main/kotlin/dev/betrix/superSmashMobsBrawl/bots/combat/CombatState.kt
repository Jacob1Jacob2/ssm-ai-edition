package dev.betrix.superSmashMobsBrawl.bots.combat

import dev.betrix.superSmashMobsBrawl.bots.BotDifficulty

/** What a bot is trying to do right now. */
enum class CombatState {
    /** Not in a live fight (lobby, countdown, respawning). */
    IDLE,
    /** No opponent to fight: drift back toward the middle of the map. */
    ROAM,
    ATTACK,
    /** Low on health: put distance between itself and the opponent. */
    FLEE,
    /** Walking to an item pickup. */
    GET_ITEM,
    /** Going for the Smash Crystal: running to its pad, or holding the pad until it lands. */
    GET_CRYSTAL,
    /** Knocked off the map and trying to get back. Decided by the brain's physics, not a policy. */
    RECOVER,
}

/** The facts a policy decides on. Fractions are 0..1; distances are blocks; null means "none". */
data class CombatSituation(
    val health: Double,
    val targetHealth: Double?,
    val targetDistance: Double?,
    /** Distance to the nearest reachable item pickup. */
    val itemDistance: Double?,
    /** How far the closest opponent is from that same pickup (who wins the race). */
    val itemEnemyDistance: Double?,
    /** Share of max health lost over roughly the last two seconds. */
    val recentDamage: Double,
    /** Attack abilities off cooldown right now. */
    val attacksReady: Int,
    /** Distance to the pad of a Smash Crystal this bot could still claim. */
    val crystalDistance: Double? = null,
    /** Ticks until that crystal can be taken; 0 once it has landed. */
    val crystalLandsIn: Int = 0,
    /**
     * That pickup is the way forward: the opponent sits where nothing walks, and this is something
     * to get to them or hit them with. Worth the walk however far it is.
     */
    val itemNeeded: Boolean = false,
)

/**
 * A Smash Crystal is the strongest thing on the map, so it outranks items, running away and most
 * fights. Returns null when the crystal should not change what the bot is doing.
 *
 * An [eager] bot sets off the moment the crystal appears and holds the pad; a basic one finishes the
 * exchange it is in and leaves just in time to be there when it lands.
 */
internal fun crystalPlan(s: CombatSituation, eager: Boolean, current: CombatState): CombatState? {
    val away = s.crystalDistance ?: return null
    val enemy = s.targetDistance
    if (s.crystalLandsIn == 0) return CombatState.GET_CRYSTAL // it is lying there: take it
    // Waiting at the pad: keep whoever else wants it away, but never wander off after them. A
    // fight already under way may drift a little further before the bot turns back, so it does
    // not flip between fighting and walking back with every step.
    val leash = if (current == CombatState.ATTACK) 8.0 else 4.0
    if (away <= leash) return if (enemy != null && enemy < 8.0) CombatState.ATTACK else CombatState.GET_CRYSTAL
    val travelTicks = away / 4.0 * 20 // about four blocks a second on foot
    if (!eager && s.crystalLandsIn > travelTicks + 100) return null
    if (!eager && enemy != null && enemy < 5.0) return null
    return CombatState.GET_CRYSTAL
}

/**
 * Chooses between ATTACK, FLEE, GET_ITEM, GET_CRYSTAL and ROAM. IDLE and RECOVER are forced by the
 * brain.
 */
fun interface CombatPolicy {
    fun decide(s: CombatSituation, current: CombatState, ticksInState: Long): CombatState

    companion object {
        /** Normal and Hard think like [NormalPolicy]; Harder and Expert like [ExpertPolicy]. */
        fun of(difficulty: BotDifficulty): CombatPolicy = if (difficulty.smartTargeting) ExpertPolicy else NormalPolicy
    }
}

/**
 * A basic state machine: run when hurt, grab whatever is lying nearby, otherwise fight the target.
 * It makes no attempt to judge whether running or detouring is actually a good idea.
 */
object NormalPolicy : CombatPolicy {
    private const val FLEE_BELOW = 0.25
    private const val FLEE_FOR_TICKS = 100L
    private const val ITEM_RANGE = 12.0

    override fun decide(s: CombatSituation, current: CombatState, ticksInState: Long): CombatState {
        crystalPlan(s, eager = false, current)?.let { return it }
        val threatened = s.targetDistance != null && s.targetDistance < 10
        if (current == CombatState.FLEE) {
            // Health does not come back by itself, so running ends on a timer or once it is safe.
            if (threatened && ticksInState < FLEE_FOR_TICKS) return CombatState.FLEE
        } else if (s.health < FLEE_BELOW && threatened && current == CombatState.ATTACK && ticksInState > 60) {
            return CombatState.FLEE
        }
        if (s.itemDistance != null && s.itemNeeded) return CombatState.GET_ITEM
        if (s.itemDistance != null && s.itemDistance < ITEM_RANGE &&
            (s.targetDistance == null || s.targetDistance > 5 || current == CombatState.GET_ITEM)) return CombatState.GET_ITEM
        return if (s.targetDistance == null) CombatState.ROAM else CombatState.ATTACK
    }
}

/**
 * Plays to win the exchange: finishes weak opponents instead of running, only disengages while its
 * abilities are down and it is losing, and only detours for items it will reach first.
 */
object ExpertPolicy : CombatPolicy {
    override fun decide(s: CombatSituation, current: CombatState, ticksInState: Long): CombatState {
        val distance = s.targetDistance
        val targetHealth = s.targetHealth ?: 1.0
        val finishing = distance != null && distance < 8 && targetHealth < 0.3
        // A kill that is one hit away comes first, unless the crystal is already there for the taking.
        if (!finishing || s.crystalLandsIn == 0) crystalPlan(s, eager = true, current)?.let { return it }
        if (!finishing && distance != null && distance < 9) {
            val losing = s.health < 0.3 && targetHealth > s.health + 0.15
            val pressured = s.attacksReady == 0 || s.recentDamage > 0.3
            // A short reset while cooldowns return, never a long retreat.
            if (losing && pressured && (current != CombatState.FLEE || ticksInState < 60) &&
                (current == CombatState.FLEE || ticksInState > 40)) return CombatState.FLEE
        }
        val item = s.itemDistance
        // Nothing else gets the bot to this opponent: the item comes before standing and staring.
        if (item != null && s.itemNeeded) return CombatState.GET_ITEM
        if (item != null && !finishing) {
            val winsRace = s.itemEnemyDistance == null || item + 1.5 < s.itemEnemyDistance
            val cheapDetour = distance == null || item < distance * 0.75 || item < 5
            if (item < 24 && winsRace && (cheapDetour || current == CombatState.GET_ITEM)) return CombatState.GET_ITEM
        }
        return if (distance == null) CombatState.ROAM else CombatState.ATTACK
    }
}

/** Remembers the current state and how long the bot has been in it. */
class CombatStateMachine(private val policy: CombatPolicy) {
    var state = CombatState.IDLE; private set
    private var enteredTick = 0L

    fun ticksInState(tick: Long) = tick - enteredTick

    /** Asks the policy. Returns the previous state if it changed, else null. */
    fun update(situation: CombatSituation, tick: Long): CombatState? =
        force(policy.decide(situation, state, tick - enteredTick), tick)

    /** For states the brain decides itself (IDLE, RECOVER). Returns the previous state if it changed. */
    fun force(next: CombatState, tick: Long): CombatState? {
        if (next == state) return null
        val previous = state
        state = next
        enteredTick = tick
        return previous
    }
}
