package dev.betrix.superSmashMobsBrawl.items

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket
import net.minecraft.server.network.ServerGamePacketListenerImpl
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player

/**
 * The A and D keys of a Flying Car.
 *
 * The car faces the way its driver looks, so turning the car with a key means turning the driver's
 * view: the server tells the driver's game to look a little further round, and the car follows
 * the look as it does a mouse. A player's own game decides where they look, and takes such an
 * order at once, in one jump; given once a tick, twenty times a second, the view would go round in
 * twenty jumps a second, which is the stutter of a slide show. So it is given every few
 * milliseconds, a fraction of a degree at a time, from a timer of its own beside the game's ticks
 * (a player's game takes what it is sent on every frame it draws). The game's tick only says which
 * way the keys are held.
 */
object CarSteering {
    private class Turn(val connection: ServerGamePacketListenerImpl) {
        /** Degrees a second the keys ask for now; set by the game's tick. */
        @Volatile var wanted = 0.0
        /** Degrees a second it is turning at, and when that was last worked out. */
        var rate = 0.0
        var last = System.nanoTime()
        /** How far it has been turned since that was last asked, and in how many steps: for the log. */
        @Volatile var turned = 0.0
        @Volatile var steps = 0
    }

    private val turns = ConcurrentHashMap<UUID, Turn>()
    @Volatile private var timer: ScheduledExecutorService? = null

    /** How often a turn is passed on, in milliseconds: several times within a frame of most screens. */
    const val STEP_MILLIS = 8L

    /** [player], driving, has the turn keys held so as to want [wanted] degrees a second (nothing held: 0). */
    fun steer(player: Player, wanted: Double) {
        val turn = turns[player.uniqueId] ?: run {
            if (wanted == 0.0) return
            turns.computeIfAbsent(player.uniqueId) { Turn((player as CraftPlayer).handle.connection) }
        }
        turn.wanted = wanted
        if (timer == null) start()
    }

    /** [player] is out of their car: nothing turns them any more. */
    fun release(player: UUID) { turns.remove(player) }

    /** How far the keys have turned [player] since this was last asked, in degrees, and in how many steps. */
    fun taken(player: UUID): Pair<Double, Int> {
        val turn = turns[player] ?: return 0.0 to 0
        val result = turn.turned to turn.steps
        turn.turned = 0.0; turn.steps = 0
        return result
    }

    @Synchronized private fun start() {
        if (timer != null) return
        timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "ssm-car-steering").apply { isDaemon = true } }.also {
            it.scheduleAtFixedRate(::step, STEP_MILLIS, STEP_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    /** The plugin is being switched off. */
    @Synchronized fun stop() {
        timer?.shutdownNow()
        timer = null
        turns.clear()
    }

    private fun step() {
        // (Anything thrown here would end the timer for good, without a word.)
        try {
            val now = System.nanoTime()
            for (turn in turns.values) {
                // By the time that has really passed, so that a late step turns further, not slower.
                val seconds = ((now - turn.last) / 1e9).coerceIn(0.0, 0.05)
                turn.last = now
                turn.rate = CarRules.turnEased(turn.rate, turn.wanted, seconds)
                val by = turn.rate * seconds
                if (abs(by) < 0.005) continue
                turn.turned += by
                turn.steps++
                // Relative on both: so much further round from wherever the player is looking by now, up and down untouched.
                turn.connection.send(ClientboundPlayerRotationPacket(by.toFloat(), true, 0f, true))
            }
        } catch (problem: Throwable) {
            if (!complained) { complained = true; ItemService.plugin.logger.warning("[car] turning by the keys failed: $problem") }
        }
    }
    @Volatile private var complained = false
}
