package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.bots.BotDifficulty
import dev.betrix.superSmashMobsBrawl.bots.BotManager
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.minigames.MinigameState
import dev.betrix.superSmashMobsBrawl.utils.ClassicSidebar
import io.papermc.paper.scoreboard.numbers.NumberFormat
import java.util.UUID
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * SSM AI Edition: the in-game sidebar.
 *
 *   SUPER SMASH MOBS
 *   Items: Off
 *   Smash: Off
 *   Difficulty: Expert
 *   Mode: 7v1
 *   ==================
 *   Players
 *   4 CringeInducing        <- lives left: green 4, yellow 3, gold 2, red 1
 *   3 Deidara
 *   2 You
 *   AI Edition
 *
 * One board per real player, so in big games the list can always include their own line.
 */
object ScoreboardService : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val minigameService: MinigameService by inject()
    private val lang: LangService by inject()

    private const val FOOTER = "AI Edition"
    /** Nine fixed lines include the settings, section spacing, and footer. */
    private const val MAX_PLAYER_LINES = 6
    /** Player lines that fit once the settings block is hidden (heading, two blanks and the footer stay). */
    private const val MAX_PLAYER_LINES_ALONE = 11

    private val boards = HashMap<UUID, Pair<BrawlMinigame, Scoreboard>>()
    private val lobbyBoards = HashMap<UUID, Scoreboard>()
    private val lobbyCodes = HashMap<UUID, String>()

    private data class EndingBoard(val board: Scoreboard, val world: UUID, val expires: Long)
    private val endingBoards = HashMap<UUID, EndingBoard>()

    fun showEnding(game: BrawlMinigame, viewer: Player) {
        if (BotManager.isBot(viewer)) return
        val board = createBoard()
        val rows = game.teams.flatMap { team -> team.players.map { (team.metadata["stocks"] as? Int ?: 0) to (it.name ?: "Player") } }
            .filter { it.first > 0 }
            .sortedWith(compareByDescending<Pair<Int, String>> { it.first }.thenBy { it.second })
        val lines = mutableListOf<Component>(Component.empty(), text("Players", NamedTextColor.YELLOW, true), Component.empty())
        rows.take(10).forEach { (lives, name) -> lines += livesRow("$lives $name", lives) }
        lines += Component.empty()
        lines += text(FOOTER, NamedTextColor.GOLD).append(text(" ${game.displayCode}", NamedTextColor.DARK_GRAY))
        writeLines(board, lines)
        endingBoards[viewer.uniqueId] = EndingBoard(board, viewer.world.uid, System.currentTimeMillis() + 8500)
        viewer.scoreboard = board
    }

    private var titleFrame = 0
    private var titleTicks = 0L

    fun setup() {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { update() }, 20L, 10L)
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { animateTitle() }, 20L, 1L)
    }

    private fun titleComponent(frame: Int): Component = ClassicSidebar.title(frame)

    private fun animateTitle() {
        if (boards.isEmpty() && endingBoards.isEmpty()) return
        titleTicks++
        titleFrame = ClassicSidebar.frameAtTick(titleTicks)
        boards.values.forEach { (game, board) -> updateTitle(board, game.getState() == MinigameState.STARTING) }
        endingBoards.values.forEach { updateTitle(it.board, false) }
    }

    private fun updateTitle(board: Scoreboard, starting: Boolean) {
        val heading = if(starting) ClassicSidebar.COUNTDOWN_TITLE else ClassicSidebar.TITLE
        val title = ClassicSidebar.title(ClassicSidebar.frameAtTick(titleTicks, heading), heading)
        val objective = board.getObjective("ssm") ?: return
        if(objective.displayName() != title)objective.displayName(title)
    }

    private fun text(s: String, color: TextColor, bold: Boolean = false) =
        Component.text(s, color).decoration(TextDecoration.BOLD, bold)

    /** A player row in the colour of their lives; eight lives are a rainbow that keeps moving. */
    private fun livesRow(row: String, lives: Int): Component =
        dev.betrix.superSmashMobsBrawl.utils.CombatFeedback.livesText(row, lives, (System.currentTimeMillis() / 500).toInt())
            .decoration(TextDecoration.BOLD, false)

    private fun difficultyColor(d: BotDifficulty?): NamedTextColor =
        when (d) {
            BotDifficulty.NORMAL -> NamedTextColor.GREEN
            BotDifficulty.HARD -> NamedTextColor.YELLOW
            BotDifficulty.HARDER -> NamedTextColor.GOLD
            BotDifficulty.EXPERT -> NamedTextColor.RED
            null -> NamedTextColor.LIGHT_PURPLE
        }

    private fun update() {
        val viewing = HashSet<UUID>()
        minigameService.activeMinigames().forEach { game ->
            val state = game.getState()
            if (state != MinigameState.STARTING && state != MinigameState.IN_PROGRESS) return@forEach
            game.allPlayers().mapNotNull { it.player }.filter { !BotManager.isBot(it) }.forEach { viewer ->
                viewing += viewer.uniqueId
                val existing = boards[viewer.uniqueId]
                val board =
                    if (existing != null && existing.first === game) existing.second
                    else createBoard().also { boards[viewer.uniqueId] = game to it }
                render(game, viewer, board)
                if (viewer.scoreboard != board) viewer.scoreboard = board
            }
        }
        boards.keys.retainAll(viewing)
        endingBoards.entries.removeIf { (id, ending) ->
            val player = Bukkit.getPlayer(id)
            player == null || id in viewing || player.world.uid != ending.world || System.currentTimeMillis() > ending.expires
        }
        endingBoards.forEach { (id, ending) -> viewing += id; Bukkit.getPlayer(id)?.scoreboard = ending.board }
        val lobbyViewing = HashSet<UUID>()
        Bukkit.getOnlinePlayers().filter {
            !BotManager.isBot(it) && it.uniqueId !in viewing && HubService.isPlayerInHub(it)
        }.forEach { viewer ->
            lobbyViewing += viewer.uniqueId
            val board = lobbyBoards.getOrPut(viewer.uniqueId) { createLobbyBoard() }
            renderLobby(viewer, board)
            if (viewer.scoreboard != board) viewer.scoreboard = board
        }
        lobbyBoards.keys.retainAll(lobbyViewing)
        lobbyCodes.keys.retainAll(lobbyViewing)
        viewing += lobbyViewing
        val main = Bukkit.getScoreboardManager().mainScoreboard
        Bukkit.getOnlinePlayers().forEach { p ->
            if (p.uniqueId !in viewing && p.scoreboard != main && !BotManager.isBot(p)) p.scoreboard = main
        }
    }

    private fun createLobbyBoard(): Scoreboard {
        val board = Bukkit.getScoreboardManager().newScoreboard
        val obj = board.registerNewObjective("ssm", Criteria.DUMMY,
            text("Waiting for players...", NamedTextColor.GREEN, bold = true))
        obj.displaySlot = DisplaySlot.SIDEBAR
        // The waiting-room reference has section spacing, without a score-number column.
        obj.numberFormat(NumberFormat.blank())
        return board
    }

    private fun renderLobby(viewer: Player, board: Scoreboard) {
        val setup = GameSetupService.setupOf(viewer)
        val kit = KitService.currentSelectedKitForPlayer(viewer)
        val code = lobbyCodes.getOrPut(viewer.uniqueId) {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            buildString { repeat(8) { append(alphabet.random()) } }
        }
        val mode = if (setup.allBotsVsPlayer || setup.bots == 1) text("${setup.bots}", NamedTextColor.RED)
            .append(text("v", NamedTextColor.WHITE)).append(text("1", NamedTextColor.GREEN))
            else if (setup.teamUps) text("Team-Ups", NamedTextColor.GOLD)
            else text("FFA", NamedTextColor.GREEN)
        val lines = listOf(
            Component.empty(),
            text("Players", NamedTextColor.YELLOW, bold = true),
            text("${setup.players} (You + ${setup.bots} AI)  ", NamedTextColor.WHITE)
                .append(livesRow("${setup.lives} ${if (setup.lives == 1) "Life" else "Lives"}", setup.lives)),
            Component.empty(),
            text("Map", NamedTextColor.RED, bold = true),
            text(MapVoteService.selectedMapName(viewer), NamedTextColor.WHITE),
            Component.empty(),
            text("Kit", NamedTextColor.GOLD, bold = true),
            if (KitService.isRandomSelected(viewer)) text("Random", NamedTextColor.WHITE) else lang.t("kits.${kit.id}.name").color(NamedTextColor.WHITE),
            Component.empty(),
            text("Options", NamedTextColor.AQUA, bold = true),
            ClassicSidebar.spawnSetting("Items", setup.items, setup.itemIntervalSeconds,
                GameSetupService.ITEM_INTERVALS, false).append(text("  ", NamedTextColor.WHITE))
                .append(ClassicSidebar.spawnSetting("Smash", setup.crystals && setup.enabledCrystalTiers.isNotEmpty(),
                    setup.crystalIntervalSeconds, GameSetupService.CRYSTAL_INTERVALS, true)),
            text("Difficulty: ", NamedTextColor.WHITE)
                .append(text(setup.difficultyName, if (setup.botsIdle) NamedTextColor.GRAY else difficultyColor(setup.difficulty)))
                .append(text("  ", NamedTextColor.WHITE)).append(mode),
            Component.empty(),
            text("SSM AI Edition", NamedTextColor.GOLD).append(text(" $code", NamedTextColor.DARK_GRAY)),
        )
        writeLines(board, lines)
    }

    private fun createBoard(): Scoreboard {
        val board = Bukkit.getScoreboardManager().newScoreboard
        val obj = board.registerNewObjective("ssm", Criteria.DUMMY, titleComponent(titleFrame))
        obj.displaySlot = DisplaySlot.SIDEBAR
        obj.numberFormat(NumberFormat.styled(NamedTextColor.RED))
        return board
    }

    private fun render(game: BrawlMinigame, viewer: Player, board: Scoreboard) {
        val obj = board.getObjective("ssm") ?: return
        updateTitle(board, game.getState() == MinigameState.STARTING)
        val everyone = game.allPlayers()
        if (game.getState() == MinigameState.STARTING) {
            writeLines(board, listOf(Component.empty(), text("Players", NamedTextColor.YELLOW, true),
                text("${everyone.size} Players", NamedTextColor.WHITE), Component.empty(),
                text(FOOTER, NamedTextColor.GOLD).append(text(" ${game.displayCode}", NamedTextColor.DARK_GRAY))))
            return
        }
        val humans = everyone.filter { !BotManager.isBot(it.uniqueId) }
        // The game's settings are whatever its (first) real player picked in the hub.
        val setup = game.settings

        // (lives, name) for everyone still in it, most lives first.
        val rows =
            game.teams
                .flatMap { team ->
                    val lives = team.metadata["stocks"] as? Int
                    team.players.map { p -> lives to (p.name ?: "?") }
                }
                .filter { (lives, _) -> lives == null || lives > 0 }
                .filter { (_, name) ->
                    Bukkit.getPlayerExact(name)?.let { it.gameMode != GameMode.SPECTATOR || game.respawnManager.isRespawning(it) } ?: false
                }
                .sortedWith(compareByDescending<Pair<Int?, String>> { it.first ?: 0 }.thenBy { it.second.lowercase() })

        // A sidebar holds fifteen lines and everyone still alive is always listed. A crowded match
        // first gives up the settings block, then puts two names on a line.
        val showSettings = rows.size <= MAX_PLAYER_LINES
        val perLine = if (rows.size <= MAX_PLAYER_LINES_ALONE) 1 else 2
        val shown = rows.take(MAX_PLAYER_LINES_ALONE * perLine)

        val lines = mutableListOf<Component>()
        if (showSettings) {
            lines += ClassicSidebar.spawnSetting("Items", setup.items, setup.itemIntervalSeconds, GameSetupService.ITEM_INTERVALS, false)
            lines += ClassicSidebar.spawnSetting("Smash", setup.crystals && setup.enabledCrystalTiers.isNotEmpty(),
                setup.crystalIntervalSeconds, GameSetupService.CRYSTAL_INTERVALS, true)
            lines += text("Difficulty: ", NamedTextColor.WHITE).append(text(setup.difficultyName, if(setup.botsIdle)NamedTextColor.GRAY else difficultyColor(setup.difficulty)))
            lines += text("Mode: ", NamedTextColor.WHITE).append(
                if (setup.allBotsVsPlayer || everyone.size == 2) text("${everyone.size - 1}", NamedTextColor.RED)
                    .append(text("v", NamedTextColor.WHITE)).append(text("1", NamedTextColor.GREEN))
                else if (setup.teamUps) text("Team-Ups", NamedTextColor.GOLD)
                else text("FFA", NamedTextColor.GREEN))
            lines += text(ClassicSidebar.DIVIDER, NamedTextColor.GREEN).let {
                if (ResourcePackService.hasPack(viewer)) it.append(Component.text("\uEFFF"))
                    .font(ResourcePackService.SIDEBAR_DIVIDER_FONT) else it
            }
        }
        lines += text("Players", NamedTextColor.YELLOW, bold = true).let {
            if (ResourcePackService.hasPack(viewer)) it.font(ResourcePackService.SIDEBAR_HEADING_FONT) else it
        }
        lines += Component.empty()
        fun cell(lives: Int?, name: String): Component {
            val label = Bukkit.getPlayerExact(name)?.let { BotManager.displayNameOf(it) } ?: name
            return if (lives == null) text(label, NamedTextColor.WHITE) else livesRow("$lives $label", lives)
        }
        shown.chunked(perLine).forEach { pair ->
            var row = cell(pair[0].first, pair[0].second)
            if (pair.size > 1) row = Component.empty().append(row).append(text("   ", NamedTextColor.WHITE))
                .append(cell(pair[1].first, pair[1].second))
            lines += if (ResourcePackService.hasPack(viewer)) row.font(ResourcePackService.SIDEBAR_PLAYER_FONT) else row
        }
        if (shown.size < rows.size) lines += text("+${rows.size - shown.size} more", NamedTextColor.GRAY).let {
            if (ResourcePackService.hasPack(viewer)) it.font(ResourcePackService.SIDEBAR_PLAYER_FONT) else it
        }
        lines += Component.empty()
        lines += text(FOOTER, NamedTextColor.GOLD)
            .append(text(" ${game.displayCode}", NamedTextColor.DARK_GRAY)).let {
            if (ResourcePackService.hasPack(viewer)) it.font(ResourcePackService.SIDEBAR_FOOTER_FONT) else it
        }

        writeLines(board, lines)
    }

    private fun writeLines(board: Scoreboard, lines: List<Component>) {
        val obj = board.getObjective("ssm") ?: return
        lines.forEachIndexed { i, line ->
            val score = obj.getScore("line$i")
            score.score = lines.size - i
            score.customName(line)
        }
        (lines.size until 20).forEach { i -> board.resetScores("line$i") }
    }
}
