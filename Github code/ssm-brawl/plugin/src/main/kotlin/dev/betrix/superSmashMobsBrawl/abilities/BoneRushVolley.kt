package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.events.*
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.projectiles.*
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import kotlin.random.Random

/** One broad, physical bone field shared by normal and upgraded Rush, with identical hit protection. */
object BoneRushVolley {
    fun emit(player:Player,game:BrawlMinigame,damage:Double,source:String,boost:Double=1.0,count:Int=4):List<BrawlProjectile> {
        player.world.playSound(player.location,Sound.ENTITY_SKELETON_HURT,.4f,1f+Random.nextFloat())
        return List(count) {
            val velocity=player.eyeLocation.direction.multiply(Random.nextDouble(.6,.9)*boost)
            velocity.y=velocity.y.coerceAtLeast(.3)
            val spawn=player.location.add(Random.nextDouble(-2.5,2.5),Random.nextDouble(0.0,3.0),Random.nextDouble(-2.5,2.5))
            BrawlProjectile.custom(player,source) {
                player.world.dropItem(spawn,ItemStack.of(Material.BONE)) {
                    it.pickupDelay=Int.MAX_VALUE;it.setCanMobPickup(false);it.setCanPlayerPickup(false)
                }
            }.setInitialVelocity {it.velocity=velocity}.projectileSize(.3*boost).maxLifetime(40)
                .onHitEntity {target,_ ->
                    if(target is Player && target!=player && game.allPlayers().any {it.player==target} && !game.arePlayersOnSameTeam(player,target)) {
                        val event=BrawlDamageEvent(target,Damager.DamagerLivingEntity(player),damage,
                            damageType=BrawlDamageType.Projectile,sourceName=source,hitCooldownTicks=0,hitSound=true)
                        event.callEvent()
                        if(event.appliedDamage>0 && !game.respawnManager.isRespawning(target) && target.gameMode==GameMode.SURVIVAL)
                            target.velocity=velocity.clone().multiply(.25).setY(-.3).multiply(boost)
                    }
                    ProjectileAction.DESTROY
                }
                // Native dropped-item physics settle/bounce on terrain. Contact is not expiry.
                .onHitBlock {_,_->ProjectileAction.CONTINUE}
                .onIdle {ProjectileAction.CONTINUE}.launch()
        }
    }
}
