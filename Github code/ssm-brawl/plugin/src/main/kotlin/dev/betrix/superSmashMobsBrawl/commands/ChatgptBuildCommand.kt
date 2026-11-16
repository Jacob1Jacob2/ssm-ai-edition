package dev.betrix.superSmashMobsBrawl.commands

import dev.betrix.superSmashMobsBrawl.services.LobbyBuildService
import dev.rollczi.litecommands.annotations.command.Command
import dev.rollczi.litecommands.annotations.context.Context
import dev.rollczi.litecommands.annotations.execute.Execute
import org.bukkit.entity.Player
import dev.rollczi.litecommands.annotations.argument.Arg

@Command(name = "chatgptbuild")
class ChatgptBuildCommand {
    @Execute fun toggle(@Context player: Player) = LobbyBuildService.toggle(player)
    @Execute(name = "face") fun face(@Context player: Player, @Arg("npc-direction") direction: String) = LobbyBuildService.face(player, direction)
    @Execute(name = "move") fun move(@Context player: Player, @Arg x: Int, @Arg y: Int, @Arg z: Int) = LobbyBuildService.move(player, x, y, z)
    @Execute(name = "save") fun save(@Context player: Player) = LobbyBuildService.save(player)
}
