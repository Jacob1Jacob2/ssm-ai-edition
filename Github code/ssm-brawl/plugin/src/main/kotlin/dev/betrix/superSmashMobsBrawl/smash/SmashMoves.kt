package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.services.DataService
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.potion.*
import org.bukkit.util.Vector
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.*

class SmashMoves(val s:SmashSession):KoinComponent {
    private val data:DataService by inject()
    private val player get()=s.player
    private val c get()=s.combat
    private val arena get()=s.arena
    val elemental=ElementalSmashes(s)
    val upgrades=SmashUpgrades(s)
    val forms=SmashForms(s)
    val passives=SmashPassives(s)
    val wolfMagma=WolfMagmaSmashes(s)
    val witch=WitchSmashes(s)
    val undead=UndeadSmashes(s)
    val farm=FarmSmashes(s)
    private val newKits=NewKitSmashes(s)
    private val creeper=CreeperSmashes(s)
    private val blocks=TripleBlockToss(s)
    private val polar=PolarBearSmashes(s)
    fun releaseBlocks(p:SmashPower)=blocks.release(p)
    fun base(p:SmashPower,key:String,fallback:Double):Double = p.spec.ability?.let { data.getAbility(it)?.metadata?.get(key)?.content?.toDoubleOrNull() } ?: fallback
    fun cooldown(p:SmashPower):Int = when {
        p.spec.key=="polar_bear:3" -> polar.cooldown()
        p.spec.key=="slime:1" -> 1
        p.spec.key=="pig:1" -> 10
        p.spec.key=="goat:1" -> 10
        p.spec.kit in setOf("mooshroom","phantom") -> 10
        p.spec.cooldownTicks>0 -> p.spec.cooldownTicks
        else -> 1
    }
    fun begin(p:SmashPower) {
        elemental.begin(p)
        if(p.spec.key=="pig:3")farm.pigmanBegin()
        if(p.spec.kit=="polar_bear") {polar.begin(p);return}
        if(newKits.handles(p)){newKits.begin(p);return}
        if(p.spec.kind==SmashKind.BURST) burst(p)
        if(p.spec.key=="villager:1") {
            player.world.spawnParticle(Particle.FIREWORK,player.location.add(0.0,1.0,0.0),40,1.0,1.0,1.0,0.1)
            c.dust(player.location,SmashTier.REGULAR,80,1.0)
        }
        if(p.spec.kind==SmashKind.FORM)forms.begin(p)
    }
    fun end(p:SmashPower) {
        elemental.end(p)
        undead.end(p)
        if(p.spec.key=="pig:3")s.pigmanSpeed(false)
        if(p.spec.kit=="polar_bear") {polar.end(p);return}
        creeper.end(p)
        if(p.spec.key=="magma_cube:2")wolfMagma.stopFlash(p,false)
        if(p.spec.key=="witch:2")witch.end()
        if(p.spec.key=="wolf:3")s.frenzySpeed(false)
        if(p.spec.key=="enderman:1")blocks.clear(p)
        if(p.spec.key=="enderman:2") {s.rage=false;s.rageSpeed(false)}
        if(p.spec.key=="spider:3")s.restoreHealthCap()
        newKits.end(p)
        if(p.spec.kind==SmashKind.FORM)forms.end(p)
    }
    fun tick(p:SmashPower) {
        if(p.spec.key=="blaze:0")elemental.inferno(p)
        undead.tick(p)
        if(newKits.handles(p)){newKits.tick(p);return}
        if(p.spec.key=="witch:2")witch.tick(p)
        if(p.spec.key=="magma_cube:2"){wolfMagma.flash(p);return}
        if(p.spec.key=="magma_cube:1" && p.active) {
            if(s.holding(p) && p.uses>0)wolfMagma.gun(p) else p.active=false
        }
        if(p.spec.kind==SmashKind.FORM) {forms.tick(p);return}
        if(p.spec.kind==SmashKind.PASSIVE || p.spec.kind==SmashKind.BURST)passives.tick(p)
        if(p.spec.key=="enderman:1")blocks.tick(p)
        if(p.spec.key=="zombie:0" && p.active) {
            if(player.isHandRaised || dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(player)) {
                p.charged=(p.charged+1).coerceAtMost(30);player.exp=(p.charged/6)/5f
                if(p.charged%6==0 && p.counter!=p.charged) {
                    p.counter=p.charged;player.playSound(player.eyeLocation,Sound.BLOCK_DISPENSER_DISPENSE,1f,1+p.charged/60f)
                }
                if(dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(player) && p.charged==30)releaseBow(p)
            }
        }
        if(p.spec.key=="slime:1" && p.active) {
            if(!s.holding(p))releaseRocket(p)
            else {
                if(p.charged<20) {
                    p.charged++
                    // A full quick charge costs 10% of a full normal charge, despite finishing
                    // three times sooner. A ready rocket stops draining, like the base ability.
                    val cost=base(p,"expDrainPerTick",.00916)*base(p,"maxChargeTimeMs",3000.0)/50.0/20.0*.1
                    player.exp=(player.exp-cost.toFloat()).coerceAtLeast(0f)
                }
                player.world.spawnParticle(Particle.ITEM_SLIME,player.location.add(0.0,1.0,0.0),5,.3,.3,.3,.02)
                player.world.playSound(player.location,Sound.ENTITY_SLIME_SQUISH,.5f,.5f+1.5f*p.charged/20f)
                if(player.exp<0.01 || (dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(player) && p.charged==20))releaseRocket(p)
            }
        }
        if(p.spec.fuelSeconds>0 && p.spec.kind==SmashKind.TOOL && p.active) {
            if(!s.holding(p))p.active=false else if(s.consumeFuel(p))fuel(p)
        }
        if(p.active && p.spec.key in setOf("squid:1","villager:2")) {
            if(!s.holding(p)) {p.active=false;return}
            // One shot per tick, with a small three-dimensional cone.
            if(arena.tick>=p.nextUse && p.uses>0) {
                c.shot(p,if(p.spec.key=="squid:1")Material.INK_SAC else listOf(Material.EMERALD,Material.GOLD_INGOT,Material.IRON_INGOT)[p.counter%3],
                    if(p.spec.key=="squid:1")base(p,"projectileDamage",1.725) else base(p,"damagePerPellet",1.5)*1.2*.9,
                    1.0,2.5,direction=c.spread(player.eyeLocation.direction,.12),hitInterval=0,gravity=if(p.spec.key=="villager:2").025 else 0.0,
                    onHit={if(p.spec.key=="villager:2")dev.betrix.superSmashMobsBrawl.utils.HitFeedback.play(player)})
                p.uses--;p.counter++;p.nextUse=arena.tick+1;s.refreshTool(p)
            }
        }
        if(p.active && p.spec.key=="snowman:1") {
            if(!s.holding(p) || p.counter>=100) {p.active=false;p.uses--;s.rebuild();return}
            p.counter+=2
            val start=p.position ?: player.location.clone().also {p.position=it}
            val dir=p.direction ?: player.eyeLocation.direction.clone().normalize().also {p.direction=it}
            val side=Vector(-dir.z,0.0,dir.x).let {if(it.lengthSquared()<.001)Vector(1,0,0) else it.normalize()}
            repeat(2) {i ->
                val at=start.clone().add(dir.clone().multiply((p.counter-i).toDouble())).subtract(0.0,1.0,0.0)
                val path=mutableSetOf<org.bukkit.block.Block>()
                for(w in -2..2)at.clone().add(side.clone().multiply(w.toDouble())).block.let {if(arena.temporaryBlock(it,Material.PACKED_ICE,400))path+=it}
                c.loop(400,5) {
                    path.filter {it.type==Material.PACKED_ICE}.forEach {it.world.spawnParticle(Particle.SNOWFLAKE,it.location.add(.5,1.15,.5),2,.3,.1,.3,.01)}
                    s.enemies().filter {it.location.clone().subtract(0.0,0.2,0.0).block in path}.forEach {it.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS,10,9,false,true))}}
            }
            if(p.age%6==0)player.world.playSound(player.location,Sound.BLOCK_AMETHYST_BLOCK_CHIME,.55f,1.3f+p.counter%5*.08f)
            s.enemies().filter {it.location.clone().subtract(0.0,1.0,0.0).block.type==Material.PACKED_ICE}.forEach {it.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS,30,9,false,true))}
        }
    }
    fun releaseRocket(p:SmashPower) {
        if(!p.active)return
        p.active=false
        player.world.spawnParticle(Particle.ITEM_SLIME,player.eyeLocation,20,.3,.3,.3,.08)
        val rocket=dev.betrix.superSmashMobsBrawl.projectiles.SlimeProjectile(player,"abilities.slime_rocket.name",(p.charged*3.0/20).coerceAtMost(3.0),1.2)
        rocket.setupHitCallback().launch()
        c.loop(600,finish={rocket.teardown()}){}
        p.uses--;p.charged=0;p.nextUse=arena.tick+cooldown(p);s.refreshTool(p)
    }
    fun releaseBow(p:SmashPower) {
        if(!p.active || p.uses<=0)return
        val count=(1+p.charged/6).coerceAtMost(6)
        p.uses--;s.refreshTool(p);player.exp=0f
        (player as org.bukkit.craftbukkit.entity.CraftPlayer).handle.stopUsingItem()
        p.active=false;p.charged=0
        c.loop(count*2,2) {c.shot(p,Material.ARROW,12.0,1.0,3.0,gravity=.05)}
    }
    fun click(p:SmashPower,left:Boolean):Boolean {
        if(p.spec.kit=="polar_bear")return polar.click(p)
        if(newKits.handles(p))return newKits.click(p)
        elemental.click(p)?.let {return it}
        if(p.spec.kind==SmashKind.FORM)return forms.click(p)
        if(p.spec.kind==SmashKind.SUMMON) {c.mob(p,EntityType.SNOW_GOLEM,seconds=20,damage=2.0,scale=if(p.spec.tier==SmashTier.ULTRA)2.0 else 1.0);return true}
        if(p.spec.key=="magma_cube:2") {wolfMagma.startFlash(p);return false}
        if(p.spec.fuelSeconds>0) {
            if(p.spec.key=="cow:2" && p.active){p.active=false;player.velocity=Vector();return false}
            p.active=true;if(p.spec.key=="guardian:2")p.counter=0;return false
        }
        when(p.spec.key) {
            "skeleton:0" -> c.shot(p,Material.ARROW,6.0,1.0,3.4)
            "skeleton:2" -> upgrades.bones(p)
            "iron_golem:0" -> upgrades.slam(p)
            "iron_golem:1" -> upgrades.fissure(p)
            "spider:0" -> {upgrades.needler(p);return false}
            "spider:1" -> upgrades.webs(p)
            "slime:1" -> {if(player.exp<0.01)return false;if(!p.active){p.active=true;p.charged=0};return false}
            "squid:1","villager:2" -> {p.active=true;return false}
            "squid:2" -> c.shot(p,Material.INK_SAC,16.0,2.5,2.3,3.0,explosion=6.0,terrain=4.0)
            "creeper:0" -> c.shot(p,Material.CHARCOAL,base(p,"projectileDamage",6.5)*1.2,base(p,"projectileKnockbackModifier",2.5)*1.2,1.5,1.5,16.0,3.0)
            "creeper:1" -> {
                val radius=base(p,"explosionRadius",8.0)
                s.enemies().filter{it.location.distanceSquared(player.location)<radius*radius}.forEach {
                    val damage=15.0*(0.1+0.9*(radius-it.location.distance(player.location))/radius)*1.2
                    s.hit(it,damage,base(p,"explosionKnockbackMultiplier",2.5),source=p.spec.name)
                }
                c.pulse(player.location,radius,0.0,0.0,p)
                arena.destroy(player.location,3.0,12)
                player.velocity=player.eyeLocation.direction.multiply(1.8).setY(max(0.5,player.eyeLocation.direction.y*1.8))
            }
            "enderman:1" -> {blocks.start(p);return false}
            "snowman:1" -> {if(p.active)return false;p.active=true;p.counter=0;p.position=player.location.clone();p.direction=player.eyeLocation.direction.clone().normalize();return false}
            "wolf:1" -> wolfMagma.wolfFly(p)
            "wolf:2" -> wolfMagma.throwWolf(p)
            "magma_cube:1" -> {p.active=true;return false}
            "witch:0","witch:1" -> witch.potion(p)
            "witch:2" -> {witch.bats(p);return false}
            "wither_skeleton:0" -> c.shot(p,Material.WITHER_SKELETON_SKULL,base(p,"damage",6.0)*1.2,1.2,1.4,1.0,20.0,2.0)
            "wither_skeleton:1" -> return undead.images(p)
            "zombie:0" -> {if(!p.active){p.charged=0;p.counter=0};p.active=true;return false}
            "zombie:1" -> {p.active=true;return false}
            "cow:1" -> farm.herd(p)
            "horse:0" -> farm.kick(p)
            "pig:0","pig:2" -> return farm.bacon(p)
            "pig:1" -> return farm.bomb(p)
            "blaze:0" -> c.fan(p,Material.AIR,3,base(p,"damage",1.25),0.5,1.0,onHit={it.fireTicks=60})
            "chicken:2" -> c.fan(p,Material.CHICKEN_SPAWN_EGG,16,base(p,"eggDamage",1.0),0.5,2.4,onHit={target->c.mob(p,EntityType.CHICKEN,target.location.clone().add(0.0,0.2,0.0),seconds=10,baby=true,damage=1.5,target=target)},creatureVisual=false)
            "chicken:3" -> {
                if(p.active)return false
                p.active=true
                c.shot(p,Material.CHICKEN_SPAWN_EGG,base(p,"damage",7.0)*1.2,1.5,2.2,1.6,explosion=4.0,terrain=2.5,onFinish={hit->
                    p.active=false;if(!hit)p.uses--;if(s.alive)s.rebuild()
                });return false
            }
            "guardian:0" -> c.line(p,15.0,9.0,1.5)
            "guardian:1" -> c.fan(p,Material.PRISMARINE_SHARD,9,base(p,"damage",3.0)*1.2,0.0,2.0,scale=1.5,onHit={it.velocity=player.location.toVector().subtract(it.location.toVector()).normalize().setY(0.5).multiply(1.5)})
            "sheep:0" -> {
                (dev.betrix.superSmashMobsBrawl.services.KitService.getKitForPlayer(player)?.getPassive("double_jump") as? dev.betrix.superSmashMobsBrawl.passives.DoubleJumpPassive)?.rechargeNow()
                player.velocity=player.eyeLocation.direction.multiply(1.2).setY(1.5);flightHits(p,20,base(p,"damage",8.0),base(p,"knockbackMultiplier",2.5),base(p,"damageRadius",2.0))
            }
            "sheep:1" -> c.shot(p,Material.AIR,base(p,"damage",7.0)*1.2,base(p,"knockbackMultiplier",3.0)*1.2,1.5,1.0,24.0)
            "sheep:2" -> c.shot(p,Material.WHITE_WOOL,base(p,"explosionDamage",12.0),2.0,3.0,1.0,explosion=base(p,"damageRadius",9.0))
            "villager:0" -> {
                val range=base(p,"range",6.0)*1.2
                s.enemies().filter {val d=it.eyeLocation.toVector().subtract(player.eyeLocation.toVector());d.length()<=range && d.normalize().dot(player.eyeLocation.direction)>0.5}.forEach {s.hit(it,base(p,"damage",6.0)*1.2,2.4,source=p.spec.name)}
                c.line(p,range,0.0,1.2)
            }
            else -> return false
        }
        return true
    }
    fun fuel(p:SmashPower) {
        if(p.spec.key=="magma_cube:2") {wolfMagma.flash(p);return}
        if(p.spec.key=="horse:2") {farm.rush(p);return}
        if(p.spec.key=="cow:2") {farm.milkSpiral(p);return}
        if(p.spec.key=="guardian:2")return // WaterSplash owns lift, release and landing.
        val speed=when(p.spec.key){"cow:2"->1.65;"squid:0"->.75;"magma_cube:2"->base(p,"speed",1.1)*1.5;"blaze:1"->base(p,"speed",1.25)*1.5;"guardian:2"->1.3;else->1.2}
        player.velocity=player.eyeLocation.direction.multiply(speed)
        if(p.spec.key=="squid:0") {
            // Same lift and ground boost as Super Squid, scaled together by 25%.
            val velocity=player.velocity
            velocity.y=(velocity.y+.125).coerceAtMost(1.25)+(if(player.isOnGround).25 else 0.0)
            player.velocity=velocity
        }
        val damage=when(p.spec.key){"cow:2"->7.5;"slime:0"->8.4;"magma_cube:2"->base(p,"baseDamage",4.0);"blaze:1"->base(p,"damage",7.0)*1.2;else->7.2}
        val radius=if(p.spec.key=="magma_cube:2")base(p,"radius",4.0)*1.5 else 1.5
        s.enemies().filter {p.spec.key!="squid:0" && it.location.distanceSquared(player.location)<=radius*radius}.forEach {
            if(s.hit(it,damage,when(p.spec.key){"blaze:1"->3.0;"slime:0"->2.0;else->1.5},source=p.spec.name) && p.spec.key=="slime:0") {
                player.velocity=player.eyeLocation.direction.multiply(-1.1).setY(0.35);p.active=false
            }
        }
        if(p.spec.key=="squid:0") {
            player.world.spawnParticle(Particle.SPLASH,player.location.add(0.0,.5,0.0),30,.3,.3,.3,0.0)
            player.world.playSound(player.location,Sound.ENTITY_GENERIC_SPLASH,.5f,1f)
        } else if(p.spec.key=="blaze:1") {
            dev.betrix.superSmashMobsBrawl.abilities.FireflyAbility.flightEffects(player)
        } else {
            c.dust(player.location,p.spec.tier,10,0.6)
            if(p.age%4==0 && p.spec.key!="slime:0")SmashKitEffects.shot(p.spec.kit,player.location)
        }
        if(p.spec.key=="cow:2") {
            player.world.playSound(player.location,Sound.ENTITY_PLAYER_SPLASH,0.2f,0.75f)
            repeat(18){i->val angle=i*2*PI/18+p.age*0.5;c.dust(player.location.add(cos(angle)*1.5,i/9.0,sin(angle)*1.5),p.spec.tier,1,0.03)}
        }
        player.fallDistance=0f
    }
    fun blink(p:SmashPower) {
        if(p.uses<=0)return
        val ray=player.world.rayTraceBlocks(player.eyeLocation,player.eyeLocation.direction,60.0,FluidCollisionMode.NEVER,true) ?: return
        val block=ray.hitBlock ?: return
        val at=block.location.add(0.5,1.05,0.5)
        if(!at.block.isPassable || !at.clone().add(0.0,1.0,0.0).block.isPassable)return
        at.yaw=player.location.yaw;at.pitch=player.location.pitch
        val from=player.location.clone()
        from.world.spawnParticle(Particle.PORTAL,from.add(0.0,1.0,0.0),60,.4,.7,.4,.25)
        player.teleport(at)
        at.world.spawnParticle(Particle.PORTAL,at.clone().add(0.0,1.0,0.0),60,.4,.7,.4,.25)
        p.uses--;player.exp=p.uses.coerceAtLeast(0)/10f
        player.world.playSound(at,Sound.ENTITY_ENDERMAN_TELEPORT,0.8f,1f)
    }
    fun flightHits(p:SmashPower,ticks:Int,damage:Double,kb:Double,radius:Double) {
        c.loop(ticks) {s.enemies().filter {it.location.distanceSquared(player.location)<radius*radius}.forEach {s.hit(it,damage,kb,source=p.spec.name)};c.dust(player.location,p.spec.tier,5)}
    }
    fun web(at:Location,ticks:Int) {
        val b=if(at.block.isPassable || at.block.type==Material.COBWEB)at.block else at.block.getRelative(0,1,0)
        if(!b.isPassable && b.type!=Material.COBWEB)return
        arena.temporaryBlock(b,Material.COBWEB,ticks)
    }
    fun burst(p:SmashPower) {
        when(p.spec.key) {
            "creeper:2","creeper:3" -> creeper.begin(p)
            "spider:3" -> {
                val at=player.location.clone();val radius=18
                for(x in -radius..radius)for(y in 0..radius)for(z in -radius..radius) {
                    val d=sqrt((x*x+y*y+z*z).toDouble());if(d in 17.2..18.2)web(at.clone().add(x.toDouble(),y.toDouble(),z.toDouble()),600)
                }
                player.world.playSound(at,Sound.ENTITY_SPIDER_AMBIENT,2f,.6f)
            }
            "zombie:2","zombie:3" -> undead.apocalypse(p)
            "guardian:3" -> elemental.rise(p)
            "sheep:3" -> elemental.sheeple(p)
        }
    }
}
