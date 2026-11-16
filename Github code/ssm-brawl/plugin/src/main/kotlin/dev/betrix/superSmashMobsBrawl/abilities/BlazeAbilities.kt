package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.events.*
import gg.flyte.twilight.event.event
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import java.util.UUID

/** Classic Inferno throws separate blaze-powder embers, with travel time and gravity. */
class InfernoAbility(player: Player) : ClassicKitAbility("inferno", player) {
    override val countsOwnUses get() = true
    private var firing = false
    private var energy = 0.0
    fun refill() { energy=1.0 }
    override fun setup() {
        super.setup()
        if (borrowed != null) energy = 1.0
        // The speed belongs to the Blaze, not to a borrowed Inferno.
        if (borrowed == null) player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, Int.MAX_VALUE, 0, false, false))
        runnables.add(repeatingTask(1) {
            if (game?.getState() != dev.betrix.superSmashMobsBrawl.minigames.MinigameState.IN_PROGRESS) return@repeatingTask
            val regen = metadata.double("energyRegenPerTick") ?: 0.025
            val empowered = dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"blaze:2") &&
                ((ownerKit?.getAbility("firefly") as? FireflyAbility)?.isFlyingNow() == true ||
                    dev.betrix.superSmashMobsBrawl.smash.SmashService.session(player)?.powers?.get("blaze:1")?.active == true)
            if(empowered) energy = (energy + if(firing) (metadata.double("energyCostPerTick") ?: 0.035)+regen else regen*3).coerceAtMost(1.0)
            else if(!firing) energy = (energy+regen).coerceAtMost(1.0)
            if(dev.betrix.superSmashMobsBrawl.smash.SmashService.session(player)?.powers?.values?.none {it.spec.fuelSeconds>0} != false && borrowed == null) player.exp = energy.toFloat()
        })
    }

    override fun canActivate(sendMessage: Boolean) = !firing && energy >= 0.05 && super.canActivate(sendMessage)
    override fun activate() {
        super.activate()
        firing = true
        loop(finish = { firing = false }) { age ->
            if (age > 2 && (!player.isBlocking || !isCorrectItemForAbility(player.inventory.itemInMainHand))) false
            else if (energy < (metadata.double("energyCostPerTick") ?: 0.035)) false
            else {
                energy = (energy - (metadata.double("energyCostPerTick") ?: 0.035)).coerceAtLeast(0.0)
                if (spendUse()) { launchEmber(); true } else false
            }
        }
    }

    fun launchEmber() {
        var at=player.eyeLocation
        fun spread()=kotlin.random.Random.nextDouble(-0.07,0.07)
        val velocity=at.direction.add(Vector(spread(),spread(),spread())).multiply(upgradedValue("projectileSpeed", 1.8))
        val ember=item(at,Material.BLAZE_POWDER)
        player.world.playSound(at,Sound.ENTITY_GHAST_SHOOT,0.1f,1f)
        loop(finish={remove(ember)}) {age ->
            if(age>=14 || !ember.isValid) false
            else {
                val impact=collision(at,velocity,0.25)
                if(impact!=null) {
                    impact.second?.let {target ->
                        if(target !is Player || kitService.getKitForPlayer(target)?.id !in setOf("blaze","magma_cube","enderman"))
                            if(hit(target,metadata.double("damage") ?: 0.25,ignoreArmor=true)) burn(target,0.5)
                    }
                    false
                } else {
                    at=at.add(velocity);ember.teleport(at);ember.velocity=Vector()
                    velocity.multiply(0.98);velocity.y-=0.04
                    true
                }
            }
        }
    }
}

class FireflyAbility(player: Player) : ClassicKitAbility("firefly", player) {
    companion object {
        fun flightEffects(player:Player) {
            player.world.spawnParticle(Particle.FLAME,player.location.add(0.0,1.0,0.0),40,.6,.6,.6,0.0)
            player.world.playSound(player.location,Sound.ENTITY_GENERIC_EXPLODE,.6f,1.2f)
            player.world.playSound(player.location,Sound.ENTITY_GENERIC_EXPLODE,.5f,1.25f)
        }
    }
    private var charging = false
    private var chargeAge = 0
    private var interrupted = false
    private var flying = false
    fun isFlyingNow() = flying
    override fun setup() {
        super.setup()
        listeners.add(event<BrawlDamageEvent>(priority = EventPriority.MONITOR) {
            if (victim == player && charging && appliedDamage > 0 &&
                damage >= 4.0) interrupted = true
        })
    }

    override fun canActivate(sendMessage: Boolean) = !charging && !flying && super.canActivate(sendMessage)
    override fun activate() {
        super.activate()
        charging = true
        interrupted = false
        val life = ownerKit
        val hitPlayers = mutableSetOf<UUID>()
        loop(finish = { charging = false; flying = false; life?.setInvincible(false) }) { age ->
            chargeAge = age
            val charge = metadata.int("chargeTicks") ?: 30
            when {
                interrupted -> false
                age < charge -> {
                    player.velocity = Vector(0.0, 0.02, 0.0)
                    player.world.spawnParticle(Particle.FIREWORK, player.location.add(0.0, 1.0, 0.0), 10, 0.6, 0.6, 0.6, 0.0)
                    player.world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 0.2f, 0.6f)
                    player.world.playSound(player.location, Sound.ENTITY_BLAZE_AMBIENT, 0.5f, 1f + age.toFloat() / charge.coerceAtLeast(1))
                    true
                }
                age >= charge + (metadata.int("flightTicks") ?: 20) -> false
                else -> {
                    charging = false
                    flying = true
                    life?.setInvincible(true)
                    val velocity = player.eyeLocation.direction.multiply(upgradedValue("speed", 0.7)).add(Vector(0.0,0.15,0.0))
                    player.velocity = velocity
                    player.fallDistance = 0f
                    flightEffects(player)
                    targets(player.location, 2.2 * itemBoost).forEach { target ->
                        if (hitPlayers.add(target.uniqueId) && hit(target, metadata.double("damage") ?: 7.0, 2.0)) burn(target, 3)
                    }
                    player.world.rayTraceBlocks(player.eyeLocation, velocity.clone().normalize(), 1.0) == null
                }
            }
        }
    }
}
