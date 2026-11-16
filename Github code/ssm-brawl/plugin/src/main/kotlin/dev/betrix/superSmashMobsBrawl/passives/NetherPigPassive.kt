package dev.betrix.superSmashMobsBrawl.passives

import dev.betrix.superSmashMobsBrawl.smash.SmashLooks
import dev.betrix.superSmashMobsBrawl.smash.SmashService
import gg.flyte.twilight.scheduler.repeatingTask
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class NetherPigPassive(player: Player) : BrawlPassive("nether_pig",player) {
    var active=false
        private set
    override fun setup() {
        super.setup();player.exp=0f
        runnables.add(repeatingTask(1) {
            if(minigameService.getMinigameForPlayer(player)?.getState()!=dev.betrix.superSmashMobsBrawl.minigames.MinigameState.IN_PROGRESS)return@repeatingTask
            if(!SmashService.ownsXp(player))player.exp=(player.exp+0.005f).coerceAtMost(1f)
            if(SmashService.has(player,"pig:3"))return@repeatingTask
            if(!active && player.health>0 && player.health<=(metadata.double("lowHealthThreshold")?:6.0)) {
                active=true;player.exp=1f;SmashLooks.pig(player,false)
                player.world.playSound(player.location,Sound.ENTITY_ZOMBIFIED_PIGLIN_ANGRY,2f,1f)
            } else if(active && player.health>=10) {
                active=false;SmashLooks.restore(player)
                player.world.playSound(player.location,Sound.ENTITY_PIG_AMBIENT,2f,1f)
            }
            if(active)player.addPotionEffect(PotionEffect(PotionEffectType.SPEED,18,0,false,false))
        })
    }
    override fun teardown() {
        if(active && !SmashService.has(player,"pig:3"))SmashLooks.restore(player)
        active=false;super.teardown()
    }
}
