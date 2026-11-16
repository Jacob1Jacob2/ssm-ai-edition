package dev.betrix.superSmashMobsBrawl.commands

import dev.betrix.superSmashMobsBrawl.smash.SmashService
import dev.betrix.superSmashMobsBrawl.smash.SmashTier
import dev.rollczi.litecommands.annotations.argument.Arg
import dev.rollczi.litecommands.annotations.command.Command
import dev.rollczi.litecommands.annotations.context.Context
import dev.rollczi.litecommands.annotations.execute.Execute
import org.bukkit.entity.Player

@Command(name = "spawn")
class SpawnCrystalCommand {
    private fun tier(name: String) = when (name.lowercase()) {
        "blue", "light" -> SmashTier.LIGHT
        "green", "regular" -> SmashTier.REGULAR
        "yellow", "super" -> SmashTier.SUPER
        "red", "ultra" -> SmashTier.ULTRA
        else -> null
    }
    @Execute(name = "crystal")
    fun spawn(@Context player: Player, @Arg("crystal-color") color: String) = run(player, color, false)

    @Execute(name = "crystal")
    fun item(@Context player: Player, @Arg("crystal-color") color: String, @Arg("crystal-mode") mode: String) {
        if (!mode.equals("item", true)) { usage(player); return }
        run(player, color, true)
    }
    @Execute(name = "item")
    fun arenaItem(@Context player: Player, @dev.rollczi.litecommands.annotations.join.Join("arena-item") name: String) {
        if (!player.isOp) { player.sendMessage("Only operators can spawn items."); return }
        val kind = dev.betrix.superSmashMobsBrawl.items.ArenaItem.parse(name)
        if (kind == null) { player.sendMessage("Use /spawn item <name>. Items: " + dev.betrix.superSmashMobsBrawl.items.ArenaItem.entries.joinToString { it.id }); return }
        player.sendMessage(dev.betrix.superSmashMobsBrawl.items.ItemService.spawnInFront(player, kind))
    }
    private fun usage(player: Player) = player.sendMessage("Use /spawn crystal <blue|green|yellow|red> [item]")
    private fun run(player: Player, color: String, item: Boolean) {
        if (!player.isOp) { player.sendMessage("Only operators can spawn Smash Crystals."); return }
        val rarity = tier(color) ?: run { usage(player); return }
        val result=if (item) {
            if (SmashService.giveTestStar(player, rarity)) "You received ${rarity.title}. Right-click it during a match to activate it."
            else "Your inventory is full. Make room for the Smash Crystal first."
        } else SmashService.spawnCrystal(player, rarity)
        if(result.isNotBlank())player.sendMessage(result)
    }
}
