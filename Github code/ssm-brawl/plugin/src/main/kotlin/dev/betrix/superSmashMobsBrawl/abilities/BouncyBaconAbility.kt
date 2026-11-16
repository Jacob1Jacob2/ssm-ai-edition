package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.smash.SmashService
import dev.betrix.superSmashMobsBrawl.services.KitService
import dev.betrix.superSmashMobsBrawl.passives.NetherPigPassive
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector

class BouncyBaconAbility(player: Player) : ClassicKitAbility("bouncy_bacon", player) {
    private fun cost()=if(borrowed!=null)0f else PigEnergy.cost(player,0.225f)
    override fun canActivate(sendMessage:Boolean)=player.exp>=cost()&&super.canActivate(sendMessage)
    override fun activate() {
        val cost=cost()
        if(player.exp<cost)return
        player.exp=(player.exp-cost).coerceAtLeast(0f)
        super.activate()
        player.world.playSound(player.location,Sound.ENTITY_PIG_AMBIENT,2f,1.5f)
        val flight=dev.betrix.superSmashMobsBrawl.utils.BaconFlight(player,Material.PORKCHOP,Material.COOKED_PORKCHOP,
            upgradedValue("projectileSpeed",1.2),itemBoost,
            collision={at,step->collision(at,step,.3)},
            hit={target,at->hit(target,4.0,metadata.double("projectileKnockback")?:.9,at)},
            eat={cooked ->
                if(borrowed==null)player.exp=(player.exp+.05f).coerceAtMost(1f)
                player.world.playSound(player.location,Sound.ENTITY_GENERIC_EAT,2f,1f)
                if(cooked) {
                    player.health=(player.health+1.0).coerceAtMost(player.getAttribute(Attribute.MAX_HEALTH)!!.value)
                    player.world.spawnParticle(org.bukkit.Particle.HEART,player.location.add(0.0,.5,0.0),4,.2,.2,.2,0.0)
                }
            },track={ownedEntities.add(it)})
        loop(finish={flight.close()}) {flight.step()}
    }
}

internal object PigEnergy {
    fun cost(player:Player,base:Float)=base*if(SmashService.has(player,"pig:3")) {
        if(player.health<10)0.34f else 0.67f
    } else if((KitService.getKitForPlayer(player)?.getPassive("nether_pig") as? NetherPigPassive)?.active==true)0.7f else 1f
}
