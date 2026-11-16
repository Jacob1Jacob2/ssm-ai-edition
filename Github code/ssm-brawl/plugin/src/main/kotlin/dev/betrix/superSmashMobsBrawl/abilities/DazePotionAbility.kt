package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.projectiles.BrawlProjectile
import dev.betrix.superSmashMobsBrawl.projectiles.ProjectileAction
import org.bukkit.*
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.potion.PotionType
import org.bukkit.util.Vector

/** Classic water potion: shatters on the FIRST impact, stronger on a direct hit. */
class DazePotionAbility(player:Player):ClassicKitAbility("daze_potion",player) {
    private val projectiles=mutableSetOf<BrawlProjectile>()
    override fun activate() {
        super.activate()
        val water=ItemStack.of(Material.POTION).apply {
            editMeta {if(it is PotionMeta)it.basePotionType=PotionType.WATER}
        }
        var splashed=false
        fun splash(at:Location?,direct:LivingEntity?) {
            if(at==null || splashed)return
            splashed=true
            at.world.playSound(at,Sound.ENTITY_SPLASH_POTION_BREAK,1f,1f)
            at.world.spawnParticle(Particle.ENTITY_EFFECT,at,30,0.4,0.2,0.4,0.0,Color.fromRGB(0x385DC6))
            targets(at,upgradedValue("effectRadius", 3.0)).forEach {target ->
                val isDirect=target==direct
                if(hit(target,if(isDirect)7.0 else 6.0,2.0,at)) {
                    if (isDirect && target is Player) player.playSound(player.location,Sound.ENTITY_ARROW_HIT_PLAYER,0.5f,1f)
                    target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS,40,if(isDirect)1 else 0,false,true))
                }
            }
        }
        val projectile=BrawlProjectile.potion(player,water,"abilities.daze_potion.name")
            .setInitialVelocity {it.velocity=player.eyeLocation.direction.add(Vector(0.0,0.2,0.0))}
            .projectileSize(0.25).maxLifetime(200).enableIdleDetection(false)
            .onTick {shot -> shot.projectileEntity?.location?.let {at ->
                Particle.ENTITY_EFFECT.builder().location(at).color(Color.fromRGB(0x385DC6))
                    .count(1).extra(0.0).receivers(96,true).spawn()
            }}
            .onHitEntity {target,shot ->splash(shot.projectileEntity?.location,target);ProjectileAction.DESTROY}
            .onHitBlock {_,shot ->splash(shot.projectileEntity?.location,null);ProjectileAction.DESTROY}
            .onExpire {shot ->splash(shot.projectileEntity?.location,null);ProjectileAction.DESTROY}
            .onTeardown {projectiles.remove(it)}.upgraded().launch()
        projectiles+=projectile
    }
    override fun teardown() {projectiles.toList().forEach {it.teardown()};projectiles.clear();super.teardown()}
}
