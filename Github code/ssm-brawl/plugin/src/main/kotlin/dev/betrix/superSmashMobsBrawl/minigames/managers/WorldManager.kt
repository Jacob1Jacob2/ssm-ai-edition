package dev.betrix.superSmashMobsBrawl.minigames.managers

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import dev.betrix.superSmashMobsBrawl.IManageable
import dev.betrix.superSmashMobsBrawl.Manageable
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.models.BrawlGameWorld
import dev.betrix.superSmashMobsBrawl.services.DataService
import dev.betrix.superSmashMobsBrawl.services.WorldService
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Responsible for selecting, loading, and cleaning up the game world for a minigame. */
interface IWorldManager : IManageable {
    suspend fun loadWorld(gameId: String): Result<BrawlGameWorld, Exception>

    fun getWorld(): BrawlGameWorld?
}

class DefaultWorldManager(private val minigame: BrawlMinigame) :
    Manageable(), IWorldManager, KoinComponent {
    private val worldService: WorldService by inject()
    private val dataService: DataService by inject()

    private var brawlWorld: BrawlGameWorld? = null

    override suspend fun loadWorld(gameId: String): Result<BrawlGameWorld, Exception> {
        val validMaps =
            dataService.getAllGameMaps().filter { map ->
                minigame.minigameDef.mapWhitelist?.let { whitelist ->
                    return@filter whitelist.contains(map.id)
                }
                minigame.minigameDef.mapBlacklist?.let { blacklist ->
                    return@filter !blacklist.contains(map.id)
                }
                true
            }

        val voters = minigame.host?.let { host -> minigame.allPlayers().filter { it.uniqueId == host } }
            ?: minigame.allPlayers()
        val (selectedMap, voteCount, choiceLabel) =
            dev.betrix.superSmashMobsBrawl.services.MapVoteService.pickMap(voters,
                validMaps.filter { dev.betrix.superSmashMobsBrawl.services.MapVoteService.capacity(it) >= minigame.allPlayers().size })
                ?: return Err(RuntimeException("Choose a map before starting."))
        if (dev.betrix.superSmashMobsBrawl.services.MapVoteService.capacity(selectedMap) < minigame.allPlayers().size)
            return Err(RuntimeException("The chosen map does not have enough starting spots. Choose another map or fewer players."))
        val transform = minigame.settings.resolveTransform()

        try {
            val result =
                worldService.copyAndLoadWorld(selectedMap, gameId, transform = transform).map { it as BrawlGameWorld }
            result.map { loaded -> brawlWorld = loaded }
            return result
        } catch (e: Exception) {
            return Err(e)
        }
    }

    override fun getWorld(): BrawlGameWorld? = brawlWorld

    override fun teardown() {
        super.teardown()
        // WorldService handles unloading via its own lifecycle; nothing to do here yet.
        brawlWorld = null
    }
}
