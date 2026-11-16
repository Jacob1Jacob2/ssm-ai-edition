package dev.betrix.superSmashMobsBrawl.commands

import dev.betrix.superSmashMobsBrawl.services.CustomBotService
import dev.rollczi.litecommands.annotations.argument.Arg
import dev.rollczi.litecommands.annotations.command.Command
import dev.rollczi.litecommands.annotations.context.Context
import dev.rollczi.litecommands.annotations.execute.Execute
import dev.rollczi.litecommands.annotations.permission.Permission
import org.bukkit.command.CommandSender

@Command(name="removebot")
@Permission("ssmb.removebot")
class RemoveBotCommand {
    @Execute
    fun remove(@Context sender:CommandSender,@Arg name:String) {
        sender.sendMessage(if(CustomBotService.remove(name))"Removed custom bot $name." else "No custom bot named $name. Built-in opponents cannot be removed.")
    }
}
