package dev.betrix.superSmashMobsBrawl.commands

import dev.betrix.superSmashMobsBrawl.services.LobbyRouting
import dev.betrix.superSmashMobsBrawl.services.LobbyService
import dev.rollczi.litecommands.annotations.argument.Arg
import dev.rollczi.litecommands.annotations.command.Command
import dev.rollczi.litecommands.annotations.context.Context
import dev.rollczi.litecommands.annotations.execute.Execute
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** /lobby opens the list of lobbies; /lobby <number> goes straight to one. */
@Command(name = "lobby")
class LobbyCommand {
    @Execute
    fun menu(@Context sender: CommandSender) {
        if (sender is Player) LobbyService.openMenu(sender) else sender.sendMessage("Only players can change lobbies.")
    }

    @Execute
    fun change(@Context sender: CommandSender, @Arg number: Int) {
        if (sender !is Player) { sender.sendMessage("Only players can change lobbies."); return }
        if (number !in LobbyRouting.ids || !LobbyService.changeLobby(sender, number)) sender.sendMessage(
            Component.text("Lobby> ", NamedTextColor.BLUE).append(Component.text("There is no Lobby $number. Choose ${LobbyRouting.ids.first()} to ${LobbyRouting.ids.last()}.", NamedTextColor.GRAY)))
    }
}
