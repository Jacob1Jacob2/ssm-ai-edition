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
 * [starveTicks]. The only way to fill it is to hurt somebody: every 2 damage dealt to another
 * player gives half a bar back (a Chicken Missile's 8 damage is two bars).
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
        runnables.add(repeatingTask(1) { tick() })
        super.setup()
    }

    private fun reset() {
        player.feed()
        ticks = 0.0
        drainDue = drainTicks
        starveDue = 0.0
    }

    private fun tick() {
        if (player.gameMode != GameMode.SURVIVAL) return
        if (minigameService.getMinigameForPlayer(player)?.getState() != MinigameState.IN_PROGRESS) return
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
        player.feed()
        super.teardown()
    }
}
