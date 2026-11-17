package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.events.*
import dev.betrix.superSmashMobsBrawl.extensions.disguise
import dev.betrix.superSmashMobsBrawl.extensions.doKnockback
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import java.util.UUID

/** Shared lifetime, swept collision and combat routing for the restored classic kits. */
abstract class ClassicKitAbility(id: String, player: Player) : BrawlAbility(id, player) {
    protected val ownedEntities = mutableSetOf<Entity>()
    private val burns = mutableMapOf<Player, Long>()
    protected val ownerKit get() = kitService.getKitForPlayer(player)
    protected val game get() = minigameService.getMinigameForPlayer(player)

    protected fun targetable(target: LivingEntity, attacker: Player = player, self: Boolean = false): Boolean {
        val match = minigameService.getMinigameForPlayer(attacker) ?: return false
        if (!target.isValid || target.isDead || target.world != attacker.world) return false
        if (target is Player) return (self || target != attacker) &&
            target.gameMode == GameMode.SURVIVAL && minigameService.getMinigameForPlayer(target) === match &&
            !match.respawnManager.isRespawning(target) &&
            (target == attacker || !match.arePlayersOnSameTeam(attacker, target))
        val summonOwner = ClassicSummons.owners[target.uniqueId] ?: return false
        return summonOwner != attacker && minigameService.getMinigameForPlayer(summonOwner) === match &&
            !match.arePlayersOnSameTeam(attacker, summonOwner)
    }

    protected fun targets(at: Location, radius: Double, self: Boolean = false, attacker: Player = player) =
        at.world.getNearbyLivingEntities(at, radius).filter { targetable(it, attacker, self) }

    protected fun hit(target: LivingEntity, damage: Double, knockback: Double = 0.0,
                      origin: Location = player.location, attacker: Player = player,
                      ignoreArmor: Boolean = false, self: Boolean = false, hitSound: Boolean = false, sourceName: String = displayName): Boolean {
        if (!targetable(target, attacker, self)) return false
        val health = target.health
        val event = BrawlDamageEvent(target, Damager.DamagerLivingEntity(attacker), damage,
            damageType = BrawlDamageType.Projectile, sourceName = sourceName, ignoreArmor = ignoreArmor, hitSound = hitSound)
        event.callEvent()
        if (event.appliedDamage <= 0) return false
        if (knockback > 0 && target.isValid && targetable(target, attacker, self)) {
            target.doKnockback(knockback * itemBoost * (kitService.getKitForPlayer(attacker)?.getOutgoingKnockbackMultiplier() ?: 1.0),
                damage, health, origin.toVector(), null)
        }
        return true
    }

    /** Each cast belongs to one life: changing kit, respawning or leaving cancels its effects. */
    protected fun loop(finish: () -> Unit = {}, tick: (Int) -> Boolean) {
        val life = ownerKit
        val match = game
        var age = 0
        runnables.add(repeatingTask(1) {
            if (!player.isOnline || player.gameMode != GameMode.SURVIVAL || ownerKit !== life ||
                game !== match || match == null || match.respawnManager.isRespawning(player) || !tick(age++)) {
                finish()
                cancel()
            }
        })
    }

    protected fun item(at: Location, material: Material): ItemDisplay = at.world.spawn(at, ItemDisplay::class.java).apply {
        setItemStack(ItemStack.of(material))
        itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
        transformation = transformation.also { it.scale.mul(itemBoost.toFloat()) }
        teleportDuration = 1
        interpolationDuration = 1
        setGravity(false)
        isPersistent = false
        ownedEntities.add(this)
    }

    protected fun remove(entity: Entity?) {
        entity ?: return
        ClassicSummons.owners.remove(entity.uniqueId)
        ownedEntities.remove(entity)
        entity.remove()
    }

    /** Health over a summon's head, so it is plain that it can be killed and how close it is. */
    private fun healthTag(entity: LivingEntity) {
        entity.customName(net.kyori.adventure.text.Component.text("${player.name} ", net.kyori.adventure.text.format.NamedTextColor.YELLOW)
            .append(net.kyori.adventure.text.Component.text("❤ ${kotlin.math.ceil(entity.health).toInt()}", net.kyori.adventure.text.format.NamedTextColor.RED)))
        entity.isCustomNameVisible = true
    }

