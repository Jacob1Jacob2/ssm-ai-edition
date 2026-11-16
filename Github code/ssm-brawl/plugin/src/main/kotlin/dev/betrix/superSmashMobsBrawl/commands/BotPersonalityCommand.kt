package dev.betrix.superSmashMobsBrawl.commands

import dev.betrix.superSmashMobsBrawl.services.BotConversationService
import dev.rollczi.litecommands.annotations.command.Command
import dev.rollczi.litecommands.annotations.context.Context
import dev.rollczi.litecommands.annotations.execute.Execute
import org.bukkit.entity.Player

@Command(name = "botpersonality")
class BotPersonalityCommand {
    @Execute fun menu(@Context player: Player) = BotConversationService.openMenu(player)
}
