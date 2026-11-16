package dev.betrix.superSmashMobsBrawl.passives

import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageType
import dev.betrix.superSmashMobsBrawl.events.Damager
import dev.betrix.superSmashMobsBrawl.projectiles.BrawlProjectile
import dev.betrix.superSmashMobsBrawl.projectiles.ProjectileAction
import gg.flyte.twilight.event.event
import gg.flyte.twilight.scheduler.TwilightRunnable
import gg.flyte.twilight.scheduler.delay
import gg.flyte.twilight.scheduler.repeatingTask
import kotlin.math.min
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Arrow
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.util.Vector

class BarragePassive(player: Player) : BrawlPassive("barrage", player) {

    private val maxCharge = metadata.int("maxCharge") ?: 5
    private val arrowDamage = metadata.double("arrowDamage") ?: 6.0
    private var active = false
    private var charge = 0
    private var chargeRunnable: TwilightRunnable? = null

    private val activeProjectiles = mutableListOf<BrawlProjectile>()

    override fun setup() {
        active = true
        listeners.add(
            event<PlayerInteractEvent> {
                if (dev.betrix.superSmashMobsBrawl.items.ItemService.kind(item) != null) return@event
                if (
                    player != this@BarragePassive.player ||
                        item?.type != Material.BOW || hand != org.bukkit.inventory.EquipmentSlot.HAND ||
                        dev.betrix.superSmashMobsBrawl.smash.SmashService.session(player)?.form()!=null ||
                        item?.itemMeta?.persistentDataContainer?.has(dev.betrix.superSmashMobsBrawl.smash.SmashItems.POWER)==true ||
                        useItemInHand()==org.bukkit.event.Event.Result.DENY ||
                        !(player.inventory.contains(Material.ARROW) ||
                            player.inventory.contains(Material.TIPPED_ARROW) ||
                            player.inventory.contains(Material.SPECTRAL_ARROW)) ||
                        (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
                ) {
                    return@event
                }

                finishFiring()

                // 80% of the former charge rate, without rounding every interval down.
                var chargingTicks = 0
                val newChargeRunnable =
                    repeatingTask(1) {
                        chargingTicks++
                        if(chargingTicks>3 && (!player.isHandRaised || player.inventory.itemInMainHand.type!=Material.BOW ||
                            dev.betrix.superSmashMobsBrawl.smash.SmashService.session(player)?.form()!=null)) {
                            finishFiring();return@repeatingTask
                        }
                        if (charge < maxCharge && chargingTicks >= 25 + kotlin.math.ceil(charge * 7.5).toInt()) {
                            incrementCharge()
                        }
                    }

                chargeRunnable = newChargeRunnable
                runnables.add(newChargeRunnable)
            }
        )

        listeners.add(
            event<PlayerItemHeldEvent> {
                if (player != this@BarragePassive.player) {
                    return@event
                }

                finishFiring()
            }
        )

        listeners.add(
            event<EntityShootBowEvent> {
                if(bow?.itemMeta?.persistentDataContainer?.has(dev.betrix.superSmashMobsBrawl.smash.SmashItems.POWER)==true)return@event
                if (dev.betrix.superSmashMobsBrawl.items.ItemService.kind(bow) != null) return@event
                if (projectile !is Arrow) {
                    return@event
                }

                val arrow = projectile as Arrow
                val source = arrow.shooter

                if (source != player || entity != player) {
                    return@event
                }

                // Replace the native arrow, whose vanilla damage is disabled in the arena.
                isCancelled = true
                arrow.remove()
                player.inventory.contents.firstOrNull { it?.type == Material.ARROW }?.let { it.amount-- }
                onBowFired(force.toDouble())
                finishFiring()
            }
        )

        super.setup()
    }

    override fun teardown() {
        active = false
        finishFiring()
        activeProjectiles.toList().also { activeProjectiles.clear() }.forEach { it.teardown() }
        super.teardown()
    }

    private fun onBowFired(force: Double) {
        repeat(1 + charge) { idx ->
            delay((idx + 1).toLong()) {
                if (!active || !player.isOnline || player.gameMode != org.bukkit.GameMode.SURVIVAL) return@delay

                val spread =
                    Vector(
                        (Math.random() - 0.5) / 10,
                        (Math.random() - 0.5) / 10,
                        (Math.random() - 0.5) / 10,
                    )

                val arrow =
                    BrawlProjectile.arrow(player, (3.0 * force).coerceAtLeast(0.4), "Barrage")
                        .velocityAddend(spread)
                        .onHitEntity { entity, _ ->
                            BrawlDamageEvent(
                                    entity,
                                    Damager.DamagerLivingEntity(player),
                                    arrowDamage * force,
                                    damageType = BrawlDamageType.Projectile,
                                    sourceName = "Barrage", automaticKnockback = true, hitCooldownTicks = 10, hitSound = true, bowHitSound = true,
                                )
                                .callEvent()

                            ProjectileAction.DESTROY
                        }
                        .onTeardown { activeProjectiles.remove(it) }
                        .launch()

                activeProjectiles.add(arrow)

                player.world.playSound(player.eyeLocation, Sound.ENTITY_ARROW_SHOOT, 1f, 1f)
            }
        }
    }

    private fun incrementCharge() {
        charge++
        player.exp = min(0.9999F, charge.toFloat() / maxCharge.toFloat())
        player.playSound(player.eyeLocation, Sound.BLOCK_DISPENSER_DISPENSE, 1f, 1 + 0.1f * charge)
    }

    fun cancelCharge() = finishFiring()
    private fun finishFiring() {
        val wasCharging=chargeRunnable!=null || charge>0
        chargeRunnable?.let {
            runnables.remove(it)
            it.cancel()
        }
        chargeRunnable = null
        if(wasCharging && !dev.betrix.superSmashMobsBrawl.smash.SmashService.ownsXp(player))player.exp = 0f
        charge = 0
    }
}
