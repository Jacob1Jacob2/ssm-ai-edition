package dev.betrix.superSmashMobsBrawl.projectiles

import dev.betrix.superSmashMobsBrawl.abilities.ClassicSummons
import dev.betrix.superSmashMobsBrawl.events.*
import dev.betrix.superSmashMobsBrawl.services.KitService
import dev.betrix.superSmashMobsBrawl.services.MinigameService
import gg.flyte.twilight.event.event
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.entity.*
import org.bukkit.util.Vector
import org.koin.core.component.inject

/** A launched slime remains a living hazard after landing; attacks accelerate its decay. */
class SlimeProjectile(owner: Player, name: String, private val charge: Double,
                      private val damageMultiplier: Double = 1.0) : BrawlProjectile(owner, name) {
    private val games: MinigameService by inject()
    private val kits: KitService by inject()
    private val life = kits.getKitForPlayer(owner)
    private val match = games.getMinigameForPlayer(owner)
    private val lastHit = mutableMapOf<java.util.UUID, Int>()
    private var landed = false
    private var age = 0
    private var decay = 0

    init {
        projectileSize(0.75)
        maxLifetime(480)
        enableBlockDetection(false)
        enableIdleDetection(false)
        onTick {
            val slime = projectileEntity as? Slime ?: return@onTick
            age++; decay++
            if (slime.isOnGround) landed = true
            if (games.getMinigameForPlayer(owner) !== match || kits.getKitForPlayer(owner) !== life ||
                owner.gameMode != GameMode.SURVIVAL || match?.respawnManager?.isRespawning(owner) == true) {
                teardown(); return@onTick
            }
            if (decay >= 120) {
                decay = 0
                slime.world.spawnParticle(Particle.ITEM_SLIME, slime.location.add(0.0,0.3,0.0), 20,0.4,0.3,0.4,0.08)
                slime.world.playSound(slime.location,Sound.ENTITY_SLIME_SQUISH,0.7f,1.3f)
                if (slime.size <= 1) { teardown(); return@onTick }
                slime.size--
            }
            if (slime.isOnGround && age % 16 == 0) {
                val target = slime.world.players.filter(::enemy).minByOrNull { it.location.distanceSquared(slime.location) }
                val direction = target?.location?.toVector()?.subtract(slime.location.toVector())?.setY(0.0) ?: Vector()
                if (direction.lengthSquared() > 0.01) direction.normalize().multiply(0.25)
                slime.velocity = direction.setY(0.42)
                slime.world.spawnParticle(Particle.ITEM_SLIME,slime.location,6,0.25,0.0,0.25,0.03)
            }
        }
    }
    private fun enemy(p: Player) = p != owner && p.gameMode == GameMode.SURVIVAL &&
        games.getMinigameForPlayer(p) === match && match != null &&
        !match.respawnManager.isRespawning(p) && !match.arePlayersOnSameTeam(owner,p)

    override fun createProjectileEntity(): Slime {
        val slime = owner.world.spawn(owner.eyeLocation, Slime::class.java) {
            it.size = charge.toInt().coerceIn(1,3)
            it.setAI(true); Bukkit.getMobGoals().removeAllGoals(it)
            it.isSilent = true; it.isPersistent = false
        }
        ClassicSummons.owners[slime.uniqueId] = owner
        listeners.add(event<SlimeSplitEvent> { if (entity == slime) isCancelled = true })
        listeners.add(event<EntityDamageByEntityEvent> { if (damager == slime) isCancelled = true })
        listeners.add(event<EntityDamageEvent> {
            if (entity != slime) return@event
            isCancelled = true
            val attacker = (this as? EntityDamageByEntityEvent)?.damager as? Player ?: return@event
            if (enemy(attacker)) decay += 50
        })
        listeners.add(event<BrawlDamageEvent> {
            if (victim != slime || isCancelled) return@event
            val attacker = (damager as? Damager.DamagerLivingEntity)?.livingEntity as? Player ?: return@event
            if (enemy(attacker)) { decay += (damage * 12).toInt().coerceAtLeast(20); appliedDamage = damage }
        })
        return slime
    }
    override fun doVelocity() {
        projectileEntity?.velocity = owner.eyeLocation.direction.multiply(1.0 + charge / 2.0).apply { y = maxOf(y,0.2) }
    }
    fun setupHitCallback(): SlimeProjectile {
        onHitEntity { target, _ ->
            val slime = projectileEntity as? Slime ?: return@onHitEntity ProjectileAction.DESTROY
            if (target is Player && enemy(target) && age >= (lastHit[target.uniqueId] ?: 0)) {
                val hit = BrawlDamageEvent(target,Damager.DamagerLivingEntity(owner),
                    (if (landed) slime.size * 2.0 else 3.0 + slime.size * 3.0) * damageMultiplier,
                    (if (landed) 1.0 else 1.5) * upgradeScale, BrawlDamageType.Projectile,
                    sourceName = "Slime Rocket", automaticKnockback = true, hitCooldownTicks = 10, hitSound = true)
                hit.callEvent()
                if (hit.appliedDamage > 0) lastHit[target.uniqueId] = age + if (landed) 10 else 40
            }
            ProjectileAction.CONTINUE
        }
        return this
    }
    override fun teardown() {
        projectileEntity?.let { ClassicSummons.owners.remove(it.uniqueId) }
        super.teardown()
    }
}
