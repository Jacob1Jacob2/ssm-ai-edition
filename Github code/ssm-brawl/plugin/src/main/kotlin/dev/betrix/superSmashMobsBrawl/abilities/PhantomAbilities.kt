package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.minigames.MinigameState
import dev.betrix.superSmashMobsBrawl.smash.SmashService
import dev.betrix.superSmashMobsBrawl.utils.NewKitMath
import gg.flyte.twilight.event.event
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import dev.betrix.superSmashMobsBrawl.utils.HitFeedback
import org.bukkit.util.Vector
import kotlin.math.*

class RavenousMembraneAbility(player:Player):ClassicKitAbility("ravenous_membrane",player) {
    private data class Chunk(val display:ItemDisplay,val position:Location,val velocity:Vector,var bounces:Int=0,var age:Int=0)
    override fun activate(){super.activate(); volley()}
    fun volley(multiplier:Double=1.0,branches:Int=5) {
        val chunks=mutableListOf<Chunk>()
        val hitAt=mutableMapOf<java.util.UUID,Int>()
        var spawned=0
        fun spawn(at:Location,direction:Vector) {
            if(spawned>=128 || chunks.size>=40)return
            spawned++
            chunks.add(Chunk(item(at,Material.PHANTOM_MEMBRANE),at.clone(),direction.clone().normalize().multiply(0.8 * itemBoost)))
        }
        player.world.playSound(player.eyeLocation,Sound.ENTITY_PHANTOM_FLAP,0.7f,1.3f)
        spawn(player.eyeLocation,player.eyeLocation.direction)
        loop(finish={chunks.forEach {remove(it.display)};chunks.clear()}) {age ->
            if(age>=120 || chunks.isEmpty())return@loop false
            for(chunk in chunks.toList()) {
                chunk.age++
                if(chunk.age>=60){remove(chunk.display);chunks.remove(chunk);continue}
                val obstacle=chunk.position.world.rayTraceBlocks(chunk.position,chunk.velocity,chunk.velocity.length(),FluidCollisionMode.NEVER,true)
                val contact=collision(chunk.position,chunk.velocity,0.3)
                val victim=contact?.second
                if(victim!=null && age-(hitAt[victim.uniqueId]?:-100)>=10) {
                    if(hit(victim,4.0*multiplier,1.2,chunk.position)) {
                        player.world.playSound(contact.first,Sound.ENTITY_PHANTOM_BITE,0.6f,1.4f)
                        player.playSound(player.eyeLocation,Sound.ENTITY_ARROW_HIT_PLAYER,1f,1f)
                        hitAt[victim.uniqueId]=age
                        val at=contact.first
                        remove(chunk.display);chunks.remove(chunk)
                        repeat(branches) {i ->
                            val angle=i*2*PI/branches+age*0.3
                            val velocity=Vector(cos(angle),if(i%2==0)0.3 else -0.15,sin(angle)).normalize()
                            spawn(at.clone().add(velocity.clone().multiply(0.9)),velocity)
                        }
                        at.world.spawnParticle(Particle.END_ROD,at,8,0.2,0.2,0.2,0.03)
                        continue
                    }
                }
                if(obstacle!=null) {
                    if(chunk.bounces++>=2){remove(chunk.display);chunks.remove(chunk);continue}
                    player.world.playSound(chunk.position,Sound.ENTITY_PHANTOM_FLAP,0.25f,1.6f)
                    val normal=obstacle.hitBlockFace?.direction ?: chunk.velocity.clone().normalize().multiply(-1)
                    chunk.velocity.subtract(normal.clone().multiply(2*chunk.velocity.dot(normal)))
                    chunk.position.set(obstacle.hitPosition.x,obstacle.hitPosition.y,obstacle.hitPosition.z).add(normal.multiply(0.1))
                } else chunk.position.add(chunk.velocity)
                chunk.display.teleport(chunk.position)
                chunk.position.world.spawnParticle(Particle.END_ROD,chunk.position,2,.04,.04,.04,0.0)
            }
            true
        }
    }
}

