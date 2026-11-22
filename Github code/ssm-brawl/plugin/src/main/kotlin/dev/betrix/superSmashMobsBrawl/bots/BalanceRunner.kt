package dev.betrix.superSmashMobsBrawl.bots

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.events.MinigameEndAnalyticsEvent
import dev.betrix.superSmashMobsBrawl.items.ArenaItem
import dev.betrix.superSmashMobsBrawl.items.ItemStats
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.minigames.MinigameState
import dev.betrix.superSmashMobsBrawl.services.DataService
import dev.betrix.superSmashMobsBrawl.services.GameSetupService
import dev.betrix.superSmashMobsBrawl.services.KitService
import dev.betrix.superSmashMobsBrawl.services.LobbyRouting
import dev.betrix.superSmashMobsBrawl.services.LobbyService
import dev.betrix.superSmashMobsBrawl.services.MapVoteService
import dev.betrix.superSmashMobsBrawl.services.MinigameService
import gg.flyte.twilight.event.event
import java.io.File
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Plays a long run of bot-only matches with items on, several at a time and one after another,
 * and has [ItemStats] record what the items did in them: the raw material of an item balance
 * report ([dev.betrix.superSmashMobsBrawl.items.ItemReport]).
 *
 * Every match is a free-for-all on the next map of a shuffled list, as built and reversed in turn,
 * with random kits. A match that will not start is passed over; one that will not end (two bots
 * that cannot get at each other) is stopped after [Plan.limitSeconds] and marked, so that it counts
 * for what its items did and not for who "won" it.
 */
