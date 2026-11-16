package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.extensions.disguise
import dev.betrix.superSmashMobsBrawl.utils.ClassicEffects
import dev.betrix.superSmashMobsBrawl.utils.ChickenMissileTargets
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.*

/** Swept projectiles use the same enemy checks and damage limiter as every other Smash. */
class SmashCombat(val session:SmashSession) {
    val player get()=session.player
    val arena get()=session.arena
    private var lastShotSound = -1L
    fun dust(at:Location,tier:SmashTier,count:Int=12,spread:Double=0.5) {
        SmashKitEffects.particles(session.kit,at,count,spread)
    }
    fun spread(direction:Vector,amount:Double):Vector = direction.clone().add(Vector(
        kotlin.random.Random.nextDouble(-amount,amount),kotlin.random.Random.nextDouble(-amount,amount),
        kotlin.random.Random.nextDouble(-amount,amount))).normalize()

    fun pulse(at:Location,radius:Double,damage:Double,kb:Double,power:SmashPower,ground:Boolean=false,vertical:Double?=null,terrain:Double=0.0,hitInterval:Int=10,sound:Boolean=true):List<Player> {
        val contacted=session.enemies().filter {damage>0 && it.location.distanceSquared(at)<=radius*radius && (!ground || it.isOnGround)}
        contacted.forEach {session.hit(it,damage,kb,vertical,power.spec.name,at,hitInterval)}
        at.world.spawnParticle(Particle.EXPLOSION,at, maxOf(1,(radius/2).toInt()),radius*.35,.5,radius*.35,0.0)
        SmashKitEffects.particles(session.kit,at.clone().add(0.0,.5,0.0),24,radius*.35)
        if(sound)at.world.playSound(at,Sound.ENTITY_GENERIC_EXPLODE,if(power.spec.key=="sheep:2")4f else .8f,0.9f)
        if(terrain>0)arena.destroy(at,terrain)
        return contacted
    }
    fun shot(power:SmashPower,material:Material,damage:Double,kb:Double=1.0,speed:Double=2.0,scale:Double=1.0,
             homing:Double=0.0,explosion:Double=0.0,terrain:Double=0.0,bounce:Boolean=false,
             origin:Location=player.eyeLocation.clone(),direction:Vector=player.eyeLocation.direction,
             onHit:((Player)->Unit)?=null,onFinish:((Boolean)->Unit)?=null,creatureVisual:Boolean=true,trail:SmashTier=power.spec.tier,onImpact:((Location)->Unit)?=null,gravity:Double=0.0,notes:Boolean=false,
             homingStrength:Double=.48,hitInterval:Int=10,blockData:org.bukkit.block.data.BlockData?=null,shotSound:Sound?=null,shotPitch:Float=1f,flightSound:Sound?=null,flightPitch:Float=.6f,flightVolume:Float=1.5f,flightSoundInterval:Int=4,onTravel:((Location,Vector)->Unit)?=null,onSummonHit:(()->Unit)?=null) {
        val chickenMissile = material==Material.CHICKEN_SPAWN_EGG && creatureVisual
        fun missileBlast(at:Location):Boolean {
            var accepted=false
            at.world.getNearbyLivingEntities(at,explosion).filter {
                ChickenMissileTargets.canHit(player,it) && it.location.distanceSquared(at)<=explosion*explosion
            }.forEach {target ->
                val applied=if(target is Player)session.hit(target,damage,kb,source=power.spec.name,origin=at,hitInterval=hitInterval)
                else {
                    val event=dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent(target,
                        dev.betrix.superSmashMobsBrawl.events.Damager.DamagerLivingEntity(player),damage,kb,
                        dev.betrix.superSmashMobsBrawl.events.BrawlDamageType.Explosion,power.spec.name)
                    event.callEvent();event.appliedDamage>0
                }
                if(applied) {
                    accepted=true
                    if(target is Player)onHit?.invoke(target) else onSummonHit?.invoke()
                }
            }
            // Use the existing explosion visuals without applying player damage twice.
            pulse(at,explosion,0.0,kb,power,terrain=terrain)
            if(accepted)ClassicEffects.fireworkBurst(at,Color.WHITE)
            return accepted
        }
        // Even persistent storms remain bounded; expired visuals are removed on every path.
        if(arena.entities.size>=450) {onFinish?.invoke(false);return}
        if(lastShotSound!=arena.tick) {
            lastShotSound=arena.tick
            if(shotSound!=null)origin.world.playSound(origin,shotSound,.7f,shotPitch)
            else if(blockData!=null) origin.world.playSound(origin,Sound.BLOCK_STONE_BREAK,.7f,1f)
            else SmashKitEffects.shot(power.spec.kit,origin)
        }
        val mobType=if(!creatureVisual)null else when(material) {Material.CHICKEN_SPAWN_EGG->EntityType.CHICKEN;Material.COW_SPAWN_EGG->EntityType.COW;Material.PIG_SPAWN_EGG->EntityType.PIG;Material.BAT_SPAWN_EGG->EntityType.BAT;else->null}
        val display:Entity=if(material==Material.ARROW) origin.world.spawn(origin,Arrow::class.java) {
            it.shooter=player;it.damage=0.0;it.isCritical=false;it.setGravity(false)
            it.pickupStatus=AbstractArrow.PickupStatus.DISALLOWED
            (it as org.bukkit.craftbukkit.entity.CraftArrow).handle.setNoPhysics(true)
        } else if(blockData!=null) origin.world.spawn(origin,BlockDisplay::class.java) {
            it.block=blockData;it.teleportDuration=1;it.interpolationDuration=1
            it.transformation=Transformation(Vector3f((-scale/2).toFloat()),Quaternionf(),Vector3f(scale.toFloat()),Quaternionf())
        } else if(mobType!=null)(origin.world.spawnEntity(origin,mobType) as LivingEntity).apply {
            setAI(false);setGravity(false);isInvulnerable=true;isCollidable=false;isSilent=true
            if(this is Cow)variant=Cow.Variant.TEMPERATE
            if(this is Pig)variant=Pig.Variant.TEMPERATE
            if(this is Chicken)variant=Chicken.Variant.TEMPERATE
            getAttribute(org.bukkit.attribute.Attribute.SCALE)?.baseValue=scale
            if(this is Ageable) {if(mobType==EntityType.CHICKEN && scale<1.4)setBaby() else setAdult()}
        } else origin.world.spawn(origin,ItemDisplay::class.java) {
            it.setItemStack(ItemStack.of(material));it.isPersistent=false
            it.teleportDuration=1;it.interpolationDuration=1
            it.transformation=Transformation(Vector3f(),Quaternionf(),Vector3f(scale.toFloat()),Quaternionf())
            it.brightness=Display.Brightness(15,15)
        }
        SmashVisuals.ids+=display.uniqueId
        arena.track(display)
        // The first arrow packet is at eye height. Hide it from its shooter until it clears
        // the camera; collision still sweeps from the eyes so nearby walls cannot be skipped.
        var hiddenFromShooter=display is Arrow
        if(hiddenFromShooter)player.hideEntity(dev.betrix.superSmashMobsBrawl.items.ItemService.plugin,display)
        arena.effect(object:SmashEffect {
            var at=origin.clone();var velocity=direction.clone().normalize().multiply(speed);var age=0;var bounced=false;var hit=false
            var lockedTarget:Player?=null
            var landedTicks:Int?=null
            override fun step():Boolean {
                if(!player.isOnline || !session.alive || !display.isValid)return false
                landedTicks?.let {ticks -> landedTicks=ticks+1;return ticks+1<dev.betrix.superSmashMobsBrawl.projectiles.ArrowLanding.LIFETIME_TICKS}
                if(age++>=(if(power.spec.key=="blaze:0")6 else 80))return false
                if(homing>0 && (!bounce || bounced)) {
                    val enemies=session.enemies()
                    val target=lockedTarget?.takeIf {it in enemies && SmashTargeting.center(it).distanceSquared(at.toVector())<=homing*homing}
                        ?: enemies.filter {SmashTargeting.center(it).distanceSquared(at.toVector())<=homing*homing}
                            .minByOrNull {SmashTargeting.center(it).distanceSquared(at.toVector())}
                    lockedTarget=target
                    if(target!=null) {
                        velocity=SmashTargeting.steer(at.toVector(),velocity,SmashTargeting.center(target),target.velocity,speed,homingStrength)
                    }
                }
                val length=velocity.length();val dir=velocity.clone().normalize()
                val wall=at.world.rayTraceBlocks(at,dir,length,FluidCollisionMode.NEVER,true)
                var distance=wall?.hitPosition?.distance(at.toVector()) ?: length+1
                var target:LivingEntity?=null
                val candidates:List<LivingEntity> = if(chickenMissile)at.world.getNearbyLivingEntities(at,length+4)
                    .filter { ChickenMissileTargets.canHit(player,it) } else session.enemies()
                candidates.forEach {p ->
                    val box=((p as? Player)?.disguise?.boundingBox ?: p.boundingBox).clone()
                        .expand(0.18*scale*(if(chickenMissile)ChickenMissileTargets.HITBOX_SCALE else 1.0))
                    val intersect=if(box.contains(at.toVector()))at.toVector() else box.rayTrace(at.toVector(),dir,length)?.hitPosition
                    if(intersect!=null && intersect.distance(at.toVector())<distance) {distance=intersect.distance(at.toVector());target=p}
                }
                val victim=target
                val impact=if(distance<=length)at.clone().add(dir.clone().multiply(distance)) else null
                if(victim!=null) {
                    if(chickenMissile) {
                        hit=missileBlast(impact!!);onImpact?.invoke(impact);return false
                    }
                    if(victim !is Player)return false
                    hit=true

                    if(explosion>0) {
                        val contacted=pulse(impact!!,explosion,damage,kb,power,terrain=terrain,hitInterval=hitInterval)
                        (contacted+victim).distinct().forEach {onHit?.invoke(it)}
                    } else {
                        if(damage>0 && session.hit(victim,damage,kb,source=power.spec.name,origin=at,hitInterval=hitInterval) && (blockData!=null || material in setOf(Material.ARROW,Material.COBWEB)))
                            dev.betrix.superSmashMobsBrawl.utils.HitFeedback.play(player,
                                power.spec.material in setOf(Material.BOW,Material.CROSSBOW))
                        onHit?.invoke(victim)
                    }
                    onImpact?.invoke(impact!!)
                    return false
                }
                if(wall!=null) {
                    if(bounce && !bounced) {
                        bounced=true
                        velocity.y=abs(velocity.y).coerceAtLeast(0.7)
                        at=wall.hitPosition.toLocation(at.world).add(0.0,0.35,0.0)
                    } else {
                        onImpact?.invoke(wall.hitPosition.toLocation(at.world))
                        if(explosion>0) {
                            if(chickenMissile)hit=missileBlast(wall.hitPosition.toLocation(at.world))
                            else {
                                val contacted=pulse(wall.hitPosition.toLocation(at.world),explosion,damage,kb,power,terrain=terrain,hitInterval=hitInterval)
                                hit=contacted.isNotEmpty()
                                contacted.forEach {onHit?.invoke(it)}
                            }
                        }
                        if(display is Arrow) {
                            dev.betrix.superSmashMobsBrawl.projectiles.ArrowLanding.stick(display,wall.hitPosition.toLocation(at.world),velocity)
                            if(hiddenFromShooter) {
                                player.showEntity(dev.betrix.superSmashMobsBrawl.items.ItemService.plugin,display)
                                hiddenFromShooter=false
                            }
                            landedTicks=0
                            return true
                        }
                        return false
                    }
                } else at.add(velocity)
                if(bounce)velocity.y-=0.045
                velocity.y-=gravity
                if(flightSound!=null && age%flightSoundInterval.coerceAtLeast(1)==0)at.world.playSound(at,flightSound,flightVolume,flightPitch)
                onTravel?.invoke(at,velocity)
                display.teleport(at)
                if(hiddenFromShooter && at.distanceSquared(player.eyeLocation)>2.25) {
                    player.showEntity(dev.betrix.superSmashMobsBrawl.items.ItemService.plugin,display);hiddenFromShooter=false
                }
                if(display is Arrow) {
                    // Native arrow meshes use atan2(x,z), not a flat inventory-item orientation.
                    val yaw=Math.toDegrees(atan2(velocity.x,velocity.z)).toFloat()
                    val pitch=Math.toDegrees(atan2(velocity.y,sqrt(velocity.x*velocity.x+velocity.z*velocity.z))).toFloat()
                    display.setRotation(yaw,pitch);display.velocity=Vector()
                    val handle=(display as org.bukkit.craftbukkit.entity.CraftArrow).handle
                    handle.yRotO=yaw;handle.xRotO=pitch
                }
                if(notes)at.world.spawnParticle(Particle.NOTE,at,0,(age%24)/24.0,0.0,0.0,1.0)
                if(power.spec.kit=="magma_cube")at.world.spawnParticle(Particle.FLAME,at,4,.1,.1,.1,.015)
                if(power.spec.key=="creeper:0" || material==Material.WITHER_SKELETON_SKULL)at.world.spawnParticle(Particle.SMOKE,at,4,.12,.12,.12,.015)
                if(display is BlockDisplay) {
                    // Rotation and movement use independent interpolation. Rotate around the
                    // block's centre, not its corner, and restart each transformation interval.
                    display.interpolationDelay=0
                    display.setTransformationMatrix(org.joml.Matrix4f().rotateY(age*.16f).rotateX(age*.12f)
                        .scale(scale.toFloat()).translate(-.5f,-.5f,-.5f))
                }
                if(material==Material.CHICKEN_SPAWN_EGG && creatureVisual && age%2==0) {
                    ClassicEffects.chickenMissileSmoke(at,velocity)
                }
                if(power.spec.key=="blaze:0")at.world.spawnParticle(Particle.FLAME,at,5,0.12,0.12,0.12,0.01)
                if(material==Material.ECHO_SHARD && age%3==0)at.world.spawnParticle(Particle.SONIC_BOOM,at,1)
                if(material in setOf(Material.INK_SAC,Material.REDSTONE,Material.LAPIS_LAZULI)) {
                    repeat(3) {dust(at.clone().subtract(velocity.clone().multiply(it/3.0)),trail,1,0.02)}
                }
                return true
            }
            override fun close() {
                if(hiddenFromShooter)player.showEntity(dev.betrix.superSmashMobsBrawl.items.ItemService.plugin,display)
                SmashVisuals.ids.remove(display.uniqueId);display.remove();onFinish?.invoke(hit)
            }
        })
    }
    fun fan(power:SmashPower,material:Material,count:Int,damage:Double,kb:Double=1.0,speed:Double=2.0,homing:Double=0.0,scale:Double=1.0,onHit:((Player)->Unit)?=null,creatureVisual:Boolean=true,trail:SmashTier=power.spec.tier) {
        repeat(count) { val direction=if(it==0)player.eyeLocation.direction else spread(player.eyeLocation.direction,.15)
            shot(power,material,damage,kb,speed,scale,homing,direction=direction,onHit=onHit,creatureVisual=creatureVisual) }
    }
    fun loop(ticks:Int,every:Int=1,finish:()->Unit={},body:(Int)->Unit) {
        arena.effect(object:SmashEffect {var age=0
            override fun step():Boolean {if(!session.alive || age>=ticks)return false;if(age%every==0)body(age);age++;return true}
            override fun close() {finish()}
        })
    }
    fun mob(power:SmashPower,type:EntityType,at:Location=player.location,seconds:Int=20,baby:Boolean=false,ranged:Boolean=false,damage:Double=4.0,scale:Double=1.0,target:Player?=null,launchVelocity:Vector?=null):LivingEntity? {
        if(arena.entities.count {it is LivingEntity}>=50)return null
        val mob=at.world.spawnEntity(at,type) as? LivingEntity ?: return null
        mob.isPersistent=false;mob.isCollidable=false;mob.isSilent=true
        if(mob is Zombie)mob.setShouldBurnInDay(false)
        if(mob is Ageable) { if(baby)mob.setBaby() else mob.setAdult() }
        mob.getAttribute(org.bukkit.attribute.Attribute.SCALE)?.baseValue=scale
        mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.baseValue=10.0;mob.health=10.0
        if(type==EntityType.GIANT){mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.baseValue=40.0;mob.health=40.0}
        // Keep gravity/ground movement, but replace autonomous goals with our targeting.
        mob.setAI(true)
        if(mob is Mob)Bukkit.getMobGoals().removeAllGoals(mob)
        if(mob is Wolf)mob.variant=Wolf.Variant.PALE
        if(mob is Cow)mob.variant=Cow.Variant.TEMPERATE
        if(mob is Pig)mob.variant=Pig.Variant.TEMPERATE
        if(mob is Chicken)mob.variant=Chicken.Variant.TEMPERATE
        if(type==EntityType.ELDER_GUARDIAN) {mob.setGravity(false);mob.isInvulnerable=true}
        if(ranged)mob.equipment?.setItemInMainHand(ItemStack.of(Material.BOW))
        arena.track(mob);power.summons+=mob
        dev.betrix.superSmashMobsBrawl.abilities.ClassicSummons.owners[mob.uniqueId]=player
        SmashSummons.owners[mob.uniqueId]=session
        if(type==EntityType.SNOW_GOLEM)mob.isCustomNameVisible=true
        if(launchVelocity!=null) {mob.setGravity(false);mob.velocity=Vector()}
        arena.effect(object:SmashEffect {var age=0
            var flight=launchVelocity?.clone()
            var flightAt=at.clone()
            override fun step():Boolean {
                if(!session.alive || !mob.isValid || (seconds>0 && age>=seconds*20))return false
                age++
                if(type==EntityType.ZOMBIE || type==EntityType.GIANT)mob.fireTicks=0
                if(type==EntityType.GIANT && age%5==0) {
                    // Clear enough space for the actual giant body, keeping its supporting floor.
                    val forward=mob.location.direction.setY(0.0).normalize()
                    val box=mob.boundingBox.clone().shift(forward.multiply(1.0)).expand(.15,0.0,.15)
                    var broken=0
                    for(y in floor(box.minY).toInt()..floor(box.maxY).toInt())
                        for(x in floor(box.minX).toInt()..floor(box.maxX).toInt())
                            for(z in floor(box.minZ).toInt()..floor(box.maxZ).toInt()) {
                                val block=mob.world.getBlockAt(x,y,z)
                                if(broken<24 && !block.isEmpty && block.type !in setOf(Material.BARRIER,Material.BEDROCK,Material.COMMAND_BLOCK)
                                    && !arena.protectsCrystal(block)) {
                                    arena.changeBlock(block,Material.AIR);broken++
                                }
                            }
                }
                if(type==EntityType.ELDER_GUARDIAN)return true
                flight?.let {velocity ->
                    val contact=smashCollision(session,flightAt,velocity,.35)
                    if(contact!=null || age>=40) {
                        if(contact!=null) {
                            flightAt=contact.first
                            contact.second?.let {session.hit(it,damage,1.2,source=power.spec.name,origin=flightAt)}
                            mob.teleport(flightAt.clone().subtract(velocity.clone().normalize().multiply(.2)))
                        }
                        flight=null;mob.setGravity(true);mob.velocity=Vector()
                    } else {
                        flightAt.add(velocity);flightAt.direction=velocity
                        mob.teleport(flightAt);velocity.y-=.08
                        return true
                    }
                }
                if(type==EntityType.SNOW_GOLEM && age%2==0) {
                    val left=(seconds*20-age).coerceAtLeast(0)
                    val bars=(left*20/(seconds*20)).coerceIn(0,20)
                    mob.customName(net.kyori.adventure.text.Component.text("▌".repeat(bars),net.kyori.adventure.text.format.NamedTextColor.GREEN)
                        .append(net.kyori.adventure.text.Component.text("▌".repeat(20-bars),net.kyori.adventure.text.format.NamedTextColor.RED))
                        .append(net.kyori.adventure.text.Component.text(" ${"%.1f".format(java.util.Locale.ROOT,left/20.0)}s",net.kyori.adventure.text.format.NamedTextColor.WHITE)))
                }
                val enemy=target?.takeIf {it in session.enemies()} ?: session.enemies().minByOrNull {it.location.distanceSquared(mob.location)} ?: return true
                val delta=enemy.location.toVector().subtract(mob.location.toVector())
                val stationary=type==EntityType.SNOW_GOLEM
                val aim=SmashTargeting.center(enemy).subtract(mob.eyeLocation.toVector())
                if(aim.lengthSquared()>.001) {
                    val facing=mob.location.setDirection(aim)
                    mob.setRotation(facing.yaw,facing.pitch)
                    val handle=(mob as org.bukkit.craftbukkit.entity.CraftLivingEntity).handle
                    handle.yHeadRot=facing.yaw;handle.yBodyRot=facing.yaw
                }
                if(!stationary) {
                    val move=delta.clone().setY(0.0)
                    if(move.lengthSquared()>0.1) {
                        move.normalize().multiply(if(type==EntityType.CHICKEN)0.30 else 0.23)
                        if(!mob.location.clone().add(move.clone().multiply(3)).block.isPassable && mob.isOnGround)move.y=0.45
                        else move.y=mob.velocity.y
                        mob.velocity=move
                    }
                }
                if(mob is Sheep && age%5==0)mob.color=DyeColor.entries[(age/5)%DyeColor.entries.size]
                if(ranged || stationary) {
                    val every=if(stationary)if(power.spec.tier==SmashTier.ULTRA)2 else 4 else 20
                    if(age%every==0)repeat(if(stationary && power.spec.tier==SmashTier.ULTRA)3 else 1) {
                        shot(power,if(stationary)Material.SNOWBALL else Material.ARROW,damage,1.0,2.3,
                            homing=if(stationary)32.0 else 0.0,
                            origin=mob.eyeLocation,direction=aim.clone().normalize().rotateAroundY(if(stationary && power.spec.tier==SmashTier.ULTRA)(it-1)*0.08 else 0.0),
                            homingStrength=if(power.spec.tier==SmashTier.ULTRA).55 else .12)
                    }
                } else if(damage>0 && delta.lengthSquared()<(if(type==EntityType.GIANT)25.0 else 3.0)) {
                    session.hit(enemy,damage,if(type==EntityType.SHEEP)2.5 else 0.8,source=power.spec.name,origin=mob.location)
                    if(type==EntityType.SHEEP)return false
                }
                return true
            }
            override fun close() {
                if(type==EntityType.SNOW_GOLEM && mob.isValid)
                    mob.world.spawnParticle(Particle.CLOUD,mob.location.add(0.0,.8,0.0),28,.4,.6,.4,.04)
                power.summons.remove(mob);SmashSummons.owners.remove(mob.uniqueId)
                dev.betrix.superSmashMobsBrawl.abilities.ClassicSummons.owners.remove(mob.uniqueId);mob.remove()
            }
        })
        return mob
    }
    fun line(power:SmashPower,length:Double,damage:Double,radius:Double=0.8,pull:Boolean=false) {
        val from=player.eyeLocation;val dir=from.direction
        val block=from.world.rayTraceBlocks(from,dir,length,FluidCollisionMode.NEVER,true)
        val end=block?.hitPosition?.distance(from.toVector()) ?: length
        var d=0.0
        while(d<end) {
            val at=from.clone().add(dir.clone().multiply(d));dust(at,power.spec.tier,3,radius*0.25)
            session.enemies().filter {damage>0 && it.eyeLocation.distanceSquared(at)<radius*radius+1}.forEach {session.hit(it,damage,1.2,source=power.spec.name)}
            d+=0.35
        }
    }
}

object SmashSummons {val owners=mutableMapOf<java.util.UUID,SmashSession>()}
object SmashVisuals {val ids=mutableSetOf<java.util.UUID>()}
