package dev.betrix.superSmashMobsBrawl.commands

import com.github.shynixn.mccoroutine.bukkit.launch
import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.services.MapScanService
import dev.rollczi.litecommands.annotations.argument.Arg
import dev.rollczi.litecommands.annotations.command.Command
import dev.rollczi.litecommands.annotations.context.Context
import dev.rollczi.litecommands.annotations.description.Description
import dev.rollczi.litecommands.annotations.execute.Execute
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** /scanmaps [mapId] - force a rescan of every map (or one map) in ./worlds. Ops/console only. */
@Command(name = "scanmaps")
class ScanMapsCommand : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()

    @Execute
    @Description("Rescan every map's wool/pressure-plate markers")
    fun all(@Context sender: CommandSender) = run(sender, null)

    @Execute
    @Description("Rescan one map's wool/pressure-plate markers")
    fun one(@Context sender: CommandSender, @Arg mapId: String) = run(sender, mapId)

    @Execute(name = "column")
    @Description("Debug: print the blocks in one column of a map")
    fun column(
        @Context sender: CommandSender,
        @Arg mapId: String,
        @Arg x: Int,
        @Arg z: Int,
        @Arg y1: Int,
        @Arg y2: Int,
    ) {
        if (sender is Player && !sender.isOp) return
        plugin.launch {
            MapScanService.describeColumn(mapId, x, z, y1, y2).forEach { plugin.logger.info(it) }
            plugin.logger.info("COLUMN-DONE $mapId")
        }
    }

    private fun run(sender: CommandSender, mapId: String?) {
        if (sender is Player && !sender.isOp) {
            sender.sendMessage("Only operators can rescan maps.")
            return
        }
        sender.sendMessage("Rescanning ${mapId ?: "all maps"}...")
        plugin.launch {
            MapScanService.syncAll(force = true, only = mapId) { msg ->
                sender.sendMessage(msg)
                if (sender is Player) plugin.logger.info(msg)
            }
        }
    }
}
