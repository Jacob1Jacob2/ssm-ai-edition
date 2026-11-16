package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.abilities.*
import dev.betrix.superSmashMobsBrawl.services.KitService
import dev.betrix.superSmashMobsBrawl.utils.ClassicEffects
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.util.Vector
import kotlin.math.*

/** Upgrades share the base kit's actual projectile and movement implementations. */
class ElementalSmashes(private val s:SmashSession) {
    private val player get()=s.player
    private val c get()=s.combat
    private val arena get()=s.arena
    private fun ability(id:String)=KitService.getKitForPlayer(player)?.getAbility(id)
    fun inferno(p:SmashPower) {
        if(!p.active)return
        if(!s.holding(p) || p.uses<=0){p.active=false;return}
        repeat(3){(ability("inferno") as? InfernoAbility)?.launchEmber()}
        p.uses--;s.refreshTool(p)
    }
    fun egg(onHit:((LivingEntity)->Unit)?=null)=(ability("egg_blaster") as? EggBlasterAbility)?.shootEgg(onHit,smash=onHit!=null)
    fun releaseSplash()=(ability("water_splash") as? WaterSplashAbility)?.releaseSplash()
    fun end(p:SmashPower) {
        if(p.spec.key=="guardian:0")(ability("target_laser") as? TargetLaserAbility)?.endSmash()
        if(p.spec.key=="guardian:2")releaseSplash()
    }
    fun begin(p:SmashPower) {
        if(p.spec.key in setOf("villager:1","villager:3"))
            ClassicEffects.fireworkBurst(player.location.add(0.0,1.0,0.0),if(p.spec.key=="villager:1")Color.LIME else Color.FUCHSIA)
        if(p.spec.key=="villager:3")(ability("villager_arts") as? VillagerArtsAbility)?.perfection()
    }
    fun click(p:SmashPower):Boolean? = when(p.spec.key) {
        "blaze:0" -> {p.active=true;false}
        "chicken:2" -> {
            if(!p.active) {
                p.active=true;p.uses--;s.refreshTool(p);p.nextUse=arena.tick+s.moves.cooldown(p)
                c.loop(20,finish={p.active=false}) {
                    egg {target -> if(target is Player && target in s.enemies())
                        c.mob(p,EntityType.CHICKEN,target.location.clone().add(0.0,.2,0.0),seconds=10,baby=true,damage=1.5,target=target)}
                }
            };false
        }
        "chicken:3" -> {
            p.nextUse=arena.tick+6
            missile(p,true);false
        }
        "guardian:0" -> (ability("target_laser") as? TargetLaserAbility)?.startSmash()==true
        "guardian:1" -> {
            repeat(9){(ability("whirlpool_axe") as? WhirlpoolAxeAbility)?.launchAxe(c.spread(player.eyeLocation.direction,.15),1.2)};true
        }
        "guardian:2" -> {if((ability("water_splash") as? WaterSplashAbility)?.launchSplash(relaunch=true)==true)p.active=true;false}
        "sheep:0" -> {(ability("wooly_rocket") as? WoolyRocketAbility)?.launchSmash();true}
        "sheep:1" -> {
            laserSound(player.location)
            c.shot(p,Material.AIR,8.4,3.6,1.5,homing=24.0,shotSound=Sound.ENTITY_ZOMBIE_VILLAGER_CURE,shotPitch=.75f,
                onTravel={at,velocity -> beam(at.clone().subtract(velocity),at,.04)})
            true
        }
        "sheep:2" -> {
            c.shot(p,Material.WHITE_WOOL,12.0,2.0,1.6,explosion=9.0,gravity=.04,
                onImpact={arena.destroy(it,2.0,10)},shotSound=Sound.ENTITY_SHEEP_AMBIENT);true
        }
        "villager:0" -> {(ability("sonic_hurr") as? SonicHurrAbility)?.sonic(1.2,2.0);true}
        else -> null
    }
    fun missile(p:SmashPower,adult:Boolean=false) {
        var rewarded=false
        fun acceptedHit() {
            if(rewarded)return
            rewarded=true
            dev.betrix.superSmashMobsBrawl.utils.HitFeedback.play(player)
            if(!adult && s.has(p.spec.key) && !p.expired(arena.tick))missile(p)
        }
        c.shot(p,Material.CHICKEN_SPAWN_EGG,if(adult)8.4 else 8.0,1.5,if(adult)2.2 else 1.6,if(adult)1.6 else 1.1,
            explosion=if(adult)4.0 else 3.0,terrain=if(adult)2.5 else 0.0,
            shotSound=Sound.ENTITY_CHICKEN_HURT,shotPitch=if(adult)1f else 1.5f,
            flightSound=Sound.ENTITY_CHICKEN_HURT,flightPitch=if(adult)1f else 1.5f,flightVolume=.3f,flightSoundInterval=1,
            onHit={acceptedHit()},onSummonHit={acceptedHit()},
            onFinish={hit -> if(adult && !hit){p.uses=(p.uses-1).coerceAtLeast(0);s.refreshTool(p)}})
    }
    private fun laserSound(at:Location) {
        at.world.playSound(at,Sound.ENTITY_ZOMBIE_VILLAGER_CURE,1.5f,.75f)
        at.world.playSound(at,Sound.ENTITY_SHEEP_AMBIENT,2f,1.5f)
    }
    private fun beam(from:Location,to:Location,width:Double) {
        val delta=to.toVector().subtract(from.toVector());val steps=(delta.length()*5).toInt().coerceAtLeast(1)
        for(i in 0..steps)from.world.spawnParticle(Particle.FIREWORK,from.clone().add(delta.clone().multiply(i.toDouble()/steps)),1,width,width,width,0.0)
    }
    fun rise(p:SmashPower) {
        val center=player.location.clone()
        val elder=player.world.spawn(center.clone().add(0.0,5.0,0.0),ElderGuardian::class.java) {
            it.setAI(false);it.setGravity(false);it.isInvulnerable=true;it.isPersistent=false
        }
        val guards=List(4){player.world.spawn(elder.location,Guardian::class.java) {
            it.setAI(false);it.setGravity(false);it.isInvulnerable=true;it.isPersistent=false
        }}
        (guards+elder).forEach {arena.track(it);SmashVisuals.ids+=it.uniqueId}
        val floor=buildList {
            for(x in -10..10)for(z in -10..10)if(x*x+z*z<=100) {
                val top=center.clone().add(x.toDouble(),0.0,z.toDouble()).block
                (1..5).map{top.getRelative(0,-it,0)}.firstOrNull{it.type.isSolid}?.let{add(it)}
            }
        }.shuffled()
        c.loop(200,finish={
            if(s.alive)c.pulse(center,7.0,1000.0,3.0,p)
            (guards+elder).forEach {SmashVisuals.ids.remove(it.uniqueId);it.remove()}
        }) {age ->
            if(age%2==0)floor.drop(age/2*8).take(8).forEachIndexed {i,block ->
                arena.temporaryBlock(block,listOf(Material.PRISMARINE,Material.PRISMARINE_BRICKS,Material.DARK_PRISMARINE,Material.SEA_LANTERN)[(age/2+i)%4],200)
            }
            val targets=s.enemies().filter{it.location.distanceSquared(center)<625}
            val target=targets.minByOrNull{it.location.distanceSquared(center)}
            guards.forEachIndexed {i,g ->
                val angle=i*PI/2+age*.04+age*age*.00065
                g.teleport(elder.location.add(cos(angle)*3.0,sin(age*.05)*.4,sin(angle)*3.0))
                if(target!=null) {
                    g.target=target;g.setLaser(true)
                    val facing=target.eyeLocation.toVector().subtract(g.eyeLocation.toVector())
                    val look=g.location.setDirection(facing);g.setRotation(look.yaw,look.pitch)
                    if(age%2==0)beam(g.eyeLocation,target.eyeLocation,.04)
                }
            }
            if(age%20==0)center.world.playSound(center,Sound.ENTITY_GUARDIAN_ATTACK,1f,.5f+age/200f)
            if(age%10==0 && target!=null) {
                val angle=age*.8
                val from=target.location.add(cos(angle)*4,1.2,sin(angle)*4)
                beam(from,target.eyeLocation,.08);laserSound(from)
                s.hit(target,2.0,.5,source=p.spec.name,origin=from,hitInterval=0)
            }
        }
    }
    fun sheeple(p:SmashPower) {
        s.enemies().forEachIndexed {index,target ->
            val sheep=player.world.spawn(player.location.add(cos(index*2.0),1.0,sin(index*2.0)),Sheep::class.java) {
                it.setBaby();it.color=DyeColor.WHITE;it.setAI(true);it.setGravity(false);it.isPersistent=false
                it.customName(net.kyori.adventure.text.Component.text("jeb_"));it.isCustomNameVisible=false
                it.getAttribute(Attribute.MAX_HEALTH)?.baseValue=50.0;it.health=50.0
                it.isSilent=true;it.isCollidable=false
            }
            Bukkit.getMobGoals().removeAllGoals(sheep)
            arena.track(sheep);p.summons+=sheep;SmashSummons.owners[sheep.uniqueId]=s;ClassicSummons.owners[sheep.uniqueId]=player
            var route:List<Vector> = emptyList();var cursor=0
            c.loop(600,finish={sheep.remove();p.summons.remove(sheep);SmashSummons.owners.remove(sheep.uniqueId);ClassicSummons.owners.remove(sheep.uniqueId)}) {age ->
                if(!sheep.isValid || sheep.isDead || target !in s.enemies())return@loop
                if(sheep.location.distanceSquared(target.location)<2.6) {
                    c.pulse(sheep.location,4.0,19.2,2.5,p);arena.destroy(sheep.location,2.5,16);sheep.remove();return@loop
                }
                if(age%10==0) {route=AirRoute.find(sheep.location,target.location.clone().add(0.0,.6,0.0));cursor=0}
                while(cursor<route.size && route[cursor].distanceSquared(sheep.location.toVector())<.7)cursor++
                val aim=route.getOrNull(cursor) ?: target.location.toVector().add(Vector(0.0,.6,0.0))
                val direction=aim.clone().subtract(sheep.location.toVector())
                if(direction.lengthSquared()>.001) {
                    direction.normalize();sheep.velocity=direction.clone().multiply(.42)
                    val look=sheep.location.setDirection(direction);sheep.setRotation(look.yaw,look.pitch)
                }
                sheep.fallDistance=0f
                if(age%8 in setOf(0,3,5))sheep.world.playSound(sheep.location,Sound.ENTITY_SHEEP_AMBIENT,1f,1.5f)
            }
        }
    }
}

