package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.projectiles.BrawlProjectile
import dev.betrix.superSmashMobsBrawl.projectiles.ProjectileAction
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import kotlin.math.*
import kotlin.random.Random

/** The undead upgrades retain the base kit's controls, trajectories and cues. */
class UndeadSmashes(private val s:SmashSession) {
    private val player get()=s.player
    private val arena get()=s.arena
    private val c get()=s.combat
    fun images(p:SmashPower):Boolean {
        p.summons.removeIf {!it.isValid}
        if(p.summons.isNotEmpty()) {
            val positions=listOf(player.location.clone())+p.summons.map {it.location.clone()}
            if(!positions[1].block.isPassable || !positions[1].clone().add(0.0,1.0,0.0).block.isPassable)return false
            player.teleport(positions[1]);player.fallDistance=0f
            p.summons.forEachIndexed {i,m -> m.teleport(positions[(i+2)%positions.size])}
            player.world.playSound(player.location,Sound.ENTITY_WITHER_SPAWN,1f,2f)
            p.nextUse=arena.tick+40
            return false
        }
        if(p.uses<=0)return false
        player.world.playSound(player.location,Sound.ENTITY_WITHER_SPAWN,1f,1f)
        repeat(3) {i ->
            c.mob(p,EntityType.WITHER_SKELETON,player.location.clone().add(0.0,.2,0.0),seconds=-1,damage=4.0,
                launchVelocity=player.eyeLocation.direction.rotateAroundY((i-1)*.3).multiply(2.4))?.let {
                it.equipment?.setItemInMainHand(ItemStack.of(Material.IRON_AXE))
            }
        }
        return true
    }
    fun tick(p:SmashPower) {
        if(p.spec.key=="zombie:1" && p.active) {
            if(!s.holding(p) || p.uses<=0) {p.active=false;return}
            val count=s.moves.base(p,"projectilesPerTick",3.0).toInt().coerceAtLeast(1)
            repeat(minOf(count,p.uses)) {bile(p);p.uses--}
            s.refreshTool(p)
            if(p.age%5==0)player.world.playSound(player.location,Sound.ENTITY_PLAYER_BURP,1f,Random.nextDouble(.5,1.5).toFloat())
        }
        if(p.spec.key in setOf("zombie:2","zombie:3") && p.age%10==1) {
            val at=spawnPoint(p) ?: return
            val giant=p.spec.tier==SmashTier.ULTRA
            val remaining=(p.spec.seconds-(arena.tick-p.started)/20).toInt().coerceAtLeast(1)
            c.mob(p,if(giant)EntityType.GIANT else EntityType.ZOMBIE,at,remaining,damage=if(giant)6.0 else 3.0)
            at.world.spawnParticle(Particle.SMOKE,at,12,.5,.2,.5,.03)
            at.world.playSound(at,Sound.ENTITY_ZOMBIE_AMBIENT,.8f,if(giant).5f else 1f)
        }
    }
    private fun bile(p:SmashPower) {
        val spread=s.moves.base(p,"projectileSpread",.525)/2
        val direction=c.spread(player.eyeLocation.direction,spread)
        val speed=s.moves.base(p,"projectileBaseVelocity",.8)+Random.nextDouble()*s.moves.base(p,"projectileVelocityRandomness",.2)
        val projectile=BrawlProjectile.potion(player,ItemStack.of(Material.REDSTONE),p.spec.name)
            .setInitialVelocity {it.velocity=direction.multiply(speed)}
            .projectileSize(s.moves.base(p,"projectileSize",.5)*1.2).maxLifetime(80)
            .onHitEntity {target,shot ->
                if(target is Player && s.hit(target,s.moves.base(p,"projectileDamage",3.0)*1.2,
                    s.moves.base(p,"projectileKnockbackModifier",.5),source=p.spec.name,origin=shot.projectileEntity?.location ?: player.location,hitInterval=5)) {
                    dev.betrix.superSmashMobsBrawl.utils.HitFeedback.play(player)
                    player.health=(player.health+1.2).coerceAtMost(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value)
                }
                ProjectileAction.DESTROY
            }.onHitBlock {_,_->ProjectileAction.DESTROY}.launch()
        projectile.projectileEntity?.let {arena.track(it)}
        c.loop(82,finish={projectile.teardown()}){}
    }
    fun apocalypse(p:SmashPower) {
        arena.players().forEach {it.setPlayerTime(18000,false)}
    }
    private fun spawnPoint(p:SmashPower):Location? {
        val opponents=s.enemies()
        val points=arena.game.worldManager.getWorld()!!.data.spawnPoints
        val n=p.counter++
        // Alternate opponent neighborhoods and the map's distributed spawn islands.
        val center=if(n%2==0 && opponents.isNotEmpty())opponents[(n/2)%opponents.size].location
            else if(points.isNotEmpty())points[(n/2)%points.size].let {Location(arena.world,it.x,it.y,it.z)}
            else opponents.getOrNull((n/2)%maxOf(1,opponents.size))?.location ?: player.location
        repeat(24) {
            val angle=Random.nextDouble()*2*PI
            val radius=if(n%2==0)Random.nextDouble(3.0,7.0) else Random.nextDouble(0.0,6.0)
            val x=floor(center.x+cos(angle)*radius).toInt();val z=floor(center.z+sin(angle)*radius).toInt()
            // Find the local island floor, not a roof or a different island far overhead.
            for(y in (center.blockY+4).coerceAtMost(arena.world.maxHeight-3) downTo (center.blockY-24).coerceAtLeast(arena.world.minHeight)) {
                val ground=arena.world.getBlockAt(x,y,z)
                if(ground.type.isSolid && ground.type!=Material.BARRIER) {
                    val at=ground.location.add(.5,1.0,.5)
                    if(at.block.isPassable && at.clone().add(0.0,1.0,0.0).block.isPassable)return at
                }
            }
        }
        return null
    }
    fun end(p:SmashPower) {
        if(p.spec.key !in setOf("zombie:2","zombie:3","wither_skeleton:1"))return
        p.summons.toList().forEach {it.remove()};p.summons.clear()
        if(p.spec.kit=="zombie")arena.game.allPlayers().mapNotNull{it.player}.forEach {it.resetPlayerTime()}
    }
}
