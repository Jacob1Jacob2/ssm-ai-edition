package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.projectiles.BrawlProjectile
import dev.betrix.superSmashMobsBrawl.projectiles.ProjectileAction
import org.bukkit.Sound
import org.bukkit.util.Vector
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import kotlin.random.Random

class NeedlerAbility(player: Player) : ClassicKitAbility("needler", player) {
    private val arrows=mutableSetOf<BrawlProjectile>()
    private var firing=false
    override fun canActivate(sendMessage:Boolean)=!firing && super.canActivate(sendMessage)

    override fun activate() {
        super.activate()
        firing=true
        loop(finish={firing=false}) {age ->
            if(age>=(metadata.int("arrowCount") ?: 6) || (age>1 && !player.isBlocking))return@loop false
            fun spread()=Random.nextDouble(-0.045,0.045)
            val arrow=BrawlProjectile.arrow(player,metadata.double("arrowVelocity") ?: 1.2,"abilities.needler.name")
                .velocityAddend(Vector(spread(),spread(),spread())).maxLifetime(100)
                .onHitEntity {entity,_->
                    if(hit(entity,metadata.double("baseDamage") ?: 1.1,1.0,hitSound=true))
                        entity.addPotionEffect(PotionEffect(PotionEffectType.POISON,40,0,false,true))
                    ProjectileAction.DESTROY}
                .onTeardown {arrows.remove(it)}.upgraded().launch()
            arrows+=arrow
            player.world.playSound(player.eyeLocation,Sound.ENTITY_SPIDER_AMBIENT,0.8f,2f)
            true
        }
    }
    override fun teardown() {arrows.toList().forEach {it.teardown()};arrows.clear();super.teardown()}
}
