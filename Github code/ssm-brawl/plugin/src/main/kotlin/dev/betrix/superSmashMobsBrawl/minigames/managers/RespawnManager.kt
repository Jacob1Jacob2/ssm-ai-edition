package dev.betrix.superSmashMobsBrawl.minigames.managers

import com.github.shynixn.mccoroutine.bukkit.launch
import dev.betrix.superSmashMobsBrawl.IManageable
import dev.betrix.superSmashMobsBrawl.Manageable
import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.events.BrawlDeathEvent
import dev.betrix.superSmashMobsBrawl.events.PlayerDeathAnalyticsEvent
import dev.betrix.superSmashMobsBrawl.events.PlayerRespawnAnalyticsEvent
import dev.betrix.superSmashMobsBrawl.extensions.getFarthestFromPlayers
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.minigames.IKitHandler
import dev.betrix.superSmashMobsBrawl.minigames.ITeleportationManager
import dev.betrix.superSmashMobsBrawl.models.SpawnPoint
import dev.betrix.superSmashMobsBrawl.services.LangService
import gg.flyte.twilight.extension.feed
import gg.flyte.twilight.extension.heal
import gg.flyte.twilight.scheduler.delay
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay as coDelay
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.OfflinePlayer
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

interface IRespawnManager : IManageable {
    fun markRespawning(player: OfflinePlayer)

    fun clearRespawning(player: OfflinePlayer)

    fun isRespawning(player: OfflinePlayer): Boolean

    suspend fun handlePlayerDeath(player: Player)
}

