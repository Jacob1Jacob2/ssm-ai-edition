package dev.betrix.superSmashMobsBrawl.commands

import dev.betrix.superSmashMobsBrawl.services.DebugService
import dev.betrix.superSmashMobsBrawl.services.LangService
import dev.rollczi.litecommands.annotations.command.Command
import dev.rollczi.litecommands.annotations.context.Context
import dev.rollczi.litecommands.annotations.description.Description
import dev.rollczi.litecommands.annotations.execute.Execute
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@Command(name = "debug")
class DebugCommand : KoinComponent {
    private val lang: LangService by inject()

    @Execute
    @Description("Toggle debug mode on/off")
    fun execute(@Context sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(lang.t("messages.commands.onlyPlayers"))
            return
        }

        if (!sender.hasPermission("ssmb.debug")) {
            sender.sendMessage("Only operators can enable debugging.")
            return
        }

        val enabled = DebugService.toggleDebug(sender)
        if (enabled) {
            sender.sendMessage(lang.t("messages.commands.debug.enabled"))
        } else {
            sender.sendMessage(lang.t("messages.commands.debug.disabled"))
        }
    }
}
