package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageType
import dev.betrix.superSmashMobsBrawl.events.Damager
import dev.betrix.superSmashMobsBrawl.projectiles.BrawlProjectile
import dev.betrix.superSmashMobsBrawl.projectiles.ProjectileAction
import gg.flyte.twilight.scheduler.TwilightRunnable
import org.bukkit.Sound
import org.bukkit.entity.Egg
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.util.Vector

class EggBlasterAbility(player: Player) : BrawlAbility("egg_blaster", player) {

    private val durationMs = metadata.long("durationMs") ?: 750L
    private val eggDamage = metadata.double("eggDamage") ?: 1.0

    private var eggTask: TwilightRunnable? = null
    private var startTime: Long = 0
    private val activeProjectiles = mutableListOf<BrawlProjectile>()

    override fun onPlayerInteract(event: PlayerInteractEvent) {
        if (eggTask != null) {
            return
        }

        super.onPlayerInteract(event)
    }

    override fun activate() {
        super.activate()

        startTime = System.currentTimeMillis()

        eggTask =
            repeatingTask(0) {
                if (!player.isBlocking) {
                    stopFiring()
                    return@repeatingTask
                }

                if (System.currentTimeMillis() - startTime >= durationMs) {
                    stopFiring()
                    return@repeatingTask
                }

                shootEgg()
            }
    }

    override fun teardown() {
        stopFiring()

        while (activeProjectiles.isNotEmpty()) {
            val projectile = activeProjectiles.removeAt(activeProjectiles.lastIndex)
            projectile.teardown()
        }

        super.teardown()
    }

    fun shootEgg(onHit:((LivingEntity)->Unit)?=null,smash:Boolean=false) {
        val spawnLocation = player.eyeLocation.add(player.eyeLocation.direction.multiply(0.3))
        val eggVelocity = player.eyeLocation.direction.multiply(1.8)

        val eggProjectile =
            BrawlProjectile.custom(player, "abilities.egg_blaster.name") {
                    player.world.spawn(spawnLocation, Egg::class.java).apply {
                        shooter = player
                        if(smash)item=org.bukkit.inventory.ItemStack.of(org.bukkit.Material.CHICKEN_SPAWN_EGG)
                        velocity = eggVelocity
                    }
                }
                .setInitialVelocity { it.velocity = eggVelocity }
                .onHitEntity { entity, projectile ->
                    handleEggHit(entity)
                    onHit?.invoke(entity)
                    ProjectileAction.DESTROY
                }
                .onTeardown { projectile ->
                    projectile.projectileEntity?.let { it.world.spawnParticle(org.bukkit.Particle.ITEM,it.location,12,.15,.15,.15,.08,org.bukkit.inventory.ItemStack.of(if(smash)org.bukkit.Material.CHICKEN_SPAWN_EGG else org.bukkit.Material.EGG)) }
                    activeProjectiles.remove(projectile)
                }
                .let {if(smash)it.abilityUpgrade(1.5) {action->action()} else it.upgraded()}.launch().also {
                    // Enlargement changes appearance and collision, not projectile speed.
                    if(smash)it.projectileEntity?.velocity=eggVelocity
                }

        activeProjectiles.add(eggProjectile)

        player.world.playSound(player.location, Sound.ENTITY_CHICKEN_EGG, 0.5f, 1f)
    }

    private fun handleEggHit(entity: LivingEntity) {
        val damageEvent =
            BrawlDamageEvent(
                entity,
                Damager.DamagerLivingEntity(player),
                eggDamage,
                knockbackMultiplier = 0.0,
                damageType = BrawlDamageType.Projectile, sourceName = displayName)

        entity.noDamageTicks=0
        damageEvent.callEvent()

        // Set victim velocity to zero
        entity.velocity = Vector(0.0, 0.0, 0.0)

        // Bypass invulnerability frames
        entity.noDamageTicks = 0
    }

    private fun stopFiring() {
        eggTask?.cancel()
        eggTask = null
    }
}
