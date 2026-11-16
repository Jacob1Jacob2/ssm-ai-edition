package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.utils.NewKitMath
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.potion.*
import org.bukkit.util.Vector
import kotlin.math.*

class GoatedHurrAbility(player: Player) : ClassicKitAbility("goated_hurr",player) {
    override fun activate() { super.activate(); scream() }
    fun scream(multiplier: Double = 1.0, wide: Double = 1.0) {
        val at = player.eyeLocation
        val direction = at.direction.normalize()
        val right = direction.clone().crossProduct(Vector(0.0,1.0,0.0)).let { if(it.lengthSquared()<0.01) Vector(1.0,0.0,0.0) else it.normalize() }
        val up = right.clone().crossProduct(direction).normalize()
        val struck = mutableSetOf<java.util.UUID>()
        player.world.playSound(at,Sound.ENTITY_GOAT_SCREAMING_AMBIENT,1f,1.2f)
        loop { age ->
            if (age >= 20) return@loop false
            val step = direction.clone().multiply(0.7 * itemBoost)
            if (at.world.rayTraceBlocks(at,step,step.length(),FluidCollisionMode.NEVER,true) != null) return@loop false
            at.add(step)
            val distance = (age+1)*0.7 * itemBoost
            val radius = NewKitMath.hurrRadius(distance / itemBoost)*wide * itemBoost
            repeat(16) { i ->
                val angle = i*PI/8
                val point = at.clone().add(right.clone().multiply(cos(angle)*radius)).add(up.clone().multiply(sin(angle)*radius))
                at.world.spawnParticle(Particle.NOTE,point,0,((i + age) % 24) / 24.0,0.0,0.0,1.0)
            }
            targets(at,radius+1.0).forEach { target ->
                if (target.uniqueId !in struck && target.boundingBox.clone().expand(radius).contains(at.toVector()) &&
                    hit(target,NewKitMath.hurrDamage(distance / itemBoost)*multiplier,1.5,at.clone().subtract(direction))) struck.add(target.uniqueId)
            }
            true
        }
    }
}

class GoatedRamAbility(player: Player) : ClassicKitAbility("goated_ram",player) {
    var charging = false
        private set
    private var dive = false
    private var empowered = 1.0
    var buffUntil = 0L
        private set
    val boosted get() = System.currentTimeMillis() < buffUntil
    override fun canActivate(sendMessage:Boolean) = charging || super.canActivate(sendMessage)
    override fun activate() {
        if (charging) { dive(); return }
        super.activate(); launch()
    }
    fun launch(multiplier:Double=1.0) {
        if(charging) { dive(); return }
        empowered=multiplier; charging=true; dive=false
        buffUntil=System.currentTimeMillis()+5000
        player.addPotionEffect(PotionEffect(PotionEffectType.SPEED,100,0,false,false))
        player.velocity=player.location.direction.setY(0.0).multiply(0.3).setY(1.15*sqrt(multiplier)).multiply(itemBoost)
        player.world.playSound(player.location,Sound.ENTITY_GOAT_LONG_JUMP,1f,1f)
        val struck=mutableSetOf<java.util.UUID>()
        loop(finish={charging=false;dive=false}) { age ->
            player.fallDistance=0f
            if(age>5 && player.isOnGround) {
                player.world.playSound(player.location,Sound.ENTITY_GOAT_RAM_IMPACT,0.8f,1f)
                return@loop false
            }
            if(age>=70) return@loop false
            if(dive) {
                player.world.spawnParticle(Particle.CLOUD,player.location,5,0.3,0.3,0.3,0.02)
                targets(player.location,1.8 * itemBoost).forEach { if(it.uniqueId !in struck && hit(it,7.0*empowered,1.6))struck.add(it.uniqueId) }
            }
            true
        }
    }
    fun dive() { if(!charging)return; dive=true; player.velocity=player.eyeLocation.direction.setY(-0.65).normalize().multiply(1.7*sqrt(empowered)*itemBoost) }
}
