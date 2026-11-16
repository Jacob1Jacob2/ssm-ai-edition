package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.extensions.disguise
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.entity.EntityType
import org.bukkit.util.Vector
import kotlin.math.*

/** Movement powers sweep their whole path, including the next velocity step. */
internal fun smashCollision(s:SmashSession,from:Location,step:Vector,radius:Double):Pair<Location,Player?>? {
    val length=step.length()
    if(length<.0001)return null
    val direction=step.clone().normalize()
    val wall=from.world.rayTraceBlocks(from,direction,length,FluidCollisionMode.NEVER,true)
    var position=wall?.hitPosition
    var distance=position?.distance(from.toVector()) ?: Double.POSITIVE_INFINITY
    var victim:Player?=null
    s.enemies().forEach {target ->
        val box=(target.disguise?.boundingBox ?: target.boundingBox).clone().expand(radius)
        val hit=if(box.contains(from.toVector()))from.toVector() else box.rayTrace(from.toVector(),direction,length)?.hitPosition
        if(hit!=null && hit.distance(from.toVector())<distance) {position=hit;distance=hit.distance(from.toVector());victim=target}
    }
    return position?.let {it.toLocation(from.world) to victim}
}

class WolfMagmaSmashes(private val s:SmashSession) {
    private val player get()=s.player
    private val arena get()=s.arena
    private val c get()=s.combat
    private var flashInvisible:Boolean?=null

    fun wolfFly(p:SmashPower) {
        val direction=player.eyeLocation.direction
        player.velocity=direction.clone().multiply(s.moves.base(p,"speed",1.5)*1.5)
        player.world.playSound(player.location,Sound.ENTITY_WOLF_AMBIENT,1f,1.2f)
        arena.effect(object:SmashEffect {
            var age=0
            var previous=player.location.add(0.0,.7,0.0)
            override fun step():Boolean {
                if(!s.alive || age++>=18)return false
                val next=player.location.add(0.0,.7,0.0).add(player.velocity)
                val delta=next.toVector().subtract(previous.toVector())
                val collision=smashCollision(s,previous,delta,.65)
                if(collision!=null) {
                    val (at,target)=collision
                    // Stop short of the expanded target box instead of passing through it.
                    val stop=at.clone().subtract(delta.clone().normalize().multiply(.15)).subtract(0.0,.7,0.0)
                    stop.yaw=player.location.yaw;stop.pitch=player.location.pitch
                    if(stop.block.isPassable && stop.clone().add(0.0,1.0,0.0).block.isPassable)player.teleport(stop)
                    player.velocity=Vector(0.0,.08,0.0)
                    if(target!=null) {
                        s.hit(target,s.moves.base(p,"damage",7.0)*1.2,2.0,source=p.spec.name,origin=stop)
                        at.world.playSound(at,Sound.ENTITY_WOLF_GROWL,1f,1.3f)
                    }
                    return false
                }
                previous=player.location.add(0.0,.7,0.0)
                player.fallDistance=0f
                player.world.spawnParticle(Particle.CRIT,player.location,5,.2,.2,.2,.05)
                return true
            }
            override fun close() {}
        })
    }

    fun throwWolf(p:SmashPower) {
        player.world.playSound(player.location,Sound.ENTITY_WOLF_AMBIENT,1f,1.8f)
        c.mob(p,EntityType.WOLF,player.eyeLocation,seconds=18,damage=s.moves.base(p,"damage",5.0)*1.2,
            launchVelocity=player.eyeLocation.direction.multiply(s.moves.base(p,"speed",1.8)))
    }

