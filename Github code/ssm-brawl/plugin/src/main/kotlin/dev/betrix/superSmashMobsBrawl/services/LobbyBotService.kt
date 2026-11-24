package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.bots.BotManager
import dev.betrix.superSmashMobsBrawl.bots.BotIdentity
import dev.betrix.superSmashMobsBrawl.bots.LobbyRoster
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Team
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

/** A shared preview group per lobby, controlled by the last waiting player to change its setup. */
object LobbyBotService : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val games: MinigameService by inject()
    private val hosts = mutableMapOf<Int, UUID>()
    private val transferringUntil = mutableMapOf<Int, Long>()
    private val labels = mutableMapOf<UUID, TextDisplay>()
    /** Per lobby: the bots of the match just played that are staying for another, and until when that counts. */
    private val stayers = mutableMapOf<Int, Pair<List<String>, Long>>()
    /** Per lobby: the bots of the match just played that walked off after it, and until when they are not the ones brought in to fill it up. */
    private val leavers = mutableMapOf<Int, Pair<List<String>, Long>>()
    /** Lobbies a match has just been played out of, whose players are not back yet: [stayers] is who they expect to find there. */
    private val returning = mutableSetOf<Int>()
    /** Per lobby: the bots whoever is waiting there has seen standing in it, by name. A change to it is a "Join>" or a "Quit>" ([comings]). */
    private val shown = mutableMapOf<Int, List<String>>()
    private val lang: LangService by inject()
    private var task: BukkitTask? = null
    private var ticks = 0L
    private var reconciling = false

    fun setup() {
        if (task != null) return
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            ticks += 2
            if (ticks % 10 == 0L) reconcile()
            updateNames()
        }, 20L, 2L)
    }

    fun botsIn(lobbyId: Int): List<Player> = BotManager.onlineBots().filter {
        BotManager.isLobbyBot(it) && LobbyService.lobbyOf(it) == lobbyId
    }.sortedBy { BotManager.displayNameOf(it).lowercase() }

    /** Whose setup lobby [id] is showing at the moment. */
    fun hostOf(id: Int): UUID? = hosts[id]

    /**
     * The match played out of lobby [id] is over, and these of its bots are not leaving: when
     * everybody is back they are the ones standing there, before anybody new is brought in. (A
     * lobby that is all strangers after every match is no lobby.) [left] are those that walked off:
     * somebody else comes in for each of them, with a "Join>" line ([comings]).
     */
    fun staying(id: Int, names: List<String>, left: List<String> = emptyList()) {
        stayers[id] = names to ticks + STAYS_TICKS
        leavers[id] = left to ticks + STAYS_TICKS
        returning += id
    }

    /**
     * The bots of lobby [id] have changed while somebody stood there to see it, from [before] to
     * who is there now: those people are told who joined and who quit, in the server's own words
     * for it. Until 2026-11-24 a bot was simply there or not there: one walked in after a match and
     * said "hi all" with no "Join>" before it, which nobody on a server does.
     */
    private fun comings(id: Int, before: List<String>) {
        val (joined, left) = LobbyRoster.comings(before, botsIn(id).map(BotManager::displayNameOf))
        var after = 0L
        fun tell(name: String, key: String, stillTrue: () -> Boolean) {
            val line = Runnable {
                if (!stillTrue()) return@Runnable
                if (BotManager.debug) plugin.logger.info("[chatdbg] (lobby $id) ${if (key == "joinServer") "Join" else "Quit"}> $name")
                val message = lang.t("messages.players.$key") { "playerName" to name }
                LobbyService.hub(id)?.world?.players.orEmpty().filter { !BotManager.isBot(it) && !games.isPlayerInMinigame(it) }.forEach { it.sendMessage(message) }
            }
            // (People do not all arrive in the same instant.)
            if (after == 0L) line.run() else Bukkit.getScheduler().runTaskLater(plugin, line, after)
            after += (8..30).random()
        }
        fun here(name: String) = botsIn(id).any { BotManager.displayNameOf(it).equals(name, true) }
        left.forEach { name -> tell(name, "quitServer") { !here(name) } }
        joined.forEach { name -> tell(name, "joinServer") { here(name) } }
    }

    /** Somebody a lobby is kept for: a real player, or a bot standing in for one in a test. */
    private fun person(p: Player) = !BotManager.isBot(p) || p.uniqueId in BotManager.lobbyStandIns

    fun configure(player: Player) {
        if (BotManager.isBot(player) || !HubService.isPlayerInHub(player) || games.isPlayerInMinigame(player)) return
        hosts[LobbyService.lobbyOf(player)] = player.uniqueId
        reconcile()
    }

    private fun reconcile() {
        if (reconciling || LobbyService.allHubs().size != 5 || BotManager.rosterProfiles().isEmpty()) return
        reconciling = true
        try {
            for (id in LobbyRouting.ids) {
                val hub = LobbyService.hub(id) ?: continue
                val waiting = hub.world.players.filter { person(it) && !games.isPlayerInMinigame(it) }
                if (waiting.isEmpty() || LobbyService.busy(id)) {
                    botsIn(id).forEach(BotManager::despawn)
                    if (waiting.isEmpty()) hosts.remove(id)
                    if (LobbyService.busy(id)) transferringUntil.remove(id)
                    // (Nobody is looking: whoever walks in next finds the bots that are there, without a word about it.)
                    shown.remove(id)
                    if (stayers[id]?.let { it.second > ticks } != true) returning -= id
                    continue
                }
                if ((transferringUntil[id] ?: 0) > ticks && BotManager.onlineBots().any {
                    LobbyService.lobbyOf(it) == id && BotManager.isReserved(it)
                }) continue
                transferringUntil.remove(id)
                val host = waiting.find { it.uniqueId == hosts[id] } ?: waiting.first().also { hosts[id] = it.uniqueId }
                // Who the people waiting here know to be about: the bots they have watched standing
                // in this lobby, or, just back from a match, those of it that said they would stay.
                val before = shown[id] ?: stayers[id]?.takeIf { id in returning && it.second > ticks }?.first
                returning -= id
                reconcileGroup(id, GameSetupService.setupOf(host))
                if (before != null) comings(id, before)
                shown[id] = botsIn(id).map(BotManager::displayNameOf)
            }
            refreshVisibility()
        } finally { reconciling = false }
    }

    private fun reconcileGroup(id: Int, settings: GameSetupService.Setup) {
        val built = BotManager.rosterProfiles()
        val custom = CustomBotService.profiles()
        val profiles = (built + custom).associateBy { it.name.lowercase() }
        val current = botsIn(id)
        // Whoever is standing here already stays; after a match, so do the bots that said they would.
        val back = stayers[id]?.takeIf { it.second > ticks }?.first.orEmpty()
        val desired = LobbyRoster.choose(settings.bots, settings.wantedBotNames,
            current.map(BotManager::displayNameOf) + back, built.shuffled().map { it.name }, custom.map { it.name },
            gone = leavers[id]?.takeIf { it.second > ticks }?.first.orEmpty())
        val chosen = desired.mapNotNull { profiles[it.lowercase()] }.map { profile ->
            val difficulty = if (CustomBotService.isCustom(profile.name)) profile.difficulty else settings.difficulty ?: profile.difficulty
            profile.copy(difficulty = difficulty)
        }
        val removed = current.filter { bot -> chosen.none { it.name.equals(BotManager.displayNameOf(bot), true) &&
            it.difficulty == BotManager.profileOf(bot)?.difficulty } }
        val positions = ArrayDeque(removed.map { it.location })
        removed.forEach(BotManager::despawn)
        // Reclaim only this lobby's ended-match leftovers; never another lobby's previews or fighters,
        // nor the bots of a test match that is about to start out of this lobby (cleared away in the
        // second before it began, such a match "did not start": 2026-11-24, with all five lobbies in use).
        BotManager.onlineBots().filter { !BotManager.isLobbyBot(it) && !BotManager.isTestBot(it) && LobbyService.lobbyOf(it) == id && it.uniqueId !in BotManager.lobbyStandIns &&
            it.world == LobbyService.hub(id)?.world && !BotManager.isReserved(it) }.forEach(BotManager::despawn)
        for (profile in chosen) {
            if (botsIn(id).any { BotManager.displayNameOf(it).equals(profile.name, true) }) continue
            BotManager.spawn(profile.copy(level = BotIdentity.levelOf(profile.name, profile.difficulty)), null, id)?.let {
                // A changed name/skin replaces the old lobby neighbour where they were standing.
                positions.removeFirstOrNull()?.let(it::teleport)
                it.inventory.clear(); it.inventory.heldItemSlot = 0
            }
        }
    }

    /** Use the same players the host just saw, rather than silently replacing random opponents. */
    fun takeForMatch(host: Player, count: Int): List<Player> {
        val id = LobbyService.lobbyOf(host)
        if (LobbyService.busy(id)) return emptyList()
        hosts[id] = host.uniqueId
        val settings = GameSetupService.setupOf(host)
        reconcileGroup(id, settings)
        val available = botsIn(id)
        val selected = settings.wantedBotNames.mapNotNull { name -> available.find { BotManager.displayNameOf(it).equals(name, true) } }
        val chosen = (selected + available.filter { it !in selected }).take(count)
        if (chosen.size != count) return emptyList()
        transferringUntil[id] = ticks + 420 // Queue fillers can join gradually for up to 15 opponents.
        chosen.forEach { BotManager.claimLobbyBot(it, settings.botKits[BotManager.displayNameOf(it).lowercase()]) }
        available.filter { it !in chosen }.forEach(BotManager::despawn)
        refreshVisibility()
        return chosen
    }

    private fun refreshVisibility() {
        for (viewer in Bukkit.getOnlinePlayers().filterNot(BotManager::isBot)) {
            for (bot in BotManager.onlineBots()) {
                if (BotManager.isLobbyBot(bot) && viewer.world != bot.world) viewer.hidePlayer(plugin, bot)
                else viewer.showPlayer(plugin, bot)
            }
        }
    }

    /** Login names must be unique, but duplicate roster choices in different lobbies still show the real name. */
    private fun updateNames() {
        val aliased = BotManager.onlineBots().filter { it.name != BotManager.displayNameOf(it) }.associateBy { it.uniqueId }
        labels.entries.removeIf { (id, label) -> if (id !in aliased) { label.remove(); true } else false }
        for ((id, bot) in aliased) {
            val at = bot.location.add(0.0, bot.height + .35, 0.0)
            val label = labels.getOrPut(id) { bot.world.spawn(at, TextDisplay::class.java) { d ->
                d.isPersistent = false; d.billboard = Display.Billboard.CENTER
                d.text(Component.text(BotManager.displayNameOf(bot), NamedTextColor.YELLOW))
                d.isShadowed = true; d.isSeeThrough = false; d.viewRange = 1f
                d.teleportDuration = 2
            } }
            label.teleport(at)
            // In a match of two teams the name is in the colour of the bot's side, like everybody else's.
            label.text(Component.text(BotManager.displayNameOf(bot), ClassicChatService.teamColor(id) ?: NamedTextColor.YELLOW))
            for (viewer in Bukkit.getOnlinePlayers().filterNot(BotManager::isBot)) {
                if (viewer.canSee(bot) && !bot.isInvisible && !dev.betrix.superSmashMobsBrawl.items.ItemService.hidden(bot))
                    viewer.showEntity(plugin, label) else viewer.hideEntity(plugin, label)
            }
        }
        if (ticks % 10 != 0L) return
        val names = aliased.values.map { it.name }.toSet()
        for (viewer in Bukkit.getOnlinePlayers().filterNot(BotManager::isBot)) {
            val board = viewer.scoreboard
            val team = board.getTeam("ssm_bot_names") ?: board.registerNewTeam("ssm_bot_names")
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER)
            (team.entries - names).forEach(team::removeEntry)
            names.forEach(team::addEntry)
        }
    }

    /** How long after a match its bots still count as "staying": long enough for everybody to get back. */
    private const val STAYS_TICKS = 20L * 120

    fun teardown() {
        stayers.clear(); leavers.clear(); returning.clear(); shown.clear()
        task?.cancel(); task = null
        labels.values.forEach { it.remove() }; labels.clear()
        Bukkit.getOnlinePlayers().forEach { viewer ->
            viewer.scoreboard.getTeam("ssm_bot_names")?.unregister()
            BotManager.onlineBots().forEach { viewer.showPlayer(plugin, it) }
        }
        hosts.clear(); transferringUntil.clear()
    }
}
