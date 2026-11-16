package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.utils.NewKitMath
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import kotlin.math.*

class StrawburySwirlAbility(player:Player) : ClassicKitAbility("strawbury_swirl",player) {
    override fun activate() { super.activate(); spray() }
    fun spray(multiplier:Double=1.0, radius:Double=1.45 * itemBoost) {
        val at=player.eyeLocation
        val direction=at.direction.normalize()
        val right=direction.clone().crossProduct(Vector(0.0,1.0,0.0)).let { if(it.lengthSquared()<0.01)Vector(1.0,0.0,0.0) else it.normalize() }
        val up=right.clone().crossProduct(direction).normalize()
        val struck=mutableSetOf<java.util.UUID>()
        loop {age ->
            if(age>=22)return@loop false
            val step=direction.clone().multiply(0.95 * itemBoost)
            if(at.world.rayTraceBlocks(at,step,step.length(),FluidCollisionMode.NEVER,true)!=null)return@loop false
            at.add(step)
            // Two continuous twisting milk strands, trailing behind the moving wavefront.
            repeat(2) { strand ->
                repeat(24) { i ->
                    val trail=i*0.10
                    val angle=age*0.48-trail*3.2+strand*PI
                    val point=at.clone().subtract(direction.clone().multiply(trail))
                        .add(right.clone().multiply(cos(angle)*radius*0.72))
                        .add(up.clone().multiply(sin(angle)*radius*0.72))
                    at.world.spawnParticle(Particle.FIREWORK,point,1,0.0,0.0,0.0,0.0)
                    at.world.spawnParticle(Particle.DUST,point,1,0.0,0.0,0.0,0.0,
                        Particle.DustOptions(if(strand==0)Color.fromRGB(255,80,120)else Color.fromRGB(255,230,235),1.1f))
                }
            }
            if(age%4==0) at.world.playSound(at,Sound.ENTITY_GENERIC_DRINK,0.8f,1f)
            targets(at,radius+1).forEach {target ->
                if(target.uniqueId !in struck && target.boundingBox.clone().expand(radius).contains(at.toVector()) &&
                    hit(target,4.5*multiplier,2.0,at.clone().subtract(direction))) {
                    struck.add(target.uniqueId)
                    loop { t ->
                        if(t>12 || !targetable(target))false
                        else {
                            val travel=target.velocity.clone().setY(0.0)
                            val wall=travel.lengthSquared()>0.01 && target.world.rayTraceBlocks(target.location.add(0.0,0.7,0.0),travel,0.8,FluidCollisionMode.NEVER,true)!=null
                            if(wall) { hit(target,3.5*multiplier); target.world.spawnParticle(Particle.DUST,target.location,15,0.5,0.5,0.5,0.0,Particle.DustOptions(Color.RED,1f)); false } else true
                        }
                    }
                }
            }
            true
        }
    }
}

/** Restores every changed block when a cast expires, its owner dies, or the match ends. */
abstract class MushroomTerrainAbility(id:String,player:Player):ClassicKitAbility(id,player) {
    private val originals=linkedMapOf<Block,org.bukkit.block.BlockState>()
    private val references=mutableMapOf<Block,Int>()
    protected fun place(block:Block,material:Material) { if(game?.let(dev.betrix.superSmashMobsBrawl.smash.SmashService::arena)?.protectsCrystal(block)==true || block.type in setOf(Material.BARRIER,Material.BEDROCK))return; originals.putIfAbsent(block,block.state); references[block]=(references[block]?:0)+1; block.setType(material,false) }
    protected fun restore(blocks:Collection<Block>) {
        val states=mutableListOf<org.bukkit.block.BlockState>()
        blocks.forEach {b -> val count=(references[b]?:1)-1
            if(count<=0){references.remove(b);originals.remove(b)?.let{states+=it}}else references[b]=count
        }
        dev.betrix.superSmashMobsBrawl.utils.TerrainRestoration.restore(states)
    }
    override fun teardown() { references.clear(); restore(originals.keys.toList()); super.teardown() }
}