class PhantomFlightAbility(player:Player):ClassicKitAbility("phantom_flight",player) {
    var flying=false
        private set
    private var fuel=0.0
    private var lastGroundY=player.y
    private var clock=0
    private var oldFlySpeed=0.1f
    private val flightSpeed get()=(if(SmashService.has(player,"phantom:3"))1.3 else .52)*itemBoost
    override fun setup() {
        super.setup()
        oldFlySpeed=player.flySpeed
        listeners.add(event<PlayerMoveEvent> {
            if(player!=this@PhantomFlightAbility.player || !flying || !player.isFlying || from.world!=to.world)return@event
            val delta=to.toVector().subtract(from.toVector())
            if(delta.lengthSquared()>flightSpeed*flightSpeed) {
                val capped=from.clone().add(delta.normalize().multiply(flightSpeed))
                capped.yaw=to.yaw;capped.pitch=to.pitch;to=capped
            }
        })
        listeners.add(event<PlayerToggleFlightEvent> {
            if(player==this@PhantomFlightAbility.player && SmashService.has(player,"phantom:3")) {
                isCancelled=true
                maintainNightfall()
            }
        })
        runnables.add(repeatingTask(1) {
            if(game?.getState()!=MinigameState.IN_PROGRESS)return@repeatingTask
            if(SmashService.has(player,"phantom:3")) {maintainNightfall();return@repeatingTask}
            clock++
            if(player.isOnGround && !flying){lastGroundY=player.y;fuel=(fuel+1.2/200).coerceAtMost(1.0)}
            if(flying) {
                if(player.isOnGround && clock>4) {stopFlight()}
                else {
                    player.isSprinting=false
                    if(player.velocity.lengthSquared()>flightSpeed*flightSpeed)player.velocity=player.velocity.normalize().multiply(flightSpeed)
                    val efficient=if(SmashService.has(player,"phantom:1"))0.6 else 1.0
                    fuel=(fuel-NewKitMath.flightDrain(player.y-lastGroundY)*efficient).coerceAtLeast(0.0)
                    val bombCost=if(SmashService.has(player,"phantom:2"))0.064 else 0.08
                    if(player.isSneaking && clock%10==0 && fuel>=bombCost){fuel-=bombCost;bomb()}
                    if(fuel<=0.0)stopFlight()
                }
            }
            if(!SmashService.ownsXp(player))player.exp=fuel.toFloat()
        })
    }
    override fun canActivate(sendMessage:Boolean)= flying || (fuel>=0.05 && super.canActivate(sendMessage))
    override fun activate(){
        if(SmashService.has(player,"phantom:3")){maintainNightfall();return}
        if(flying)stopFlight() else {super.activate();startFlight()}
    }
    fun maintainNightfall(){
        refill()
        if(!flying)startFlight()
        player.allowFlight=true;player.isFlying=true;player.isSprinting=false;player.fallDistance=0f
    }
    fun refill(){fuel=1.0}
    fun startFlight(){
        if(fuel<0.05)return
        flying=true;clock=0;player.flySpeed=((if(SmashService.has(player,"phantom:3")) .11375 else .0455) * itemBoost).toFloat();player.allowFlight=true;player.isFlying=true
        player.velocity=Vector(0.0,0.2 * itemBoost,0.0)
        player.world.playSound(player.location,Sound.ENTITY_PHANTOM_FLAP,1f,1f)
    }
    fun stopFlight(restoreJump:Boolean=true){
        flying=false;player.isFlying=false;player.allowFlight=false;player.flySpeed=oldFlySpeed;player.fallDistance=0f
        if(restoreJump && player.isOnGround)
            (ownerKit?.getPassive("double_jump") as? dev.betrix.superSmashMobsBrawl.passives.DoubleJumpPassive)?.rechargeNow()
    }
    fun bomb(multiplier:Double=1.0) {
        val at=player.location.add(0.0,-0.2,0.0)
        val empowered=SmashService.has(player,"phantom:2") || SmashService.has(player,"phantom:3")
        val bonus=if(SmashService.has(player,"phantom:2"))1.2 else if(empowered)1.02 else 1.0
        val display=item(at,Material.PHANTOM_MEMBRANE)
        if(empowered)display.transformation=display.transformation.apply {scale.mul(bonus.toFloat())}
        val velocity=Vector(0.0,-0.35,0.0)
        loop(finish={remove(display)}) {age ->
            if(age>=80)return@loop false
            velocity.y=(velocity.y-0.05).coerceAtLeast(-1.5)
            val impact=collision(at,velocity,0.35*bonus)
            if(impact!=null) {
                if(empowered)game?.let(SmashService::arena)?.destroy(impact.first,2.6,16) else breakNearbyTerrain(impact.first)
                targets(impact.first,3.5 * itemBoost * bonus).forEach {target ->
                    if(hit(target,5.0*multiplier*bonus,1.7,impact.first,sourceName="Phantom Bomb") && target==impact.second)
                        HitFeedback.play(player)
                }
                at.world.spawnParticle(Particle.REVERSE_PORTAL,impact.first,45,1.0,0.5,1.0,0.1)
                at.world.spawnParticle(Particle.END_ROD,impact.first,20,0.6,0.4,0.6,0.1)
                at.world.playSound(impact.first,if(empowered)Sound.ENTITY_GENERIC_EXPLODE else Sound.ENTITY_PHANTOM_DEATH,0.7f,if(empowered)1f else 1.6f)
                false
            } else {at.add(velocity);display.teleport(at);at.world.spawnParticle(Particle.END_ROD,at,2,0.1,0.1,0.1,0.0);true}
        }
    }
    override fun teardown(){stopFlight(false);super.teardown()}
}
