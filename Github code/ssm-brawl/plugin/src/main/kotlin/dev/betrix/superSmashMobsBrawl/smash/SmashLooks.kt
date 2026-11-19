package dev.betrix.superSmashMobsBrawl.smash

import dev.betrix.superSmashMobsBrawl.extensions.disguise
import me.libraryaddict.disguise.DisguiseAPI
import me.libraryaddict.disguise.DisguiseConfig
import me.libraryaddict.disguise.disguisetypes.*
import me.libraryaddict.disguise.disguisetypes.watchers.*
import org.bukkit.entity.*

object SmashLooks {
    private val forms=mutableMapOf<java.util.UUID,DisguiseType>()
    fun form(player:Player,key:String) {
        val type=when(key){"wither_skeleton:2","wither_skeleton:3"->DisguiseType.WITHER;else->null} ?: return
        change(player,type)
    }
    fun change(player:Player,type:DisguiseType) {
        if(forms[player.uniqueId]==type && DisguiseAPI.getDisguise(player)?.type==type)return
        forms[player.uniqueId]=type
        val disguise=MobDisguise(type)
        disguise.isSelfDisguiseVisible=false;disguise.notifyBar=DisguiseConfig.NotifyBar.NONE
        disguise.setReplaceSounds(false)
        disguise.setHideArmorFromSelf(false);disguise.setHideHeldItemFromSelf(false)
        // The armor a kit wears is for its numbers, not for show: on a mob with a body to wear it
        // (a Zombie Pigman, a Piglin Brute) it would be drawn. Empty stacks hide it; leaving the
        // watcher alone would pass the player's own armor through.
        disguise.watcher.armor=Array(4) { org.bukkit.inventory.ItemStack.empty() }
        disguise.watcher.customName=dev.betrix.superSmashMobsBrawl.services.ClassicChatService.nameTag(player)
        disguise.watcher.isCustomNameVisible=true
        // Outside the Nether a piglin trembles as if about to zombify; this one is here to stay.
        (disguise.watcher as? PiglinAbstractWatcher)?.isShaking=false
        DisguiseAPI.disguiseToAll(player,disguise)
    }
    fun restore(player:Player) {if(forms.remove(player.uniqueId)!=null)player.disguise?.refreshVisual()}
    /** Debug: what others see this player as, and the armor drawn on it (empty when none is). */
    fun describe(player:Player):String {
        val look=DisguiseAPI.getDisguise(player) ?: return "no disguise"
        return "${look.type} armor=[${look.watcher.armor.joinToString(",") { it?.type?.name?.lowercase() ?: "inherited" }}]"
    }
    fun slimeSize(player:Player,size:Int) {
        (DisguiseAPI.getDisguise(player)?.watcher as? SlimeWatcher)?.let { if(it.size!=size)it.size=size }
    }
    fun pig(player:Player,brute:Boolean)=change(player,if(brute)DisguiseType.PIGLIN_BRUTE else DisguiseType.ZOMBIFIED_PIGLIN)
    fun cleric(player:Player) {(DisguiseAPI.getDisguise(player)?.watcher as? VillagerWatcher)?.profession=Villager.Profession.CLERIC}
    fun armoredWither(player:Player) {(DisguiseAPI.getDisguise(player)?.watcher as? WitherWatcher)?.health=100f}
}
