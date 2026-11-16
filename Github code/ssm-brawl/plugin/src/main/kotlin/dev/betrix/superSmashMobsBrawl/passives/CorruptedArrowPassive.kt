package dev.betrix.superSmashMobsBrawl.passives

import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageType
import gg.flyte.twilight.event.event
import gg.flyte.twilight.scheduler.TwilightRunnable
import gg.flyte.twilight.scheduler.repeatingTask
import kotlin.math.min
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.persistence.PersistentDataType

class CorruptedArrowPassive(player: Player) : BrawlPassive("corrupted_arrow", player) {
    private val maxCharge: Int
        get() = metadata.int("maxCharge") ?: 6

    private val damagePerCharge: Double
        get() = metadata.double("damagePerCharge") ?: 0.9

    private val chargeDelayTicks: Long
        get() = metadata.long("chargeDelayTicks") ?: 20L

    private val chargeRateTicks: Long
        get() = metadata.long("chargeRateTicks") ?: 5L

    private val baseBowDamageCap: Double
        get() = metadata.double("baseBowDamageCap") ?: 5.0

    private var charge = 0
    private var chargeRunnable: TwilightRunnable? = null
    private val firedArrows = mutableListOf<Arrow>()
    private val damageBoostKey = NamespacedKey(plugin, "corrupted_arrow_damage_boost")

    override fun setup() {
        player.exp=0f
        setupBowChargingListener()
        setupItemHeldListener()
        setupBowFireListener()
        setupDamageListener()
        setupParticleTask()
        super.setup()
    }

    private fun setupBowChargingListener() {
        listeners.add(
            event<PlayerInteractEvent> {
                if (dev.betrix.superSmashMobsBrawl.items.ItemService.kind(item) != null) return@event
                if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(this@CorruptedArrowPassive.player,"zombie:0")) return@event
                if (
                    player != this@CorruptedArrowPassive.player ||
                        player.inventory.itemInMainHand.type != Material.BOW ||
                        !(player.inventory.contains(Material.ARROW) ||
                            player.inventory.contains(Material.TIPPED_ARROW) ||
                            player.inventory.contains(Material.SPECTRAL_ARROW)) ||
                        (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
                ) {
                    return@event
                }

                finishCharging()

                val newChargeRunnable =
                    repeatingTask(chargeDelayTicks, chargeRateTicks) {
                        if(!player.isHandRaised || player.inventory.itemInMainHand.type!=Material.BOW) {finishCharging();return@repeatingTask}
                        if (charge < maxCharge) {
                            incrementCharge()
                        }
                    }

                chargeRunnable = newChargeRunnable
                runnables.add(newChargeRunnable)
            }
        )
    }

    private fun setupItemHeldListener() {
        listeners.add(
            event<PlayerItemHeldEvent> {
                if (player != this@CorruptedArrowPassive.player) {
                    return@event
                }

                finishCharging()
            }
        )
    }

    private fun setupBowFireListener() {
        listeners.add(
            event<EntityShootBowEvent> {
                if (dev.betrix.superSmashMobsBrawl.items.ItemService.kind(bow) != null) return@event
                if (projectile !is Arrow) {
                    return@event
                }

                val arrow = projectile as Arrow
                val source = arrow.shooter

                if (source != player || entity != player) {
                    return@event
                }

                onBowFired(arrow)
                finishCharging()
            }
        )
    }

    private fun setupDamageListener() {
        // Listen to vanilla arrow damage and convert to BrawlDamageEvent
        // Note: We process even if cancelled, since CombatManager cancels arrow damage
        listeners.add(
            event<org.bukkit.event.entity.EntityDamageByEntityEvent> {
                val arrow = damager as? Arrow ?: return@event
                val victim = entity as? org.bukkit.entity.LivingEntity ?: return@event

                // Check if arrow was shot by this player
                if (arrow.shooter != player) return@event

                // Cancel vanilla damage and apply our custom damage
                isCancelled = true

                // Check if this arrow has corrupted charge
                val arrowCharge =
                    arrow.persistentDataContainer.get(damageBoostKey, PersistentDataType.INTEGER)
                        ?: 0

                if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"zombie:0"))return@event
                val calculatedDamage=damage.coerceAtMost(baseBowDamageCap)+arrowCharge*damagePerCharge

                BrawlDamageEvent(
                        victim,
                        dev.betrix.superSmashMobsBrawl.events.Damager.DamagerLivingEntity(player),
                        calculatedDamage,
                        damageType = BrawlDamageType.Projectile,
                        sourceName = "Corrupted Arrow", automaticKnockback = true, hitCooldownTicks = 10, hitSound = true, bowHitSound = true,
                    )
                    .callEvent()

                // Remove arrow from tracking
                firedArrows.remove(arrow)
                arrow.remove()
            }
        )
    }

    private fun setupParticleTask() {
        runnables.add(
            repeatingTask(1) {
                if (!player.isOnline || player.isDead) {
                    return@repeatingTask
                }

                // Clean up dead/grounded arrows and show particles
                firedArrows.removeAll { arrow ->
                    if (arrow.isDead || !arrow.isValid || arrow.isOnGround || arrow.ticksLived > 120) {
                        true
                    } else {
                        // Show particle effect on this corrupted arrow
                        Particle.DUST
                            .builder()
                            .location(arrow.location)
                            .count(1)
                            .offset(0.0, 0.0, 0.0)
                            .extra(0.0)
                            .data(Particle.DustOptions(org.bukkit.Color.RED, 1.0f))
                            .receivers(96, true)
                            .spawn()
                        false
                    }
                }
            }
        )
    }

    private fun onBowFired(arrow: Arrow) {
        if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"zombie:0")) return
        if (charge > 0) {
            // Store charge on arrow using persistent data
            arrow.persistentDataContainer.set(damageBoostKey, PersistentDataType.INTEGER, charge)
            firedArrows.add(arrow)
        }
    }

    private fun incrementCharge() {
        charge++
        if (!dev.betrix.superSmashMobsBrawl.smash.SmashService.ownsXp(player)) player.exp = min(0.9999F, charge.toFloat() / maxCharge.toFloat())
        player.playSound(player.eyeLocation, Sound.BLOCK_DISPENSER_DISPENSE, 1f, 1 + 0.1f * charge)
        
        // Show particle indicator when charging
        Particle.DUST
            .builder()
            .location(player.eyeLocation.add(player.location.direction.normalize().multiply(0.5)))
            .count(3)
            .offset(0.1, 0.1, 0.1)
            .extra(0.0)
            .data(Particle.DustOptions(org.bukkit.Color.fromRGB(139, 0, 0), 0.8f))
            .receivers(96, true)
            .spawn()
    }

    private fun finishCharging() {
        chargeRunnable?.let {
            runnables.remove(it)
            it.cancel()
        }
        chargeRunnable = null
        if (!dev.betrix.superSmashMobsBrawl.smash.SmashService.ownsXp(player)) player.exp = 0f
        charge = 0
    }

    override fun teardown() {
        finishCharging()
        firedArrows.clear()
        super.teardown()
    }
}
