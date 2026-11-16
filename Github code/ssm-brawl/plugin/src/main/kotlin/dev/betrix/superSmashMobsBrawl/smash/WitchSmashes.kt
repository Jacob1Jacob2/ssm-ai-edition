package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.abilities.BatWaveAbility
import dev.betrix.superSmashMobsBrawl.projectiles.BrawlProjectile
import dev.betrix.superSmashMobsBrawl.projectiles.ProjectileAction
import dev.betrix.superSmashMobsBrawl.services.KitService
import org.bukkit.*
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.*
import org.bukkit.util.Vector
import kotlin.math.*

class WitchSmashes(private val s:SmashSession) {
    private val player get()=s.player
    private val arena get()=s.arena
    private val c get()=s.combat
    private val wave get()=KitService.getKitForPlayer(player)?.getAbility("bat_wave") as? BatWaveAbility

    fun bats(p:SmashPower) {
        val ability=wave ?: return
        if(ability.waveActive) {ability.toggleLeash();return}
        if(p.uses<=0)return
        ability.launchSmashWave(p.spec.name)
        p.active=true;p.uses--;s.refreshTool(p)
    }
    fun tick(p:SmashPower) {p.active=wave?.waveActive==true}
    fun end() {wave?.stopWave()}

    fun potion(p:SmashPower) {
        val (type,effect)=listOf(PotionType.WEAKNESS to PotionEffectType.WEAKNESS,
            PotionType.SLOWNESS to PotionEffectType.SLOWNESS,PotionType.POISON to PotionEffectType.POISON,
            PotionType.WATER to PotionEffectType.BLINDNESS,PotionType.HARMING to PotionEffectType.INSTANT_DAMAGE).random()
        val color=effect.color
        val bottle=ItemStack.of(if(p.spec.tier==SmashTier.LIGHT)Material.SPLASH_POTION else Material.LINGERING_POTION).apply {
            editMeta {if(it is PotionMeta){it.basePotionType=type;it.color=color}}
        }
        var splashed=false
        fun splash(at:Location?,direct:LivingEntity?) {
            if(at==null || splashed || !s.alive)return
            splashed=true
            at.world.playSound(at,Sound.ENTITY_SPLASH_POTION_BREAK,1f,1f)
            at.world.spawnParticle(Particle.ENTITY_EFFECT,at,30,.4,.2,.4,0.0,color)
            s.enemies().filter {it.location.distanceSquared(at)<9}.forEach {
                s.hit(it,(if(it==direct)7.0 else 6.0)*1.2,2.0,source=p.spec.name,origin=at)
                if(effect!=PotionEffectType.INSTANT_DAMAGE)it.addPotionEffect(PotionEffect(effect,80,0))
            }
            if(p.spec.tier==SmashTier.REGULAR)c.loop(100,10) {
                at.world.spawnParticle(Particle.ENTITY_EFFECT,at,25,1.5,.1,1.5,0.0,color)
                s.enemies().filter {it.location.distanceSquared(at)<9}.forEach {
                    if(effect!=PotionEffectType.INSTANT_DAMAGE)it.addPotionEffect(PotionEffect(effect,40,0))
                    s.hit(it,1.0,0.0,source=p.spec.name,origin=at)
                }
            }
        }
        // Identical native projectile, initial lift, gravity and collision/lifetime to Daze Potion.
        val shot=BrawlProjectile.potion(player,bottle,p.spec.name)
            .setInitialVelocity {it.velocity=player.eyeLocation.direction.add(Vector(0.0,.2,0.0))}
            .projectileSize(.25).maxLifetime(200).enableIdleDetection(false)
            .onTick {it.projectileEntity?.location?.let {at -> at.world.spawnParticle(Particle.ENTITY_EFFECT,at,1,0.0,0.0,0.0,0.0,color)}}
            .onHitEntity {target,projectile -> splash(projectile.projectileEntity?.location,target);ProjectileAction.DESTROY}
            .onHitBlock {_,projectile -> splash(projectile.projectileEntity?.location,null);ProjectileAction.DESTROY}
            .onExpire {projectile -> splash(projectile.projectileEntity?.location,null);ProjectileAction.DESTROY}
            .launch()
        player.world.playSound(player.location,Sound.ENTITY_WITCH_THROW,.7f,1f)
        arena.effect(object:SmashEffect {
            override fun step()=s.alive && shot.projectileEntity?.isValid==true
            override fun close() {shot.teardown()}
        })
    }

    /** Bat Form's traveling pressure wave stops on the first opponent or wall. No block edits. */
    fun sonic(p:SmashPower) {
        val direction=player.eyeLocation.direction.normalize()
        val side=direction.clone().crossProduct(Vector(0,1,0)).let {if(it.lengthSquared()<.001)Vector(1,0,0) else it.normalize()}
        val up=side.clone().crossProduct(direction).normalize()
        player.world.playSound(player.location,Sound.ENTITY_BAT_TAKEOFF,1.2f,.7f)
        player.world.playSound(player.location,Sound.ENTITY_BAT_HURT,.7f,.6f)
        arena.effect(object:SmashEffect {
            var at=player.eyeLocation.clone()
            var age=0
            override fun step():Boolean {
                if(!s.alive || age++>=60)return false
                val collision=smashCollision(s,at,direction,.5)
                if(collision!=null) {
                    val impact=collision.first
                    impact.world.spawnParticle(Particle.EXPLOSION,impact,3,.6,.6,.6,0.0)
                    impact.world.spawnParticle(Particle.CLOUD,impact,35,.8,.8,.8,.1)
                    impact.world.playSound(impact,Sound.ENTITY_GENERIC_EXPLODE,1f,1.5f)
                    s.enemies().filter {it==collision.second || it.location.distanceSquared(impact)<=25}.forEach {
                        s.hit(it,14.0,2.5,source="Sonic Boom",origin=impact)
                    }
                    return false
                }
                at.add(direction)
                val radius=.35+min(age*.025,.65)
                repeat(16) {i ->
                    val angle=i*2*PI/16
                    val point=at.clone().add(side.clone().multiply(cos(angle)*radius)).add(up.clone().multiply(sin(angle)*radius))
                    point.world.spawnParticle(Particle.CLOUD,point,1,0.0,0.0,0.0,0.0)
                }
                return true
            }
            override fun close() {}
        })
    }
}
