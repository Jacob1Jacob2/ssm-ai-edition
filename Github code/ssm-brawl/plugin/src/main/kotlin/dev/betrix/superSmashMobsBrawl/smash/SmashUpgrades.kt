package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.extensions.itemEffect
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import kotlin.math.*

/** Upgrades retain the base ability's shape, sounds and physical behavior. */
class SmashUpgrades(private val s: SmashSession) {
    private val p get()=s.player
    private val c get()=s.combat
    private val arena get()=s.arena
    fun bones(power:SmashPower) {
        val radius=s.moves.base(power,"explosionRadius",7.0)*1.6
        val at=p.location.clone().add(0.0,.5,0.0)
        at.itemEffect(144,1.2,Sound.ENTITY_SKELETON_HURT,2f,1.2f,Material.BONE,40)
        p.world.playSound(at,Sound.ENTITY_SKELETON_DEATH,1.4f,.7f)
        c.loop(9,4) {p.world.playSound(at,Sound.ENTITY_SKELETON_HURT,1f,1.1f+it*.08f)}
        s.enemies().filter {it.location.distanceSquared(p.location)<radius*radius}.forEach {
            val damage=s.moves.base(power,"baseDamage",6.0)*1.2*(1-it.location.distance(p.location)/radius)
            s.hit(it,damage,3.0,source=power.spec.name)
        }
    }
    fun slam(power:SmashPower) {
        val radius=s.moves.base(power,"damageRange",8.0)*1.2
        val at=p.location
        s.enemies().filter {it.location.distanceSquared(at)<=radius*radius}.forEach {
            val damage=s.moves.base(power,"baseDamage",10.0)*((radius-it.location.distance(at))/radius)+.5
            s.hit(it,damage,2.4,source=power.spec.name)
        }
        p.world.playSound(at,Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR,2f,.2f)
        for(x in -5..5)for(z in -5..5) {
            val ground=at.clone().add(x.toDouble(),-.2,z.toDouble()).block
            if(ground.type.isSolid && ground.getRelative(0,1,0).isPassable)
                p.world.playEffect(ground.location,Effect.STEP_SOUND,ground.type)
        }
    }
    fun fissure(power:SmashPower) {
        var at=p.location.clone()
        var direction=p.eyeLocation.direction.setY(0.0).let {if(it.lengthSquared()<.01)Vector(0,0,1) else it.normalize()}
        val used=mutableSetOf<org.bukkit.block.Block>()
        val victims=mutableSetOf<java.util.UUID>()
        var blocked=false
        var target:Player?=null
        // Former path: .9 blocks every two ticks for 35 ticks (16.2 blocks).
        // Three substeps prevent gaps while moving 1.35 blocks/tick for 48.6 blocks total.
        c.loop(36) {age ->
            if(!blocked) {
                val enemies=s.enemies()
                target=target?.takeIf {it in enemies && it.location.distanceSquared(at)<3600}
                    ?: enemies.filter {it.location.distanceSquared(at)<3600}.minByOrNull {it.location.distanceSquared(at)}
                target?.let {
                    val desired=it.location.toVector().add(it.velocity.clone().multiply(2)).subtract(at.toVector()).setY(0.0)
                    if(desired.lengthSquared()>.1)direction=direction.multiply(.60).add(desired.normalize().multiply(.40)).normalize()
                }
                repeat(3) {
                    if(!blocked) {
                        at.add(direction.clone().multiply(.45))
                        // Ignore this fissure's raised rubble when looking for the original floor.
                        val ground=(0 downTo -3).map {dy->at.block.getRelative(0,dy,0)}.firstOrNull {
                            (arena.originalBlocks[it]?.type ?: it.type).isSolid
                        }
                        if(ground==null || ground.type in setOf(Material.BEDROCK,Material.BARRIER))blocked=true
                        else {
                            at.y=ground.y+1.0
                            if(used.add(ground)) {
                                val base=arena.originalBlocks[ground]?.type ?: ground.type
                                val material=when(base) {Material.STONE->Material.COBBLESTONE;Material.GRASS_BLOCK->Material.DIRT;Material.STONE_BRICKS->Material.CRACKED_STONE_BRICKS;else->base}
                                arena.temporaryBlock(ground,material,200)
                                for(y in 1..if(age<6)1 else 2) {
                                    val block=ground.getRelative(0,y,0)
                                    if(block.isPassable && arena.temporaryBlock(block,material,180))p.world.playEffect(block.location,Effect.STEP_SOUND,material)
                                }
                            }
                            s.enemies().filter {it.location.distanceSquared(at)<4 && it.uniqueId !in victims}.forEach {
                                if(s.hit(it,(4.0+age/2)*1.2,1.5,.6+age*.025,power.spec.name,at))victims+=it.uniqueId
                            }
                        }
                    }
                }
                if(age%2==0)p.world.playSound(at,Sound.BLOCK_STONE_BREAK,.8f,.6f)
            }
        }
    }
    fun needler(power:SmashPower) {
        if(power.active)return
        power.active=true;power.uses--;power.nextUse=arena.tick+6;s.refreshTool(power)
        c.loop(6,finish={power.active=false}) {
            c.shot(power,Material.ARROW,s.moves.base(power,"baseDamage",1.1)*1.2,1.0,
                s.moves.base(power,"arrowVelocity",1.2),1.2,18.0,
                direction=c.spread(p.eyeLocation.direction,.045))
        }
    }
    fun webs(power:SmashPower) {
        p.world.playSound(p.location,Sound.ENTITY_SPIDER_AMBIENT,2f,.6f)
        repeat(75) {
            val backwards=p.eyeLocation.direction.multiply(-1)
            c.shot(power,Material.COBWEB,s.moves.base(power,"webDamage",4.0)*1.2,0.0,1.5+Math.random()*.7,
                origin=p.location.clone().add(0.0,1.0,0.0),direction=c.spread(backwards,.6).setY(-.1-Math.random()*.35),gravity=.08,
                onHit={s.moves.web(it.location,400)},onImpact={s.moves.web(it,400)})
        }
        p.velocity=dev.betrix.superSmashMobsBrawl.utils.SpinWebLaunch.velocity(p.eyeLocation.direction,p.eyeLocation.pitch)
        p.fallDistance=0f
    }
    fun earthquake(power:SmashPower) {
        val ultra=power.spec.tier==SmashTier.ULTRA
        arena.players().forEach {it.playSound(it.location,Sound.ENTITY_MINECART_RIDING,1.1f,.5f)}
        if(ultra) {
            val at=p.location
            for(x in -8..8 step 2)for(z in -8..8 step 2) {
                val floor=at.clone().add(x.toDouble(),-.2,z.toDouble())
                if(floor.block.type.isSolid)p.world.spawnParticle(Particle.SMOKE,floor.add(0.0,.3,0.0),2,.4,.08,.4,.015)
            }
        }
        s.enemies().filter {it.isOnGround}.forEach {
            s.hit(it,if(ultra)9.0 else 3.0,.3,if(ultra).9 else .55,power.spec.name)
            p.world.spawnParticle(Particle.BLOCK,it.location,if(ultra)30 else 16,1.0,.2,1.0,.05,SmashKitEffects.ground(it.location))
            if(ultra)p.world.spawnParticle(Particle.SMOKE,it.location,12,1.0,.08,1.0,.015)
            it.playSound(it.location,Sound.BLOCK_STONE_BREAK,.6f,.6f)
        }
    }
}