class DefaultRespawnManager(private val minigame: BrawlMinigame) :
    Manageable(), IRespawnManager, KoinComponent {

    private val langService: LangService by inject()
    private val plugin: SuperSmashMobsBrawl by inject()

    private val respawning = mutableSetOf<java.util.UUID>()

    override fun setup() {
        // Listen for death events
        listeners.add(
            BrawlDeathEvent.listen {
                // Only handle deaths for players in this minigame and not already respawning
                if (
                    minigame.allPlayers().none {
                        it.isOnline && it.player?.uniqueId == player.uniqueId
                    } || isRespawning(player)
                )
                    return@listen

                val hazard = (reason as? dev.betrix.superSmashMobsBrawl.events.DeathReason.Hazard)?.type
                if (hazard == dev.betrix.superSmashMobsBrawl.events.HazardType.VOID ||
                    hazard == dev.betrix.superSmashMobsBrawl.events.HazardType.LAVA) {
                    val at = player.location.clone()
                    at.y = at.y.coerceAtLeast(player.world.minHeight.toDouble())
                    player.world.strikeLightningEffect(at)
                }
                // Fire analytics event with the real reason
                PlayerDeathAnalyticsEvent(player, reason, minigame).callEvent()

                plugin.launch { handlePlayerDeath(player) }
            }
        )
    }

    override fun markRespawning(player: OfflinePlayer) {
        respawning.add(player.uniqueId)
    }

    override fun clearRespawning(player: OfflinePlayer) {
        respawning.remove(player.uniqueId)
    }

    override fun isRespawning(player: OfflinePlayer): Boolean {
        return respawning.contains(player.uniqueId)
    }

    override suspend fun handlePlayerDeath(player: Player) {
        // Check if player is still in the minigame at the start
        if (!isPlayerInMinigame(player)) {
            return
        }

        // Unassign kit
        (minigame.kitHandler as? IKitHandler)?.removeKitFromPlayer(player)

        // Short-lived red dye fragments, without a lightning flash or thunder.
        repeat(12) {
            val dye = player.world.dropItem(player.location.clone().add(0.0, .8, 0.0), org.bukkit.inventory.ItemStack.of(org.bukkit.Material.RED_DYE))
            dye.pickupDelay = Int.MAX_VALUE
            dye.setCanPlayerPickup(false); dye.setCanMobPickup(false); dye.isPersistent = false
            dye.velocity = org.bukkit.util.Vector((Math.random()-.5)*.4, .15+Math.random()*.2, (Math.random()-.5)*.4)
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, Runnable { dye.remove() }, 24L)
        }

        var respawnSuccessful = false

        if (minigame.minigameDef.respawnDelaySeconds != null) {
            // Keep the death location; spectator controls remain free during the countdown.
            player.gameMode = GameMode.SPECTATOR
            player.allowFlight = true
            player.isFlying = true
            player.fallDistance = 0f
            player.inventory.clear()

            // Mark player as respawning
            markRespawning(player)

            try {
                val respawnDelay = minigame.minigameDef.respawnDelaySeconds ?: 0

                // Fire analytics event
                PlayerRespawnAnalyticsEvent(player, minigame, respawnDelay).callEvent()

                // Let the lives counter update for this death before showing "N lives left!".
                coDelay(0.06.seconds)
                if (!isPlayerInMinigame(player)) return
                if ((minigame.teamManager.findTeamOf(player)?.metadata?.get("stocks") as? Int ?: 1) <= 0) {
                    MatchEnding.showEliminated(player)
                    minigame.objectiveManager.checkWinCondition()
                    return
                }

                // The reference uses the smaller subtitle line and a fixed waiting message.
                // Send it once; remaining lives are shown only after the player respawns.
                player.showTitle(Title.title(
                    Component.empty(),
                    Component.text("Respawning in $respawnDelay Seconds...",
                        net.kyori.adventure.text.format.NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(250),
                        Duration.ofMillis((respawnDelay * 1000L - 500).coerceAtLeast(0)),
                        Duration.ofMillis(250))))
                coDelay(respawnDelay.seconds)

                // Final check before respawning
                if (!isPlayerInMinigame(player)) {
                    return@handlePlayerDeath
                }

                // If we reach here, respawn was successful
                respawnSuccessful = true
            } finally {
                // Only remove from respawning if respawn failed (player left during countdown)
                if (!respawnSuccessful) {
                    clearRespawning(player)
                }
            }
        }

        // Remove player from respawning state before respawning (successful case)
        if (respawnSuccessful || minigame.minigameDef.respawnDelaySeconds == null) {
            clearRespawning(player)
        }

        // SSM AI Edition: out of lives -> stay a spectator. (Upstream respawned players forever,
        // even after their team had no stocks left.)
        val stocksLeft = minigame.teamManager.findTeamOf(player)?.metadata?.get("stocks") as? Int
        if (stocksLeft != null && stocksLeft <= 0) {
            player.gameMode = GameMode.SPECTATOR
            MatchEnding.showEliminated(player)
            minigame.objectiveManager.checkWinCondition()
            return
        }

        // Find spawn point
        val world = minigame.worldManager.getWorld() ?: return
        val activePlayers =
            minigame
                .allPlayers()
                .mapNotNull { if (it.isOnline) it.player else null }
                .filter { it != player && it.gameMode != GameMode.SPECTATOR }

        val spawnPoint =
            world.data.spawnPoints.getFarthestFromPlayers(activePlayers, world.world)
                ?: SpawnPoint(0.0, 100.0, 0.0)

        // Teleport and restore player
        (minigame.teleportationManager as? ITeleportationManager)?.teleportPlayerRandomSpawnPoint(
            player
        )
        player.feed()
        player.heal()
        player.gameMode = GameMode.SURVIVAL
        player.inventory.clear()

        // Handle kit assignment
        (minigame.kitHandler as? IKitHandler)?.assignKitToPlayer(player)
        player.showTitle(Title.title(
            Component.empty(),
            stocksLeft?.let(dev.betrix.superSmashMobsBrawl.utils.CombatFeedback::livesTitle) ?: Component.empty(),
            Title.Times.times(Duration.ofMillis(250),Duration.ofSeconds(2),Duration.ofMillis(500))))
        dev.betrix.superSmashMobsBrawl.items.ItemService.hide(player,40)
    }

    private fun isPlayerInMinigame(player: Player): Boolean {
        return minigame.getState() == dev.betrix.superSmashMobsBrawl.minigames.MinigameState.IN_PROGRESS && minigame.allPlayers().any {
            it.isOnline && it.player?.uniqueId == player.uniqueId
        } && !minigame.connectionManager.isDisconnected(player)
    }

    override fun teardown() {
        super.teardown()
        respawning.clear()
    }
}