/** Bounded three-dimensional A*: flying sheep can route above or around solid walls. */
internal object AirRoute {
    private data class Cell(val x:Int,val y:Int,val z:Int) {
        fun distance(b:Cell)=abs(x-b.x)+abs(y-b.y)+abs(z-b.z)
        fun center()=Vector(x+.5,y+.5,z+.5)
    }
    fun find(from:Location,to:Location):List<Vector> {
        val start=Cell(from.blockX,from.blockY,from.blockZ);val goal=Cell(to.blockX,to.blockY,to.blockZ)
        val cost=hashMapOf(start to 0);val parent=hashMapOf<Cell,Cell>()
        val open=java.util.PriorityQueue<Pair<Cell,Int>>(compareBy{it.second});open.add(start to start.distance(goal))
        var best=start;var visited=0
        while(open.isNotEmpty() && visited++<1800) {
            val at=open.remove().first
            if(at.distance(goal)<best.distance(goal))best=at
            if(at.distance(goal)<=1){best=at;break}
            val neighbors=listOf(Cell(at.x+1,at.y,at.z),Cell(at.x-1,at.y,at.z),Cell(at.x,at.y+1,at.z),Cell(at.x,at.y-1,at.z),Cell(at.x,at.y,at.z+1),Cell(at.x,at.y,at.z-1))
            for(next in neighbors) {
                if(next.distance(start)>40 || next.y !in from.world.minHeight until from.world.maxHeight-1)continue
                if(!from.world.getBlockAt(next.x,next.y,next.z).isPassable || !from.world.getBlockAt(next.x,next.y+1,next.z).isPassable)continue
                val score=cost.getValue(at)+1
                if(score >= (cost[next] ?: Int.MAX_VALUE))continue
                cost[next]=score;parent[next]=at;open.add(next to score+next.distance(goal))
            }
        }
        val route=mutableListOf<Vector>();var at=best
        while(at!=start){route+=at.center();at=parent[at] ?: break}
        return route.asReversed()
    }
}
