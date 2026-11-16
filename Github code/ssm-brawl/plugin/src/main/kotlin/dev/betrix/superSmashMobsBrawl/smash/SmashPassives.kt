package dev.betrix.superSmashMobsBrawl.smash

import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.potion.*
import org.bukkit.util.Vector
import kotlin.math.*

class SmashPassives(val s:SmashSession) {
    private val player get()=s.player
    private val arena get()=s.arena
    private val c get()=s.combat
    fun tick(p:SmashPower) {
        when(p.spec.key) {
            "spider:2" -> if(player.isSneaking && s.consumeFuel(p)) {
                player.velocity=player.eyeLocation.direction.multiply(.9)
                player.fallDistance=0f
                player.world.spawnParticle(Particle.SMOKE,player.location.add(0.0,.15,0.0),2,.15,.08,.15,.005)
                if(p.age%6==0)player.world.playSound(player.location,Sound.ENTITY_SPIDER_STEP,.4f,1.4f)
            }
            "enderman:2" -> {
                val seen=s.enemies().any {enemy ->
                    val toward=player.eyeLocation.toVector().subtract(enemy.eyeLocation.toVector())
                    toward.lengthSquared()<900 && toward.normalize().dot(enemy.eyeLocation.direction)>0.992 && enemy.hasLineOfSight(player)
                }
                if(seen && !s.rage){s.rage=true;player.world.playSound(player.location,Sound.ENTITY_ENDERMAN_SCREAM,1f,1f)}
                if(s.rage)s.rageSpeed(true)
            }
            "wolf:3" -> {s.frenzySpeed(true);s.potion(PotionEffectType.REGENERATION,2);s.potion(PotionEffectType.STRENGTH,2)}
            "magma_cube:3" -> s.moves.wolfMagma.storm(p)
            "horse:1" -> if(p.age%40==0)s.moves.farm.trail(p)
            "horse:3" -> if(p.age%2==0)s.moves.farm.rain(p)
            "pig:3" -> s.moves.farm.pigmanTick(p)
            "villager:1" -> player.walkSpeed=if(s.has("villager:3"))0.39f else 0.26f
            "villager:3" -> {player.walkSpeed=if(s.has("villager:1"))0.39f else 0.3f;s.potion(PotionEffectType.REGENERATION,0)}
        }
    }
}
