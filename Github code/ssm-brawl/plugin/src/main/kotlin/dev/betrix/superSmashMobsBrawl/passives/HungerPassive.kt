package dev.betrix.superSmashMobsBrawl.passives

import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.BrawlDeathEvent
import dev.betrix.superSmashMobsBrawl.events.Damager
import dev.betrix.superSmashMobsBrawl.minigames.MinigameState
import dev.betrix.superSmashMobsBrawl.services.LangService
import dev.betrix.superSmashMobsBrawl.utils.HungerRules
import gg.flyte.twilight.event.event
import gg.flyte.twilight.extension.feed
import gg.flyte.twilight.scheduler.repeatingTask
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Classic Super Smash Mobs hunger: the thing that makes hiding a losing plan.
 *
 * While a match is on, half a hunger bar goes every [drainTicks] (6.25 seconds), so a full bar
 * lasts about two minutes. On an empty bar the fighter starves: [starveDamage] every
 * [starveTicks] (a second). The only way to fill it is to hurt somebody: every 2 damage dealt to
 * another player gives half a bar back (a Chicken Missile's 8 damage is two bars).
 *
 * Minecraft's own hunger (running wears it down, a full bar heals) is switched off for these
 * fighters: how fast a kit heals is the kit's regeneration, not how recently it hit someone.
 */
class HungerPassive(player: Player) : BrawlPassive("hunger", player), KoinComponent {
    private val lang: LangService by inject()
    private val drainTicks = ((metadata.double("drainSeconds") ?: HungerRules.DRAIN_SECONDS) * 20).coerceAtLeast(1.0)
    private val starveTicks = ((metadata.double("starveSeconds") ?: HungerRules.STARVE_SECONDS) * 20).coerceAtLeast(1.0)
    private val starveDamage = metadata.double("starveDamage") ?: 1.0
    private val damagePerPoint = metadata.double("damagePerHunger") ?: HungerRules.DAMAGE_PER_POINT
    private val restoreDelayMs = metadata.long("hungerRestoreDelayMs") ?: 250L

    private var ticks = 0.0
    private var drainDue = drainTicks
    private var starveDue = 0.0
    private var lastRestoreMs = 0L
    private var lastWarnMs = 0L
    /** True while the client is being shown more hunger than there is (see [keepSprint]). */
    private var masked = false
    private val human = !dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(player)

    override fun setup() {
        // After the combat manager has decided the hit counts: only damage that landed feeds.
        listeners.add(
            event<BrawlDamageEvent>(priority = EventPriority.MONITOR) {
                if (isCancelled || appliedDamage <= 0.0) return@event
                if ((damager as? Damager.DamagerLivingEntity)?.livingEntity != this@HungerPassive.player) return@event
                if (victim !is Player || victim == this@HungerPassive.player) return@event
                restore(damage)
            }
        )
        // A new life starts on a full bar.
        listeners.add(BrawlDeathEvent.listen { if (player == this@HungerPassive.player) reset() })
        listeners.add(event<PlayerDeathEvent> { if (player == this@HungerPassive.player) reset() })
        // Minecraft heals a well-fed player on its own. Here that is the kit's job.
        listeners.add(
            event<EntityRegainHealthEvent> {
                if (entity == this@HungerPassive.player && regainReason == EntityRegainHealthEvent.RegainReason.SATIATED) isCancelled = true
            }
        )
        // Before the double jump itself is handled (and the jump is taken out of the player's
        // hand): see [keepSprint].
        listeners.add(
            event<org.bukkit.event.player.PlayerToggleFlightEvent>(priority = EventPriority.LOWEST) {
                if (player == this@HungerPassive.player && human && player.gameMode == GameMode.SURVIVAL &&
                    player.foodLevel <= HungerRules.SPRINT_FLOOR) mask()
            }
        )
        // And as the last thing in every tick, after the server has sent the player whatever
        // really changed (a point of health back, say): the last word the client hears is this.
        listeners.add(
            event<com.destroystokyo.paper.event.server.ServerTickEndEvent> {
                if (human && this@HungerPassive.player.gameMode == GameMode.SURVIVAL && live()) keepSprint()
            }
        )
        runnables.add(repeatingTask(1) { tick() })
        super.setup()
    }

    private fun live() = minigameService.getMinigameForPlayer(player)?.getState() == MinigameState.IN_PROGRESS

    private fun mask() {
        player.sendHealthUpdate(player.health, HungerRules.SPRINT_FLOOR + 1, 0f)
        masked = true
    }

    /**
     * Being hungry is not meant to stop anybody running. The game client ends a sprint at three
     * hunger bars or fewer unless the player "may fly", and here that permission is the double
     * jump: in hand on the ground, gone the moment it is used. So below three bars every double
     * jump ended the sprint in mid-air. While the jump is out of the player's hand their client
     * is told there are three and a half bars, which is enough to keep running; the real number
     * does not change and is back on the screen as soon as the jump is.
     */
    private fun keepSprint() {
        if (player.foodLevel <= HungerRules.SPRINT_FLOOR && !player.allowFlight) mask()
        else if (masked) {
            masked = false
            player.sendHealthUpdate()
        }
    }

    private fun reset() {
        player.feed()
        ticks = 0.0
        drainDue = drainTicks
        starveDue = 0.0
    }

    private fun tick() {
        if (player.gameMode != GameMode.SURVIVAL) return
        if (!live()) return
        // Running and jumping cost nothing: the bar only moves by the clock and by hits.
        player.saturation = 0f
        player.exhaustion = 0f
        ticks += 1.0
        if (player.foodLevel <= 0) {
            if (ticks < starveDue) return
            starveDue = ticks + starveTicks
            val now = System.currentTimeMillis()
            if (now - lastWarnMs >= 3000) {
                lastWarnMs = now
                player.sendMessage(lang.t("messages.passives.hunger.attackToRestore"))
            }
            BrawlDamageEvent(victim = player, damager = Damager.System, damage = starveDamage, knockbackMultiplier = 0.0,
                sourceName = "Starvation", ignoreArmor = true).callEvent()
            return
        }
        if (ticks >= drainDue) {
            drainDue = ticks + drainTicks
            player.foodLevel = (player.foodLevel - 1).coerceAtLeast(0)
            // Empty: the first pang comes a moment later, not on the same tick.
            if (player.foodLevel <= 0) starveDue = ticks + starveTicks
        }
    }

    private fun restore(damage: Double) {
        val now = System.currentTimeMillis()
        if (now - lastRestoreMs < restoreDelayMs) return
        lastRestoreMs = now
        player.foodLevel = (player.foodLevel + HungerRules.restored(damage, damagePerPoint)).coerceAtMost(20)
    }

    override fun teardown() {
        masked = false
        player.feed()
        super.teardown()
    }
}
