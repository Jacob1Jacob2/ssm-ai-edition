package dev.betrix.superSmashMobsBrawl.minigames.managers

import dev.betrix.superSmashMobsBrawl.IManageable
import dev.betrix.superSmashMobsBrawl.Manageable
import dev.betrix.superSmashMobsBrawl.events.BrawlDeathEvent
import dev.betrix.superSmashMobsBrawl.events.DeathReason
import dev.betrix.superSmashMobsBrawl.events.HazardType
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.minigames.MinigameState
import gg.flyte.twilight.scheduler.delay
import gg.flyte.twilight.scheduler.repeatingTask
import java.util.UUID
import org.bukkit.GameMode

interface IHazardManager : IManageable

class DefaultHazardManager(private val minigame: BrawlMinigame) : Manageable(), IHazardManager {
    private val recentHazardDeaths = mutableSetOf<UUID>()

    override fun setup() {
        val world = minigame.worldManager.getWorld() ?: return
        runnables.add(repeatingTask(2) {
            if (minigame.getState() != MinigameState.IN_PROGRESS) return@repeatingTask
            minigame.allPlayers().forEach { participant ->
                val player = participant.player ?: return@forEach
                if (player.world != world.world || !player.isOnline) return@forEach
                if (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE) return@forEach
                if (minigame.respawnManager.isRespawning(player)) return@forEach
                val stocks = minigame.teamManager.findTeamOf(player)?.metadata?.get("stocks") as? Int
                if (stocks != null && stocks <= 0) return@forEach
                val hazard = when {
                    player.location.y <= world.data.voidLevel -> HazardType.VOID
                    player.location.block.type == org.bukkit.Material.LAVA -> HazardType.LAVA
                    player.location.block.isLiquid -> HazardType.LIQUID
                    else -> return@forEach
                }
                // Gate both hazard types before emitting a death. Spectators can remain
                // at the death location during the countdown, including inside water.
                if (!recentHazardDeaths.add(player.uniqueId)) return@forEach
                BrawlDeathEvent.call(player, DeathReason.Hazard(hazard))
                runnables.add(delay(40) { recentHazardDeaths.remove(player.uniqueId) })
            }
        })
    }

    override fun teardown() {
        super.teardown()
        recentHazardDeaths.clear()
    }
}
