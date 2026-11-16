package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.abilities.BoneRushVolley
import dev.betrix.superSmashMobsBrawl.abilities.PigEnergy
import dev.betrix.superSmashMobsBrawl.utils.BaconFlight
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import kotlin.math.*

class FarmSmashes(private val s:SmashSession) {
    private val player get()=s.player
    private val arena get()=s.arena
    private val c get()=s.combat
    fun herd(p:SmashPower) {
        player.world.playSound(player.location,Sound.ENTITY_COW_AMBIENT,2f,.6f)
        val forward=player.eyeLocation.direction.setY(0.0).let {if(it.lengthSquared()<.001)Vector(0,0,1) else it.normalize()}
        val side=Vector(-forward.z,0.0,forward.x)
        repeat(5) {i ->
            val at=player.location.add(forward).add(side.clone().multiply((i-2)*1.5))
            val cow=c.mob(p,EntityType.COW,at,seconds=8,damage=0.0,scale=1.5) ?: return@repeat
            c.loop(160,finish={
                if(cow.isValid)cow.world.spawnParticle(Particle.POOF,cow.location.add(0.0,1.0,0.0),8,.5,.5,.5,0.0)
                cow.remove()
            }) {age ->
                if(cow.isValid) {
                    if(age%10==0) {
                        cow.world.playSound(cow.location,Sound.ENTITY_COW_STEP,1f,1.2f)
                        cow.world.spawnParticle(Particle.POOF,cow.location,3,.3,.1,.3,0.0)
                        s.enemies().filter {it.location.distanceSquared(cow.location)<4.84}.forEach {
                            if(s.hit(it,s.moves.base(p,"damage",5.0)*1.2,1.5,source=p.spec.name,origin=cow.location,hitInterval=12)) {
                                cow.world.spawnParticle(Particle.EXPLOSION,cow.location.add(0.0,1.0,0.0),12,1.0,1.0,1.0,0.0)
                                cow.world.playSound(cow.location,Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR,.75f,.8f)
                                cow.world.playSound(cow.location,Sound.ENTITY_COW_HURT,1.5f,.75f)
                            }
                        }
                    }
                    if(age%30==0)cow.world.playSound(cow.location,Sound.ENTITY_COW_AMBIENT,1f,1f)
                    // Preserve ground motion instead of ending a flying cow at the first tile.
                    val target=s.enemies().minByOrNull {it.location.distanceSquared(cow.location)}
                    val move=target?.location?.toVector()?.subtract(cow.location.toVector())?.setY(0.0) ?: forward.clone()
                    if(move.lengthSquared()>.01) {
                        move.normalize().multiply(.75)
                        move.y=if(cow.isOnGround && !cow.location.add(move.clone().multiply(2)).block.isPassable).5 else cow.velocity.y
                        cow.velocity=move
                    }
                }
            }
        }
    }
    private fun milkHit(at:Location) {
        at.world.spawnParticle(Particle.FIREWORK,at,30,.2,.2,.2,.3)
        at.world.playSound(at,Sound.ENTITY_FISHING_BOBBER_SPLASH,.2f,2f)
    }
    fun milkSpiral(p:SmashPower) {
        val aim=player.eyeLocation.direction
        player.velocity=aim.clone().multiply(1.65);player.fallDistance=0f
        val center=player.location.add(0.0,.8,0.0)
        val side=aim.clone().crossProduct(Vector(0,1,0)).let {if(it.lengthSquared()<.001)Vector(1,0,0) else it.normalize()}
        val up=side.clone().crossProduct(aim).normalize()
        val previous=p.position ?: center
        repeat(4) {i ->
            val along=previous.clone().add(center.toVector().subtract(previous.toVector()).multiply(i/4.0))
            val theta=(p.age+i/4.0)*.65
            val offset=side.clone().multiply(cos(theta)*1.5).add(up.clone().multiply(sin(theta)*1.5))
            for(sign in listOf(-1.0,1.0))center.world.spawnParticle(Particle.FIREWORK,along.clone().add(offset.clone().multiply(sign)),1,0.0,0.0,0.0,0.0)
        }
        p.position=center
        center.world.playSound(center,Sound.ENTITY_FISHING_BOBBER_SPLASH,.2f,.75f)
        s.enemies().filter {it.location.distanceSquared(player.location)<4}.forEach {
            if(s.hit(it,7.5,1.5,source=p.spec.name))milkHit(it.eyeLocation)
        }
    }
    fun milkDome(p:SmashPower) {
        val center=player.location
        if(p.age%10==0)s.enemies().filter {it.location.distanceSquared(center)<100}.forEach {
            val distance=it.location.distance(center)
            if(s.hit(it,3.0+6.0*(1-distance/10).coerceIn(0.0,1.0),.3,source=p.spec.name)) {
                milkHit(it.eyeLocation)
                it.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS,30,2,false,false))
            }
        }
        if(p.age%2!=0)return
        for(radius in listOf(2.5,5.0,7.5,10.0))for(band in 0..3)repeat(12) {i ->
            val elevation=band*PI/7
            val angle=i*2*PI/12+p.age*.08+band*.3
            val at=center.clone().add(cos(angle)*cos(elevation)*radius,.3+sin(elevation)*radius,sin(angle)*cos(elevation)*radius)
            at.world.spawnParticle(Particle.FIREWORK,at,1,0.0,0.0,0.0,0.0)
        }
    }
    fun kick(p:SmashPower) {
        c.shot(p,Material.BONE,s.moves.base(p,"damage",6.0)*1.2,s.moves.base(p,"knockback",2.0)*1.5,2.3,scale=2.0,onHit={
            repeat(2){_->it.world.playSound(it.location,Sound.ENTITY_SKELETON_HURT,4f,.6f)}
            it.world.spawnParticle(Particle.LARGE_SMOKE,it.location.add(0.0,.8,0.0),12,.4,.3,.4,0.0)
        })
    }
    fun trail(p:SmashPower) {
        val item=player.world.dropItem(player.location,ItemStack.of(Material.BONE)) {
            it.pickupDelay=Int.MAX_VALUE;it.setCanPlayerPickup(false);it.setCanMobPickup(false)
        };arena.track(item)
        var exploded=false
        c.loop(400,finish={item.remove()}) {
            if(!exploded && item.isValid && s.enemies().any {it.location.distanceSquared(item.location)<6.25}) {
                exploded=true;c.pulse(item.location,3.0,5.0,1.5,p);item.remove()
            }
        }
    }
    fun rush(p:SmashPower) {
        player.velocity=if(player.isSneaking)Vector() else player.eyeLocation.direction.multiply(s.moves.base(p,"speed",.6)*1.2)
        player.fallDistance=0f
        val bones=BoneRushVolley.emit(player,arena.game,s.moves.base(p,"damagePerBone",.7)*1.75,p.spec.name)
        bones.forEach {it.projectileEntity?.let(arena::track)}
        c.loop(42,finish={bones.forEach {it.teardown()}}){}
    }
    fun rain(p:SmashPower) {
        repeat(8) {i ->
            val near=s.enemies().takeIf {it.isNotEmpty() && i<4}?.random()?.location
            val radius=sqrt(Math.random())*(if(near==null)30 else 5)
            val angle=Math.random()*2*PI
            val at=(near ?: player.location).clone().add(cos(angle)*radius,24.0,sin(angle)*radius)
            c.shot(p,Material.BONE,5.0,1.3,1.8,origin=at,direction=Vector(0.0,-1.0,0.0),explosion=3.0,flightSound=if(i==0)Sound.ENTITY_SKELETON_HURT else null)
        }
    }
    fun bacon(p:SmashPower):Boolean {
        val cost=PigEnergy.cost(player,.225f)
        if(player.exp<cost)return false
        player.exp-=cost
        val carrot=p.spec.tier==SmashTier.LIGHT
        player.world.playSound(player.location,Sound.ENTITY_PIG_AMBIENT,2f,1.5f)
        val flight=BaconFlight(player,if(carrot)Material.CARROT else Material.PORKCHOP,
            if(carrot)Material.GOLDEN_CARROT else Material.COOKED_PORKCHOP,1.5,if(carrot)1.0 else 1.5,
            collision={at,step->smashCollision(s,at,step,.3)},
            hit={target,at ->target is Player && s.hit(target,if(carrot)s.moves.base(p,"damage",6.0)*1.2 else 6.0,1.2,source=p.spec.name,origin=at)},
            eat={cooked ->
                player.exp=(player.exp+.05f).coerceAtMost(1f)
                player.world.playSound(player.location,Sound.ENTITY_GENERIC_EAT,2f,1f)
                if(cooked) {
                    player.health=(player.health+if(carrot)2 else 1).coerceAtMost(player.getAttribute(Attribute.MAX_HEALTH)!!.value)
                    if(carrot)player.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.REGENERATION,80,0))
                    player.world.spawnParticle(Particle.HEART,player.location.add(0.0,.5,0.0),4,.2,.2,.2,0.0)
                }
            },track={arena.track(it)},homing={at->if(carrot)null else s.enemies().filter {it.location.distanceSquared(at)<100}.minByOrNull {it.location.distanceSquared(at)}})
        arena.effect(object:SmashEffect {
            override fun step()=s.alive && flight.step()
            override fun close()=flight.close()
        })
        return true
    }
    fun bomb(p:SmashPower):Boolean {
        val cost=PigEnergy.cost(player,.225f)
        if(player.exp<cost)return false
        player.exp-=cost
        player.world.playSound(player.location,Sound.ENTITY_PIG_AMBIENT,2f,.75f)
        val pig=c.mob(p,EntityType.PIG,player.location,seconds=8,damage=0.0,scale=1.6) ?: return false
        // Dropped from high up, a pig with 10 health died of the fall and vanished without exploding.
        pig.getAttribute(org.bukkit.attribute.Attribute.FALL_DAMAGE_MULTIPLIER)?.baseValue=0.0
        pig.velocity=Vector(0.0,-.4,0.0)
        player.velocity=player.eyeLocation.direction.multiply(.8).setY(.95*1.5)
        var exploded=false
        c.loop(160,finish={pig.remove()}) {age ->
            if(!exploded && pig.isValid && (age>=159 || s.enemies().any {it.location.distanceSquared(pig.location)<5})) {
                exploded=true;c.pulse(pig.location,4.0,s.moves.base(p,"damage",4.0)*1.5,1.5,p,terrain=2.0)
                pig.world.playSound(pig.location,Sound.ENTITY_PIG_DEATH,1f,2f);pig.remove()
            }
        }
        return true
    }
    fun pigmanBegin() {
        player.exp=1f
        SmashLooks.pig(player,player.health<10)
        player.world.playSound(player.location,Sound.ENTITY_ZOMBIFIED_PIGLIN_ANGRY,2f,1f)
        player.world.spawnParticle(Particle.FLAME,player.location.add(0.0,.7,0.0),30,.4,.5,.4,.03)
    }
    fun pigmanTick(p:SmashPower) {
        val brute=player.health<10
        if(dev.betrix.superSmashMobsBrawl.bots.BotManager.debug && p.age%40==0)
            org.bukkit.Bukkit.getLogger().info("[smashdbg] ${player.name} pig:3 health=${"%.1f".format(player.health)} brute=$brute look=${me.libraryaddict.disguise.DisguiseAPI.getDisguise(player)?.type}")
        s.pigmanSpeed(brute)
        if(p.counter!=(if(brute)1 else 0)) {
            p.counter=if(brute)1 else 0
            player.world.playSound(player.location,if(brute)Sound.ENTITY_PIGLIN_BRUTE_ANGRY else Sound.ENTITY_ZOMBIFIED_PIGLIN_ANGRY,2f,1f)
            player.world.spawnParticle(Particle.FLAME,player.location.add(0.0,.7,0.0),30,.4,.5,.4,.03)
        }
    }
}
