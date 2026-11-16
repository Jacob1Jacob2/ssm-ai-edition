package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.extensions.disguise
import dev.betrix.superSmashMobsBrawl.disguises.EndermanDisguise
import org.bukkit.Effect
import kotlin.math.min

class TripleBlockToss(private val s:SmashSession) {
    private val player get()=s.player
    fun clear(p:SmashPower) {
        p.active=false;p.blockData=null;p.charged=0
        (player.disguise as? EndermanDisguise)?.setHeldBlock(null)
    }
    fun start(p:SmashPower) {
        if(p.active)return
        if(p.blockData==null && dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(player))
            p.blockData=player.location.clone().subtract(0.0,1.0,0.0).block.blockData
        val block=p.blockData?.takeIf {it.material.isSolid} ?: return
        p.active=true;p.charged=0
        (player.disguise as? EndermanDisguise)?.setHeldBlock(block.material)
        player.world.playEffect(player.location,Effect.STEP_SOUND,block.material)
    }
    fun tick(p:SmashPower) {
        if(!p.active)return
        if(!s.holding(p))release(p)
        else if(p.charged<24) {
            p.charged++
            if(p.charged==24)player.world.playEffect(player.location,Effect.CLICK1,0)
        }
    }
    fun release(p:SmashPower) {
        if(!p.active)return
        val block=p.blockData ?: return clear(p)
        val charge=(p.charged/24.0).coerceIn(.15,1.0)
        repeat(3) {i -> s.combat.shot(p,block.material,min(9.0,1.4*charge*8)*1.2,3.0,1.4*charge*1.6,
            scale=.5+.6*charge,direction=player.eyeLocation.direction.clone().rotateAroundY((i-1)*.12),blockData=block,gravity=.03) }
        p.uses--;p.nextUse=s.arena.tick+s.moves.cooldown(p);clear(p);s.refreshTool(p)
    }
}
