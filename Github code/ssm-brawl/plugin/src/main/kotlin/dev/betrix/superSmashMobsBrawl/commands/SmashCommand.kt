package dev.betrix.superSmashMobsBrawl.commands

import dev.betrix.superSmashMobsBrawl.smash.*
import dev.betrix.superSmashMobsBrawl.services.MinigameService
import dev.rollczi.litecommands.annotations.argument.Arg
import dev.rollczi.litecommands.annotations.command.Command
import dev.rollczi.litecommands.annotations.context.Context
import dev.rollczi.litecommands.annotations.execute.Execute
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@Command(name="smash")
class SmashCommand:KoinComponent {
    private val games:MinigameService by inject()
    private fun tier(name:String)=when(name.lowercase()) {
        "blue","light"->SmashTier.LIGHT;"green","regular"->SmashTier.REGULAR
        "yellow","super"->SmashTier.SUPER;"red","ultra"->SmashTier.ULTRA;else->null
    }
    @Execute(name="give")
    fun give(@Context sender:CommandSender,@Arg name:String,@Arg rarity:String) {
        if(sender is Player && !sender.isOp)return
        val player=Bukkit.getPlayerExact(name);val tier=tier(rarity)
        if(player==null || tier==null) {sender.sendMessage("Use /smash give <player> <blue|green|yellow|red>");return}
        sender.sendMessage(if(SmashService.giveStar(player,tier)) "Gave ${tier.title} to ${player.name}." else "That player already holds a crystal or has no inventory room.")
    }
    @Execute(name="spawn")
    fun spawn(@Context player:Player,@Arg rarity:String) {
        if(!player.isOp)return
        val game=games.getMinigameForPlayer(player) ?: return
        val tier=tier(rarity) ?: return
        if(!SmashService.arena(game).spawnDrop(tier))player.sendMessage("A crystal is already descending, or this map has no landing spot.")
    }
}
