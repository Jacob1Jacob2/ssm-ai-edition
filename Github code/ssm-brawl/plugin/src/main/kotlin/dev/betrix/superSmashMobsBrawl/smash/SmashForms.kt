package dev.betrix.superSmashMobsBrawl.smash

import org.bukkit.*
import dev.betrix.superSmashMobsBrawl.extensions.disguise
import org.bukkit.entity.*
import org.bukkit.util.Vector
import org.bukkit.event.player.PlayerTeleportEvent
import io.papermc.paper.entity.TeleportFlag
import kotlin.math.*

class SmashForms(val s:SmashSession) {
    private val player get()=s.player
    private val arena get()=s.arena
    private val c get()=s.combat
    private var dragon:EnderDragon?=null
    private var dragonDirection=Vector(0.0,0.0,1.0)
    fun begin(p:SmashPower) {
        if(p.spec.key in setOf("iron_golem:2","iron_golem:3"))s.moves.upgrades.earthquake(p)
        if(p.spec.key=="squid:3")arena.startStorm(player)
        if(p.spec.key=="slime:3")player.world.playSound(player.location,Sound.ENTITY_SLIME_JUMP,1.4f,.5f)
        if(p.spec.key=="enderman:3") {
            dragon=player.world.spawn(player.location.add(0.0,2.0,0.0),EnderDragon::class.java) {
                // NoAI freezes the client's wing animation. HOVER keeps native animation
                // while disabling autonomous dragon navigation; our steering owns movement.
                it.setAI(true);it.phase=EnderDragon.Phase.HOVER
                it.isInvulnerable=true;it.isSilent=true;it.isPersistent=false
            }.also {arena.track(it);SmashVisuals.ids+=it.uniqueId;it.addPassenger(player)}
            dragonDirection=player.eyeLocation.direction.clone()
            player.world.playSound(player.location,Sound.ENTITY_ENDER_DRAGON_GROWL,1.5f,.8f)
        }
    }
    fun end(p:SmashPower) {
        if(p.spec.key=="squid:3")arena.endStorm(player)
        if(p.spec.key in setOf("iron_golem:2","iron_golem:3"))arena.players().forEach {it.stopSound(Sound.ENTITY_MINECART_RIDING)}
        if(p.spec.key=="enderman:3") {dragon?.let {SmashVisuals.ids.remove(it.uniqueId);it.eject();it.remove()};dragon=null}
        if(p.spec.key in setOf("squid:3","witch:3","wither_skeleton:2","wither_skeleton:3"))player.isFlying=false
    }
    fun tick(p:SmashPower) {
        when(p.spec.key) {
            "skeleton:1","skeleton:3" -> {
                repeat(if(p.spec.tier==SmashTier.ULTRA)3 else 1) {i->
                    c.shot(p,Material.ARROW,6.0,1.0,3.2,if(p.spec.tier==SmashTier.ULTRA)2.0 else 1.0,
                        if(p.spec.tier==SmashTier.ULTRA)36.0 else 0.0,
                        direction=c.spread(player.eyeLocation.direction,if(p.spec.tier==SmashTier.ULTRA).15 else .12))
                }
            }
            "iron_golem:2","iron_golem:3" -> {
                if(p.age%10==0)s.moves.upgrades.earthquake(p)
            }
            "slime:3" -> {
                SmashLooks.slimeSize(player,12)
                val body=(player.disguise?.boundingBox
                    ?: player.boundingBox).clone().expand(.4)
                s.enemies().filter {body.overlaps(it.boundingBox)}.forEach {s.hit(it,8.0,3.5,source=p.spec.name)}
                if(p.age%4==0)player.world.spawnParticle(Particle.ITEM_SLIME,player.location,24,2.5,.15,2.5,.05)
                if(player.isOnGround && p.charged==1)player.world.playSound(player.location,Sound.ENTITY_SLIME_SQUISH,1.5f,.5f)
                p.charged=if(player.isOnGround)0 else 1
            }
            "squid:3","witch:3","wither_skeleton:2","wither_skeleton:3" -> {
                player.allowFlight=true;player.isFlying=true;player.fallDistance=0f
                if(p.spec.key=="wither_skeleton:3")SmashLooks.armoredWither(player)
                if(p.spec.key=="squid:3" && s.holding(p))click(p)
            }
            "enderman:3" -> {
                dragon?.takeIf {it.isValid}?.let {d ->
                    dragonDirection=dragonDirection.multiply(0.65).add(player.eyeLocation.direction.multiply(0.35)).normalize()
                    val at=d.location.add(dragonDirection.clone().multiply(1.1))
                    // A teleport rotation also rotates every passenger, feeding the dragon's
                    // turn back into the player's aim. Move first, then rotate only the dragon.
                    d.teleport(at,PlayerTeleportEvent.TeleportCause.PLUGIN,TeleportFlag.EntityState.RETAIN_PASSENGERS)
                    val facing=at.clone().setDirection(dragonDirection.clone().multiply(-1))
                    d.setRotation(facing.yaw,facing.pitch)
                    d.phase=EnderDragon.Phase.HOVER
                    if(p.age%8==0)player.world.playSound(at,Sound.ENTITY_ENDER_DRAGON_FLAP,1.4f,.9f)
                    if(p.age%60==0)player.world.playSound(at,Sound.ENTITY_ENDER_DRAGON_GROWL,1.5f,.8f)
                    if(p.age%4==0){c.pulse(at,6.0,10.0,4.0,p,sound=false);arena.destroy(at,4.0)}
                }
            }
            "cow:3" -> s.moves.farm.milkDome(p)
            "blaze:3" -> {
                player.velocity=player.eyeLocation.direction.multiply(2.0);player.fallDistance=0f
                s.enemies().filter {it.location.distanceSquared(player.location)<9}.forEach {s.hit(it,9.0,2.0,source=p.spec.name)}
                dev.betrix.superSmashMobsBrawl.abilities.FireflyAbility.flightEffects(player)
            }
            "chicken:0" -> {player.exp=1f;s.moves.elemental.egg()}
            "chicken:1" -> {
                player.exp=1f
                // Integer accumulation gives exactly 45 scheduled missiles in 15 seconds.
                p.counter+=3
                if(p.counter>=20) {p.counter-=20;s.moves.elemental.missile(p)}
            }
        }
    }
    private fun missile(p:SmashPower,bonus:Boolean=false) {
        c.shot(p,Material.CHICKEN_SPAWN_EGG,8.0,1.5,1.6,1.1,explosion=3.0,onHit={
            // Bonus missiles can chain on hits, but only while this power is active.
            if(s.has(p.spec.key) && !p.expired(arena.tick))missile(p,true)
        })
    }
    fun click(p:SmashPower):Boolean {
        when(p.spec.key) {
            "squid:3" -> {
                if(arena.tick<p.nextUse)return false;p.nextUse=arena.tick+10
                val at=player.world.rayTraceBlocks(player.eyeLocation,player.eyeLocation.direction,70.0,FluidCollisionMode.NEVER,true)?.hitPosition?.toLocation(player.world)
                    ?: player.eyeLocation.add(player.eyeLocation.direction.multiply(30))
                c.loop(12,finish={if(s.alive){at.world.strikeLightningEffect(at);c.pulse(at,6.0,12.0,2.0,p)}}) {}
            }
            "witch:3" -> {if(arena.tick<p.nextUse)return false;p.nextUse=arena.tick+10;s.moves.witch.sonic(p)}
            "wither_skeleton:2","wither_skeleton:3" -> {
                if(arena.tick<p.nextUse)return false;p.nextUse=arena.tick+10
                val ultra=p.spec.tier==SmashTier.ULTRA
                repeat(if(ultra)2 else 1) {i->c.shot(p,Material.WITHER_SKELETON_SKULL,10.0,2.0,1.6,1.0,
                    if(ultra)20.0 else 0.0,4.0,if(ultra)4.0 else 2.0,direction=player.eyeLocation.direction.clone().rotateAroundY(if(ultra)(i-0.5)*0.15 else 0.0))}
            }
            else -> return false
        }
        return false // Form cadence is managed here, not the normal tool cooldown.
    }
}