    fun gun(p:SmashPower) {
        // Two shots every three ticks: one third of the previous two shots per tick.
        if(arena.tick<p.nextUse)return
        p.nextUse=arena.tick+3
        repeat(minOf(2,p.uses)) {
            val side=Vector(-player.eyeLocation.direction.z,0.0,player.eyeLocation.direction.x).multiply(if(it==0)-.12 else .12)
            c.shot(p,Material.FIRE_CHARGE,s.moves.base(p,"damage",8.0)*1.2/3.0,1.2,2.5,.65,
                explosion=2.5,origin=player.eyeLocation.clone().add(side),hitInterval=0,
                onImpact={arena.destroy(it,1.6,6)},shotSound=Sound.ENTITY_CREEPER_DEATH,shotPitch=1.5f)
            p.uses--
        }
        s.refreshTool(p)
    }

    fun startFlash(p:SmashPower) {
        if(p.active)return
        p.position=player.location.clone()
        val aim=player.eyeLocation.direction.setY(0.0)
        p.direction=if(aim.lengthSquared()>.001)aim.normalize() else Vector(-sin(Math.toRadians(player.location.yaw.toDouble())),0.0,cos(Math.toRadians(player.location.yaw.toDouble())))
        p.active=true
        flashInvisible=player.disguise?.isInvisible() ?: false
        player.disguise?.setInvisible(true)
    }

    fun flash(p:SmashPower) {
        if(!p.active)return
        if(!s.holding(p) || p.fuel<=0) {stopFlash(p,true);return}
        val velocity=(p.direction ?: player.eyeLocation.direction).clone().multiply(s.moves.base(p,"speed",1.1)*1.5)
        if(player.world.rayTraceBlocks(player.location.add(0.0,.4,0.0),velocity.clone().normalize(),velocity.length(),FluidCollisionMode.NEVER,true)!=null) {
            stopFlash(p,true);return
        }
        s.consumeFuel(p)
        player.velocity=velocity;player.fallDistance=0f
        player.world.spawnParticle(Particle.FLAME,player.location.add(0.0,.4,0.0),3,.2,.2,.2,.01)
        player.world.playSound(player.location,Sound.BLOCK_FIRE_EXTINGUISH,.6f,1.2f)
        if(p.fuel<=0)stopFlash(p,true)
    }

    fun stopFlash(p:SmashPower,explode:Boolean) {
        if(!p.active && flashInvisible==null)return
        p.active=false
        flashInvisible?.let {player.disguise?.setInvisible(it)};flashInvisible=null
        if(!explode || !s.alive)return
        val at=player.location
        val distance=p.position?.takeIf {it.world==at.world}?.distance(at) ?: 0.0
        val damage=min(10.0,s.moves.base(p,"baseDamage",4.0)+distance*.25)
        val radius=s.moves.base(p,"radius",3.0)*1.5
        at.world.spawnParticle(Particle.EXPLOSION,at,1)
        at.world.spawnParticle(Particle.FLAME,at,100,.4,.3,.4,.3)
        at.world.playSound(at,Sound.ENTITY_GENERIC_EXPLODE,1f,1.2f)
        s.enemies().filter {it.location.distanceSquared(at)<=radius*radius}.forEach {s.hit(it,damage,2.0,source=p.spec.name,origin=at)}
    }

    fun storm(p:SmashPower) {
        if(p.age%4!=0)return
        val target=s.enemies().filter {it.location.distanceSquared(player.location)<400}.randomOrNull()
        fun meteor(destination:Location) {
            val from=destination.clone().add(kotlin.random.Random.nextDouble(-4.0,4.0),22.0,kotlin.random.Random.nextDouble(-4.0,4.0))
            c.shot(p,Material.FIRE_CHARGE,6.0,1.5,1.7,1.2,explosion=4.0,terrain=2.5,origin=from,direction=destination.toVector().subtract(from.toVector()))
        }
        repeat(if(target==null)3 else 1) {
            val angle=Math.random()*2*PI;val radius=3.0+Math.random()*5
            meteor(player.location.add(cos(angle)*radius,0.0,sin(angle)*radius))
        }
        if(target!=null)repeat(2) {meteor(target.location.add(kotlin.random.Random.nextDouble(-2.0,2.0),0.0,kotlin.random.Random.nextDouble(-2.0,2.0)))}
    }
}
