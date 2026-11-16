package dev.betrix.superSmashMobsBrawl.passives

import dev.betrix.superSmashMobsBrawl.events.*
import dev.betrix.superSmashMobsBrawl.utils.NewKitMath
import dev.betrix.superSmashMobsBrawl.smash.SmashService
import gg.flyte.twilight.event.event
import gg.flyte.twilight.scheduler.repeatingTask
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority

class OvergrowthPassive(player:Player):BrawlPassive("overgrowth",player) {
    private var previousCap=0.0
    override fun setup(){
        super.setup()
        previousCap=player.getAttribute(Attribute.MAX_ABSORPTION)?.baseValue?:0.0
        player.getAttribute(Attribute.MAX_ABSORPTION)?.baseValue=6.0
        player.absorptionAmount=0.0
        listeners.add(event<BrawlDamageEvent>(priority=EventPriority.MONITOR) {
            if(appliedDamage<=0 || victim==player || (damager as? Damager.DamagerLivingEntity)?.livingEntity!=player)return@event
            val cap=(if(SmashService.has(player,"mooshroom:3"))12.0 else 6.0) + dev.betrix.superSmashMobsBrawl.items.ItemService.appleBonus(player)
            player.getAttribute(Attribute.MAX_ABSORPTION)?.baseValue=cap
            player.absorptionAmount=NewKitMath.absorption(player.absorptionAmount,appliedDamage,cap)
        })
        runnables.add(repeatingTask(1){
            if(!SmashService.ownsXp(player)) player.exp=(player.absorptionAmount/(if(SmashService.has(player,"mooshroom:3"))12.0 else 6.0)).toFloat().coerceIn(0f,1f)
        })
    }
    override fun teardown(){player.absorptionAmount=0.0;player.getAttribute(Attribute.MAX_ABSORPTION)?.baseValue=previousCap;super.teardown()}
}
