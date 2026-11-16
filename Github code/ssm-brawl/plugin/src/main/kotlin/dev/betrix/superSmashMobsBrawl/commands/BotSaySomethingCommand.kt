package dev.betrix.superSmashMobsBrawl.commands

import dev.betrix.superSmashMobsBrawl.services.BotConversationService
import dev.rollczi.litecommands.annotations.command.Command
import dev.rollczi.litecommands.annotations.context.Context
import dev.rollczi.litecommands.annotations.execute.Execute
import org.bukkit.entity.Player

@Command(name = "botsaysomething")
class BotSaySomethingCommand {
    @Execute fun speak(@Context player: Player) {
        if (!BotConversationService.prompt(player)) player.sendMessage("Join a match and choose Salty or Chill to hear the bots.")
    }
}