object BalanceRunner : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val dataService: DataService by inject()
    private val minigameService: MinigameService by inject()

    class Plan(val run: String, val matches: Int, val concurrent: Int, val players: Int, val difficulty: BotDifficulty?,
               val equalDrops: Boolean, val intervalSeconds: Int, val lives: Int, val without: Set<ArenaItem> = emptySet(),
               val limitSeconds: Int = 600)

    private class Match(val bots: List<Player>, val lobby: Int, val map: String, val reversed: Boolean, val askedTick: Int) {
        var game: BrawlMinigame? = null
        var beganTick = 0
        var ended = false
        var stopAsked = false
    }

    private var plan: Plan? = null
    private val going = mutableListOf<Match>()
    private var asked = 0
    private var finished = 0
    private var failed = 0
    private var timedOut = 0
    private var maps: List<String> = emptyList()
    private var task: BukkitTask? = null
    private var listening = false
    private var lastSaid = 0

    val running: Boolean get() = plan != null

    /**
     * Debug: kits the first fighters of every match play, the rest being left to chance. For
     * looking at how the bots do with one kit or two (every match then has one of each) without
     * waiting for chance to deal them out: with 25 kits, one turns up in about a third of matches.
     */
    @Volatile var fixedKits: List<String> = emptyList()

    /** Where a run's record is kept. */
    fun file(run: String) = File(File(plugin.dataFolder, "item-stats"), "$run.csv")

    fun start(wanted: Plan): String {
        if (plan != null) return "A balance run is already going: ${plan!!.run}."
        dataService.getMinigame("super_smash_mobs") ?: return "The game is not loaded."
        maps = dataService.getAllGameMaps().filter { MapVoteService.capacity(it) >= wanted.players }.map { it.id }.shuffled()
        if (maps.isEmpty()) return "No map has room for ${wanted.players}."
        ItemStats.open(file(wanted.run))
        plan = wanted
        going.clear(); asked = 0; finished = 0; failed = 0; timedOut = 0; lastSaid = Bukkit.getCurrentTick()
        if (!listening) { listening = true; event<MinigameEndAnalyticsEvent> { ended(this) } }
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { step() }, 20L, 20L)
        return "Balance run ${wanted.run}: ${wanted.matches} matches of ${wanted.players}, ${wanted.concurrent} at a time, on ${maps.size} maps."
            .also { plugin.logger.info("[balance] $it") }
    }

    fun stop(why: String) {
        val current = plan ?: return
        task?.cancel(); task = null
        plan = null
        ItemStats.close()
        plugin.logger.info("[balance] finished ${current.run} ($why): $finished matches done, $timedOut of them stopped for going on too long, " +
            "$failed could not start; recorded in ${file(current.run).path}")
    }

    private fun ended(e: MinigameEndAnalyticsEvent) {
        val match = going.firstOrNull { it.game === e.minigame } ?: return
        if (match.ended) return
        match.ended = true
        ItemStats.row(e.minigame, "end", value = e.duration.inWholeSeconds.toDouble(),
            extra = (listOf(e.endReason) + e.winners.map(BotManager::displayNameOf)).joinToString("|"))
        ItemStats.flush()
    }

    private fun step() {
        val current = plan ?: return
        val now = Bukkit.getCurrentTick()
        val each = going.iterator()
        while (each.hasNext()) {
            val match = each.next()
            val game = match.game
            when {
                game == null -> {
                    val found = match.bots.firstNotNullOfOrNull { minigameService.getMinigameForPlayer(it) }
                    if (found != null) {
                        match.game = found; match.beganTick = now
                        ItemStats.row(found, "start", value = found.allPlayers().size.toDouble(), extra = listOf(match.map, if (match.reversed) "rev" else "normal",
                            current.difficulty?.name?.lowercase() ?: "mixed", if (current.equalDrops) "equal" else "real",
                            "${current.intervalSeconds}s", "${current.lives} lives").joinToString("|"))
                        found.allPlayers().mapNotNull { it.player }.forEach { fighter ->
                            ItemStats.row(found, "player", actor = fighter,
                                extra = KitService.getKitForPlayer(fighter)?.id ?: KitService.currentSelectedKitForPlayer(fighter).id)
                        }
                    } else if (now - match.askedTick > 300) {
                        failed++
                        plugin.logger.info("[balance] a match on ${match.map} did not start: passed over")
                        match.bots.forEach { if (it.isOnline) BotManager.despawn(it) }
                        each.remove()
                    }
                }
                !match.ended -> {
                    if (!match.stopAsked && now - match.beganTick > current.limitSeconds * 20) {
                        match.stopAsked = true; timedOut++
                        plugin.logger.info("[balance] the match on ${match.map} has gone on ${current.limitSeconds} seconds: stopped")
                        game.endMinigame("balance_timeout")
                    }
                    // (An ending nobody announced: it is over all the same.)
                    if (!match.ended && game.getState() == MinigameState.ENDED && now - match.beganTick > 200) {
                        match.ended = true
                        ItemStats.row(game, "end", value = (now - match.beganTick) / 20.0, extra = if (match.stopAsked) "balance_timeout" else "unannounced")
                    }
                }
                // Over: once its fighters are out of it and its lobby is free again, they leave and the next match can have their names.
                match.bots.none { it.isOnline && minigameService.isPlayerInMinigame(it) } && !LobbyService.busy(match.lobby) -> {
                    match.bots.forEach { if (it.isOnline) BotManager.despawn(it) }
                    each.remove()
                    finished++
                }
            }
        }
        if (finished >= current.matches) { if (going.isEmpty()) stop("all played"); return }
        if (failed > current.matches) { if (going.isEmpty()) stop("too many matches could not start"); return }
        // One new match a second at most, so that their worlds are not all made in the same tick.
        if (going.size < current.concurrent && finished + going.size < current.matches) begin(current, now)
        if (now - lastSaid >= 600) {
            lastSaid = now
            plugin.logger.info("[balance] ${current.run}: $finished of ${current.matches} matches done ($timedOut stopped, $failed could not start), " +
                "${going.size} going with ${going.sumOf { it.bots.size }} bots; a tick takes ${"%.1f".format(Bukkit.getAverageTickTime())} ms")
        }
    }

    private fun begin(current: Plan, now: Int) {
        val def = dataService.getMinigame("super_smash_mobs") ?: return
        val lobby = LobbyRouting.ids.firstOrNull { id -> !LobbyService.busy(id) && going.none { it.lobby == id } } ?: return
        val map = maps[asked % maps.size]
        val reversed = asked % 2 == 1
        val setup = BotNavGen.shapeOf(if (reversed) "$map@rev" else map).apply(GameSetupService.Setup(
            difficulty = current.difficulty, players = current.players, items = true, crystals = false,
            itemIntervalSeconds = current.intervalSeconds, lives = current.lives,
            enabledItems = ArenaItem.entries.toSet() - current.without, equalItemDrops = current.equalDrops))
        val bots = BotManager.startBotGame(def, current.players, current.difficulty, map, kits = fixedKits, lobby = lobby, setup = setup, anyProfile = true)
        asked++
        if (bots.size < current.players) {
            // Not enough names free on the roster just now: these go back, and it is tried again in a second.
            bots.forEach { if (it.isOnline) BotManager.despawn(it) }
            return
        }
        going += Match(bots, lobby, map, reversed, now)
    }
}
