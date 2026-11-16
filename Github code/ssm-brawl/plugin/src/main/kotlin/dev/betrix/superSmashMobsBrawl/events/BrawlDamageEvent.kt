package dev.betrix.superSmashMobsBrawl.events

import gg.flyte.twilight.event.TwilightEvent
import org.bukkit.entity.LivingEntity

sealed class BrawlDamageType {
    data object Projectile : BrawlDamageType()

    data object Explosion : BrawlDamageType()

    data object MeleeAttack : BrawlDamageType()

    data object Slam : BrawlDamageType()
}

sealed class Damager {
    data object System : Damager()

    data class DamagerLivingEntity(val livingEntity: LivingEntity) : Damager()
}

class BrawlDamageEvent @JvmOverloads constructor(
    val victim: LivingEntity,
    val damager: Damager?,
    var damage: Double,
    var knockbackMultiplier: Double = 1.0,
    val damageType: BrawlDamageType? = null,
    val sourceName: String? = null,
    val automaticKnockback: Boolean = damageType == null,
    val ignoreArmor: Boolean = false,
    /** Shared victim-side protection for projectile volleys (20 ticks = one second). */
    val hitCooldownTicks: Int = 0,
    /** Attacker-only successful-hit sample, emitted only after an accepted hit. */
    val hitSound: Boolean = false,
    /** Bow attacks use the client's distinct native PvP cue instead of the ability sample. */
    val bowHitSound: Boolean = false,
) : TwilightEvent() {
    /** Set only after the combat manager accepts a hit. Used by reactive passives. */
    var appliedDamage: Double = 0.0
        internal set
}
