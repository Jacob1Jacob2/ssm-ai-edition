package dev.betrix.superSmashMobsBrawl.abilities

import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import kotlin.random.Random

/** Aim at a surface: a one-second water warning, then a three-second geyser of fish. */
class FishFlurryAbility(player: Player) : ClassicKitAbility("fish_flurry", player) {
    private fun target()=player.world.rayTraceBlocks(player.eyeLocation,player.eyeLocation.direction,64.0,
        FluidCollisionMode.NEVER,true)?.hitBlock
    override fun canActivate(sendMessage:Boolean)=target()!=null && super.canActivate(sendMessage)
    override fun activate() {
        val block=target() ?: return
        val scale = dev.betrix.superSmashMobsBrawl.items.ItemService.nextCastScale(player, id)
        val reach = kotlin.math.ceil(3.5 * scale).toInt()
        val points=buildList {
            for(x in -reach..reach)for(z in -reach..reach)for(y in -3..3) {
                if(x*x+z*z>12.25 * scale * scale)continue
                val floor=block.getRelative(x,y,z)
                if(floor.type.isSolid && floor.getRelative(0,1,0).isPassable)
                    add(floor.location.add(0.5,1.05,0.5))
            }
        }
        if(points.isEmpty())return
        super.activate()
        loop {age ->
            if(age>=80)return@loop false
            if(age%3==0) {
                points.forEach {player.world.spawnParticle(Particle.SPLASH,it,1,0.15,0.0,0.15,0.0)}
                player.world.playSound(points.random(),Sound.ENTITY_FISHING_BOBBER_SPLASH,0.5f,1f)
            }
            if(age>=20)launchFish(points.random().clone())
            true
        }
    }
    private fun launchFish(start:Location) {
        var at=start
        // Item entities have their own bobbing/physics and fight per-tick teleports.
        val fish=at.world.spawn(at,org.bukkit.entity.ItemDisplay::class.java) {
            it.setItemStack(org.bukkit.inventory.ItemStack.of(listOf(Material.COD,Material.SALMON,Material.TROPICAL_FISH,Material.PUFFERFISH).random()))
            it.itemDisplayTransform=org.bukkit.entity.ItemDisplay.ItemDisplayTransform.GROUND
            it.teleportDuration=1;it.interpolationDuration=1;it.isPersistent=false
            ownedEntities.add(it)
        }
        val velocity=Vector(Random.nextDouble(-0.5,0.5),Random.nextDouble(1.0,2.0),Random.nextDouble(-0.5,0.5))
            .normalize().multiply(Random.nextDouble(0.25,0.65)).add(Vector(0.0,0.2,0.0)).multiply(itemBoost)
        loop(finish={remove(fish)}) {age ->
            if(age>=60 || !fish.isValid)return@loop false
            val impact=collision(at,velocity,0.3)
            if(impact!=null) {
                impact.second?.let {hit(it,2.0,0.0,it.location.clone().subtract(0.0,1.0,0.0),hitSound=true)}
                player.world.spawnParticle(Particle.POOF,impact.first,12,0.15,0.15,0.15,0.0)
                false
            } else {
                at=at.add(velocity);fish.teleport(at)
                velocity.multiply(0.98);velocity.y-=0.04
                true
            }
        }
    }
}
