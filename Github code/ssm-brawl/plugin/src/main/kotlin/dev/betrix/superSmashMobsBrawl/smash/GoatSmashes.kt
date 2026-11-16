package dev.betrix.superSmashMobsBrawl.smash

import org.bukkit.*
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import kotlin.math.*

class GoatSmashes(private val s:SmashSession) {
    private val p get()=s.player
    private val c get()=s.combat
    fun click(power:SmashPower):Boolean {
        when(power.spec.tier) {
            SmashTier.LIGHT -> {power.active=true;return false}
            SmashTier.REGULAR -> {
                val origin=p.eyeLocation
                val aim=origin.direction
                val side=Vector(-aim.z,0.0,aim.x).let {if(it.lengthSquared()<.001)Vector(1,0,0) else it.normalize()}
                val points=List(23) {origin.clone()}.toMutableList()
                val destinations=List(23) {i ->
                    val angle=i*2.3999632297
                    val radius=1.0+sqrt(i/22.0)*3.5
                    val destination=origin.clone().add(aim.clone().multiply(8.0+(i%5)))
                        .add(side.clone().multiply(cos(angle)*radius)).add(0.0,2.5+sin(angle)*radius*.55,0.0)
                    val delta=destination.toVector().subtract(origin.toVector())
                    origin.world.rayTraceBlocks(origin,delta.clone().normalize(),delta.length(),FluidCollisionMode.NEVER,true)
                        ?.hitPosition?.subtract(delta.clone().normalize().multiply(.3))?.toLocation(origin.world) ?: destination
                }
                var released=0;var cadence=0
                c.loop(96) {age ->
                    if(age>=34 && released<points.size) {
                        cadence+=8
                        if(cadence>=20) {
                            cadence-=20
                            val at=points[released++]
                            val target=s.enemies().minByOrNull {SmashTargeting.center(it).distanceSquared(at.toVector())}
                            c.shot(power,Material.AIR,5.0,1.2,1.25,homing=36.0,origin=at,
                                direction=target?.let {SmashTargeting.center(it).subtract(at.toVector())} ?: aim,
                                notes=true,hitInterval=2)
                        }
                    }
                    for(i in released until points.size) {
                        if(age<14)points[i]=origin.clone().add(destinations[i].toVector().subtract(origin.toVector()).multiply((age+1)/14.0))
                        val at=points[i]
                        at.world.spawnParticle(Particle.NOTE,at,0,(i%24)/24.0,0.0,0.0,1.0)
                    }
                    if(age<14 && age%2==0)SmashKitEffects.note(origin,age)
                }
            }
            else -> return false
        }
        return true
    }
    fun tick(power:SmashPower) {
        when(power.spec.tier) {
            SmashTier.LIGHT -> if(power.active) {
                if(!s.holding(power)){power.active=false;return}
                if(power.uses>0) {
                    repeat(3) {c.shot(power,Material.AIR,2.0,.7,2.4,direction=c.spread(p.eyeLocation.direction,.16),notes=true,hitInterval=1)}
                    power.uses--;s.refreshTool(power)
                }
            }
            SmashTier.SUPER,SmashTier.ULTRA -> {
                val dome=power.spec.tier==SmashTier.ULTRA
                if(dome)s.potion(PotionEffectType.SPEED,2)
                if(dome && power.age%2!=0)return
                val radii=if(dome)listOf(3.5,5.5,7.5) else listOf(4.5)
                val bob=sin(power.age*.14)*.22
                val origin=p.location.clone().add(0.0,if(dome).25+bob else 1.0+bob,0.0)
                radii.forEachIndexed {layer,radius ->
                    repeat(if(dome)5 else 1) {row ->
                        val elevation=if(dome)row*PI/10 else 0.0
                        // Unequal spacing preserves visible motion instead of rotating identical points onto each other.
                        repeat(17) {i ->
                            val angle=i*2*PI/17+sin(i.toDouble())*.045+power.age*(if(dome).24 else .13)+layer*.3
                            val at=origin.clone().add(cos(angle)*radius*cos(elevation),radius*sin(elevation),sin(angle)*radius*cos(elevation))
                            p.world.spawnParticle(Particle.NOTE,at,0,((i*5+layer*3)%24)/24.0,0.0,0.0,1.0)
                        }
                    }
                }
                s.enemies().filter {
                    val d=SmashTargeting.center(it).subtract(origin.toVector())
                    if(dome)d.y>-.8 && radii.any {radius->abs(d.length()-radius)<.8}
                    else abs(sqrt(d.x*d.x+d.z*d.z)-4.5)<.8 && abs(d.y)<1.4
                }.forEach {if(s.hit(it,if(dome)4.0 else 3.0,1.0,source=power.spec.name))SmashKitEffects.note(it.location,power.age)}
            }
            else -> {}
        }
    }
    fun end(power:SmashPower) {if(power.spec.tier==SmashTier.ULTRA)p.removePotionEffect(PotionEffectType.SPEED)}
}