class FungalTrampolineAbility(player:Player):MushroomTerrainAbility("fungal_trampoline",player) {
    override fun activate() { super.activate(); trampoline() }
    fun trampoline(multiplier:Double=1.0, enhanced:Boolean=false) {
        val center=player.location.block.getRelative(0,-1,0)
        val blocks=mutableListOf<Block>()
        val radius=if(enhanced)2 else 1
        for(x in -radius..radius)for(z in -radius..radius) {val b=center.getRelative(x,0,z);place(b,Material.BROWN_MUSHROOM_BLOCK);blocks.add(b)}
        val last=mutableMapOf<java.util.UUID,Int>()
        loop(finish={restore(blocks)}) {age ->
            if(age>=120)return@loop false
            if(enhanced && age%4==0)blocks.forEach {it.world.spawnParticle(Particle.SPORE_BLOSSOM_AIR,it.location.add(.5,1.15,.5),2,.3,.15,.3,.02)}
            val top=center.y+1.0
            val nearby=center.world.getNearbyLivingEntities(center.location.add(0.5,1.0,0.5),if(enhanced)4.5 else 3.0)
            nearby.forEach {entity ->
                val at=entity.location
                if((entity==player || targetable(entity)) && abs(at.x-(center.x+0.5))<radius+.8 && abs(at.z-(center.z+0.5))<radius+.8 &&
                    at.y in (top-0.2)..(top+0.7) && age-(last[entity.uniqueId]?:-100)>=12) {
                    if(enhanced && entity!=player)hit(entity,8.0,0.0,center.location)
                    entity.velocity=entity.velocity.setY(1.25*sqrt(multiplier)*itemBoost);entity.fallDistance=0f;last[entity.uniqueId]=age
                    entity.world.spawnParticle(Particle.CLOUD,entity.location,15,0.7,0.1,0.7,0.1)
                    entity.world.playSound(entity.location,Sound.BLOCK_SLIME_BLOCK_FALL,0.8f,0.8f)
                }
            }
            true
        }
    }
}

class MushroomWallAbility(player:Player):MushroomTerrainAbility("mushroom_wall",player) {
    override fun activate() {super.activate(); wall()}
    fun wall(multiplier:Double=1.0, enhanced:Boolean=false) {
        val direction=player.location.direction.setY(0.0).let {if(it.lengthSquared()<.001)Vector(0,0,1)else it.normalize()}
        val alongX=abs(direction.z)>=abs(direction.x)
        val origin=player.location.add(direction.clone().multiply(2.5))
        var center=origin.block
        val blocks=mutableListOf<Block>()
        val radius=if(enhanced)2 else 1
        val height=if(enhanced)5 else 3
        fun build() {
            for(horizontal in -radius..radius)for(y in 0 until height) {
                val b=center.getRelative(if(alongX)horizontal else 0,y,if(alongX)0 else horizontal)
                if(b.isPassable){place(b,Material.RED_MUSHROOM_BLOCK);blocks.add(b)}
            }
        }
        build()
        val last=mutableMapOf<java.util.UUID,Int>()
        loop(finish={restore(blocks)}) {age ->
            if(age>=160)return@loop false
            if(enhanced) {
                val next=origin.clone().add(direction.clone().multiply(age*.36)).block
                if(next!=center) {
                    restore(blocks);blocks.clear();center=next;build()
                }
            }
            if(age%4==0)blocks.forEach {
                it.world.spawnParticle(Particle.DUST,it.location.add(.5,.5,.5),3,.65,.5,.65,0.0,Particle.DustOptions(Color.RED,1.3f))
                if(enhanced)it.world.spawnParticle(Particle.CRIMSON_SPORE,it.location.add(.5,.5,.5),4,.7,.7,.7,.03)
            }
            targets(center.location.add(.5,height/2.0,.5),height+1.0).forEach {target ->
                if(age-(last[target.uniqueId]?:-100)>=10 && blocks.any {target.boundingBox.clone().expand(.55).overlaps(it.boundingBox)}) {
                    if(hit(target,4.0*multiplier,1.8,center.location)) {
                        last[target.uniqueId]=age
                        if(enhanced) {
                            val ahead=target.location.add(direction.clone().multiply(1.2))
                            if(ahead.block.isPassable && ahead.clone().add(0.0,1.0,0.0).block.isPassable)target.teleport(ahead)
                            target.velocity=direction.clone().multiply(.65).setY(.25)
                        }
                    }
                }
            }
            true
        }
    }
}
