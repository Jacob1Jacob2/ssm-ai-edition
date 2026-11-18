package dev.betrix.superSmashMobsBrawl.bots.combat

import java.util.UUID
import kotlin.reflect.KClass

/**
 * Everything the Combat AI and the match report about a fight. One bus exists per match; the
 * Chatting AI (and anything else, such as stats or replays) only ever listens to these events and
 * never reaches into a bot's brain.
 *
 * Events are plain data with no Bukkit types, so listeners can be unit tested.
 * [time] is always the match clock in milliseconds.
 */
sealed interface CombatEvent { val time: Long }

data class MatchStarted(override val time: Long) : CombatEvent

/** A fighter pressed an ability, whether or not it hit anything. */
data class AbilityCast(val player: UUID, val ability: String, override val time: Long) : CombatEvent

/** Damage that was actually applied. [source] is the ability or attack name shown in the kill feed. */
data class DamageDealt(
    val attacker: UUID,
    val victim: UUID,
    val amount: Double,
    val victimMaxHealth: Double,
    val source: String,
    override val time: Long,
) : CombatEvent

data class FighterDied(val victim: UUID, val killer: UUID?, val source: String, override val time: Long) : CombatEvent

data class CrystalActivated(val player: UUID, val tier: String, override val time: Long) : CombatEvent

/** Health and position of one living fighter (bot or human), sampled a few times per second. */
data class FighterSample(
    val player: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val health: Double,
    val maxHealth: Double,
    override val time: Long,
) : CombatEvent

/** A bot's state machine moved, e.g. ATTACK -> FLEE. */
data class BotStateChanged(val bot: UUID, val from: CombatState, val to: CombatState, override val time: Long) : CombatEvent

/** A bot picked a new opponent (or lost its opponent when [to] is null). */
data class BotTargetChanged(val bot: UUID, val from: UUID?, val to: UUID?, override val time: Long) : CombatEvent

/** A bot's pathfinding could not find any way to reach its opponent. */
data class TargetUnreachable(val bot: UUID, val target: UUID, override val time: Long) : CombatEvent

/** A bot has died to [camper] holding one spot often enough ([deaths]) to stop walking into it. */
data class TrapLearned(val bot: UUID, val camper: UUID, val deaths: Int, override val time: Long) : CombatEvent

/** Per-match publish/subscribe. Used on the server thread only. */
class CombatEventBus(private val onError: (CombatEvent, Throwable) -> Unit = { _, _ -> }) {
    private val listeners = HashMap<KClass<out CombatEvent>, MutableList<(CombatEvent) -> Unit>>()
    private val wildcard = ArrayList<(CombatEvent) -> Unit>()

    fun <T : CombatEvent> on(type: KClass<T>, listener: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        listeners.getOrPut(type) { ArrayList() }.add(listener as (CombatEvent) -> Unit)
    }

    inline fun <reified T : CombatEvent> on(noinline listener: (T) -> Unit) = on(T::class, listener)

    /** Receives every event; meant for logging and debugging. */
    fun onAny(listener: (CombatEvent) -> Unit) { wildcard.add(listener) }

    fun publish(event: CombatEvent) {
        // A broken chat line must never interrupt the fight that reported it.
        for (listener in listeners[event::class].orEmpty() + wildcard) {
            try { listener(event) } catch (e: Exception) { onError(event, e) }
        }
    }

    fun clear() { listeners.clear(); wildcard.clear() }
}
