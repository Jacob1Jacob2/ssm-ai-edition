package dev.betrix.superSmashMobsBrawl.abilities

import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Pig
import org.bukkit.entity.Player

class BabyBaconBombAbility(player: Player) : ClassicKitAbility("baby_bacon_bomb", player) {
    private var firedAt = 0L
    override fun canActivate(sendMessage: Boolean) = player.exp >= PigEnergy.cost(player,0.35f) && System.currentTimeMillis()-firedAt >= 500 && super.canActivate(sendMessage)
    override fun activate() {
        if (System.currentTimeMillis()-firedAt < 500) return
        firedAt = System.currentTimeMillis()
        val cost=PigEnergy.cost(player,0.35f)
        if(player.exp<cost)return
        player.exp-=cost
        super.activate()
        player.world.playSound(player.location, Sound.ENTITY_PIG_AMBIENT, 2f, 0.75f)
        val pig=player.world.spawn(player.location,Pig::class.java) {it.setBaby();it.variant=Pig.Variant.TEMPERATE;it.isCollidable=false}
        summon(pig,5.0)
        // A bomb dropped from a height must survive the landing, or it dies without going off.
        pig.getAttribute(org.bukkit.attribute.Attribute.FALL_DAMAGE_MULTIPLIER)?.baseValue=0.0
        pig.velocity=org.bukkit.util.Vector(0.0,-0.4,0.0)
        player.velocity=player.eyeLocation.direction.multiply(0.8).setY(0.95).multiply(itemBoost)
        loop(finish={remove(pig)}) {age ->
            if(!pig.isValid)return@loop false
            if(age>=80){detonate(pig);return@loop false}
            val enemy=targets(pig.location,20.0).filterIsInstance<Player>().minByOrNull {it.location.distanceSquared(pig.location)}
            if(enemy!=null) {
                val delta=enemy.location.toVector().subtract(pig.location.toVector())
                if(delta.lengthSquared()<3.0) {
                    detonate(pig)
                    return@loop false
                }
                val move=delta.setY(0.0).normalize().multiply(0.3)
                move.y=if(pig.isOnGround && !pig.location.clone().add(move.clone().multiply(3)).block.isPassable)0.45 else pig.velocity.y
                pig.velocity=move
            }
            true
        }
    }
    private fun detonate(pig:Pig) {
        targets(pig.location,upgradedValue("explosionRadius", 4.0)).forEach {hit(it,metadata.double("damage")?:4.0,0.0,pig.location)}
        pig.world.spawnParticle(Particle.EXPLOSION,pig.location,1)
        pig.world.playSound(pig.location,Sound.ENTITY_GENERIC_EXPLODE,0.6f,2f)
        pig.world.playSound(pig.location,Sound.ENTITY_PIG_DEATH,1f,2f)
    }
}
