package dev.betrix.superSmashMobsBrawl.commands

import dev.betrix.superSmashMobsBrawl.services.AutoL
import dev.rollczi.litecommands.annotations.argument.Arg
import dev.rollczi.litecommands.annotations.command.Command
import dev.rollczi.litecommands.annotations.context.Context
import dev.rollczi.litecommands.annotations.execute.Execute
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Sound
import org.bukkit.entity.Player

/** `/auto L`: whether "L <name>" is said for you after each of your kills (see [AutoL]). */
@Command(name = "auto")
class AutoCommand {
    private fun tell(player: Player, text: Component) =
        player.sendMessage(Component.text("Chat> ", NamedTextColor.BLUE).append(text))

    @Execute
    fun usage(@Context player: Player) = tell(player, Component.text("Type /auto L to turn your L after a kill on or off.", NamedTextColor.GRAY))

    @Execute
    fun toggle(@Context player: Player, @Arg("auto-what") what: String) {
        if (!AutoL.names(what)) return usage(player)
        val on = AutoL.toggle(player.uniqueId)
        tell(player, Component.text("Auto L is now ", NamedTextColor.GRAY)
            .append(Component.text(if (on) "on" else "off", if (on) NamedTextColor.GREEN else NamedTextColor.RED))
            .append(Component.text(if (on) ": an L is said for you after each kill." else ": nothing is said for you after a kill.", NamedTextColor.GRAY)))
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, if (on) 1.4f else 0.9f)
    }
}
