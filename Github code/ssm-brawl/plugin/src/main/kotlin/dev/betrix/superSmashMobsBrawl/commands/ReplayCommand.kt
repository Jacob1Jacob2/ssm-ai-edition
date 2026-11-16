package dev.betrix.superSmashMobsBrawl.commands

import com.github.michaelbull.result.onSuccess
import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.services.*
import dev.rollczi.litecommands.annotations.command.Command
import dev.rollczi.litecommands.annotations.context.Context
import dev.rollczi.litecommands.annotations.execute.Execute
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@Command(name = "playagain")
class ReplayCommand : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val games: MinigameService by inject()
    private fun replay(player: Player, options: Boolean) {
        if (games.getMinigameForPlayer(player)?.getState()?.let { it != dev.betrix.superSmashMobsBrawl.minigames.MinigameState.ENDED } == true) {
            player.sendMessage("Leave your current match first with /hub."); return
        }
        HubService.teleportToDefaultHub(player).onSuccess {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (player.isOnline && HubService.isPlayerInHub(player)) {
                    if (options) MapVoteService.openVoteGui(player) else GameSetupService.startGame(player)
                }
            })
        }
    }
    @Execute fun play(@Context player: Player) = replay(player, false)
    @Execute(name = "map") fun map(@Context player: Player) = replay(player, true)
    @Execute(name = "options") fun options(@Context player: Player) = replay(player, true)
}