    protected fun summon(entity: LivingEntity, health: Double, tagged: Boolean = false) {
        entity.setAI(true)
        if (entity is Mob) Bukkit.getMobGoals().removeAllGoals(entity)
        entity.isPersistent = false
        entity.isSilent = true
        entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.baseValue = health
        entity.health = health
        if (tagged) healthTag(entity)
        ownedEntities.add(entity)
        ClassicSummons.owners[entity.uniqueId] = player
        var lastMelee = 0L
        listeners.add(gg.flyte.twilight.event.event<BrawlDamageEvent> {
            if (victim != entity || isCancelled || !entity.isValid) return@event
            val attacker = (damager as? Damager.DamagerLivingEntity)?.livingEntity as? Player ?: return@event
            if (!targetable(entity, attacker)) return@event
            val now = System.currentTimeMillis()
            if (damageType == null && now - lastMelee < 500) return@event
            if (damageType == null) lastMelee = now
            appliedDamage = damage.coerceAtLeast(0.0)
            // Summons are silent and take no vanilla damage, so every accepted hit has to show itself.
            val at = entity.location.add(0.0, entity.height / 2, 0.0)
            val hurt = entity.hurtSound ?: Sound.ENTITY_GENERIC_HURT
            if (damage >= entity.health) {
                entity.world.playSound(at, entity.deathSound ?: hurt, SoundCategory.PLAYERS, 1f, 1f)
                entity.world.spawnParticle(Particle.POOF, at, 12, .25, .25, .25, .03)
                remove(entity)
            } else {
                entity.health -= appliedDamage
                if (tagged) healthTag(entity)
                entity.playHurtAnimation(0f)
                entity.world.playSound(at, hurt, SoundCategory.PLAYERS, 1f, 1f)
                entity.world.spawnParticle(Particle.DAMAGE_INDICATOR, at, 3, .2, .2, .2, .05)
                if (tagged && damageType == null) {
                    val away = entity.location.toVector().subtract(attacker.location.toVector()).setY(0.0)
                    if (away.lengthSquared() > .0001) entity.velocity = away.normalize().multiply(.45).setY(.3)
                }
            }
        })
        listeners.add(gg.flyte.twilight.event.event<org.bukkit.event.entity.EntityDamageEvent>(
            priority = org.bukkit.event.EventPriority.HIGHEST) {
            if (this.entity != entity) return@event
            isCancelled = true
            val attacker = (this as? org.bukkit.event.entity.EntityDamageByEntityEvent)?.damager as? Player ?: return@event
            if (cause != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK) return@event
            BrawlDamageEvent(entity, Damager.DamagerLivingEntity(attacker),
                kitService.getKitForPlayer(attacker)?.getMeleeDamage() ?: 1.0).callEvent()
        })
    }

    /** Returns the first obstacle along the entire movement segment, including mob disguise boxes. */
    protected fun collision(from: Location, step: Vector, radius: Double, attacker: Player = player): Pair<Location, LivingEntity?>? {
        val length = step.length()
        if (length < 0.0001) return null
        val direction = step.clone().normalize()
        val block = from.world.rayTraceBlocks(from, direction, length, FluidCollisionMode.NEVER, true)
        var distance = block?.hitPosition?.distance(from.toVector()) ?: Double.POSITIVE_INFINITY
        var position = block?.hitPosition?.toLocation(from.world)
        var victim: LivingEntity? = null
        targets(from, length + radius + 3.0, attacker = attacker).forEach { target ->
            val box = ((target as? Player)?.disguise?.boundingBox ?: target.boundingBox).clone().expand(radius * itemBoost)
            val intersection = if (box.contains(from.toVector())) from.toVector()
                else box.rayTrace(from.toVector(), direction, length)?.hitPosition
            if (intersection != null && intersection.distance(from.toVector()) < distance) {
                distance = intersection.distance(from.toVector())
                position = intersection.toLocation(from.world)
                victim = target
            }
        }
        return position?.let { it to victim }
    }

    protected fun burn(target: LivingEntity, seconds: Int, attacker: Player = player) {
        burn(target,seconds.toDouble(),attacker)
    }

    protected fun burn(target: LivingEntity, seconds: Double, attacker: Player = player) {
        if (target !is Player || kitService.getKitForPlayer(target)?.id in setOf("blaze", "magma_cube", "enderman")) return
        val life = kitService.getKitForPlayer(target)
        target.fireTicks = maxOf(target.fireTicks, (seconds * 20).toInt())
        ClassicBurns.owners[target.uniqueId] = this
        val alreadyBurning = burns.put(target, System.currentTimeMillis() + (seconds * 1000).toLong()) != null
        if (alreadyBurning) return
        loop(finish = {
            burns.remove(target)
            if (ClassicBurns.owners[target.uniqueId] === this) ClassicBurns.owners.remove(target.uniqueId)
        }) {
            if (kitService.getKitForPlayer(target) !== life || !targetable(target, attacker) ||
                ClassicBurns.owners[target.uniqueId] !== this ||
                System.currentTimeMillis() >= (burns[target] ?: 0)) false
            else {
                if (it > 0 && it % 20 == 0 && target.fireTicks > 0)
                    hit(target, 1.0, attacker = attacker, ignoreArmor = true)
                true
            }
        }
    }

    override fun teardown() {
        super.teardown()
        ownedEntities.toList().forEach(::remove)
        burns.keys.forEach {
            if (ClassicBurns.owners[it.uniqueId] === this) {
                it.fireTicks = 0
                ClassicBurns.owners.remove(it.uniqueId)
            }
        }
        burns.clear()
    }
}

/** Only registered combat summons can be targeted by the restored skills. */
object ClassicSummons {
    val owners = mutableMapOf<UUID, Player>()
}

/** One burning status per victim, even when two fire abilities overlap. */
private object ClassicBurns {
    val owners = mutableMapOf<UUID, ClassicKitAbility>()
}
