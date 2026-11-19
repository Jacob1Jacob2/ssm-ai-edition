package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.bots.BotDifficulty
import dev.betrix.superSmashMobsBrawl.bots.BotManager
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.minigames.MinigameState
import dev.betrix.superSmashMobsBrawl.utils.ClassicSidebar
import dev.betrix.superSmashMobsBrawl.utils.SidebarLayout
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
    /** Pixels between the end of the widest entry of a column and the start of the column beside it. */
    private const val COLUMN_GAP = 9

    private val boards = HashMap<UUID, Pair<BrawlMinigame, Scoreboard>>()
    private val lobbyBoards = HashMap<UUID, Scoreboard>()
    private val lobbyCodes = HashMap<UUID, String>()

    private data class EndingBoard(val board: Scoreboard, val world: UUID, val expires: Long)
    private val endingBoards = HashMap<UUID, EndingBoard>()

    fun showEnding(game: BrawlMinigame, viewer: Player) {
        if (BotManager.isBot(viewer)) return
        val board = createBoard()
        val rows = game.teams.flatMap { team -> team.players.map { Row(team.metadata["stocks"] as? Int ?: 0, it.name ?: "Player", game.sideOf(it)) } }
            .filter { (it.lives ?: 0) > 0 }
            .sortedWith(ROW_ORDER)
        val lines = mutableListOf<Component>(Component.empty(), text("Players", NamedTextColor.YELLOW, true), Component.empty())
        rows.take(10).forEach { lines += cell(it, it.name) }
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

    /** One fighter on the board: lives left (none to show in the sandbox), name, and side in a match of two teams. */
    private data class Row(val lives: Int?, val name: String, val side: dev.betrix.superSmashMobsBrawl.minigames.TeamSide?)

    /** Blue before Red where there are sides; within a side, and in every other mode, most lives first. */
    private val ROW_ORDER = compareBy<Row> { it.side?.ordinal ?: 0 }.thenByDescending { it.lives ?: 0 }.thenBy { it.name.lowercase() }

    /**
     * A fighter's line. In a match of two teams it is in the colour of the side, blue or red, so
     * the board shows at a glance who is with whom; in every other mode it is in the colour of the
     * lives left.
     */
    private fun cell(row: Row, label: String): Component = when {
        row.lives == null -> text(label, row.side?.color ?: NamedTextColor.WHITE)
        row.side != null -> text("${row.lives} $label", row.side.color)
        else -> livesRow("${row.lives} $label", row.lives)
    }

    private fun versus(left: String, leftColor: NamedTextColor, right: String, rightColor: NamedTextColor): Component =
        text(left, leftColor).append(text("v", NamedTextColor.WHITE)).append(text(right, rightColor))

    /** The mode as the sidebar words it, the numbers of a 3v1 or a 2v2 in the colours of their sides. */
    private fun modeText(mode: MatchMode, players: Int, random: Boolean = false): Component = when {
        // On Random players the sizes of the sides are not known until the match starts.
        random && mode == MatchMode.TEAMS -> text("Teams", NamedTextColor.BLUE)
        random && mode == MatchMode.BOTS_VS_YOU -> text("All", NamedTextColor.RED).append(text(" v ", NamedTextColor.WHITE)).append(text("1", NamedTextColor.GREEN))
        !random && players <= 1 -> text("Sandbox", NamedTextColor.AQUA)
        !random && (players == 2 || mode == MatchMode.BOTS_VS_YOU) -> versus("${players - 1}", NamedTextColor.RED, "1", NamedTextColor.GREEN)
        !random && mode == MatchMode.TEAMS && players >= 4 -> versus("${players / 2}", NamedTextColor.BLUE, "${players - players / 2}", NamedTextColor.RED)
        mode == MatchMode.TEAM_UPS -> text("Team-Ups", NamedTextColor.GOLD)
        else -> text("FFA", NamedTextColor.GREEN)
    }

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
        val mode = modeText(setup.matchMode, setup.players, setup.playersRandom)
        val lives = livesRow("${setup.lives} ${if (setup.lives == 1) "Life" else "Lives"}", setup.lives)
        val lines = listOf(
            Component.empty(),
            text("Players", NamedTextColor.YELLOW, bold = true),
            when {
                setup.playersRandom -> text("Random (You + AI)  ", NamedTextColor.LIGHT_PURPLE).append(lives)
                // No lives in the sandbox: nothing to lose there.
                setup.sandbox -> text("Just you", NamedTextColor.WHITE)
                else -> text("${setup.players} (You + ${setup.bots} AI)  ", NamedTextColor.WHITE).append(lives)
            },
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
        writeLines(board, matchLines(game, ResourcePackService.hasPack(viewer)))
    }

    /** The lines of the sidebar of a match that is on, top to bottom, for a client with or without the pack. */
    internal fun matchLines(game: BrawlMinigame, pack: Boolean): List<Component> {
        val everyone = game.allPlayers()
        // The game's settings are whatever its (first) real player picked in the hub.
        val setup = game.settings

        // Everyone still in it: most lives first, and in a match of two teams one side after the other.
        val rows =
            game.teams
                .flatMap { team ->
                    val lives = (team.metadata["stocks"] as? Int).takeIf { !game.sandbox }
                    team.players.map { p -> Row(lives, p.name ?: "?", game.sideOf(p)) }
                }
                .filter { it.lives == null || it.lives > 0 }
                .filter { row ->
                    Bukkit.getPlayerExact(row.name)?.let { it.gameMode != GameMode.SPECTATOR || game.respawnManager.isRespawning(it) } ?: false
                }
                .sortedWith(ROW_ORDER)

        // A sidebar holds fifteen lines. The settings and everyone still alive are always on it: a
        // crowded match puts the settings two to a line, then the fighters in two columns, each
        // column starting at the same place on every line (see SidebarLayout).
        val shape = SidebarLayout.of(rows.size)

        val items = ClassicSidebar.spawnSetting("Items", setup.items, setup.itemIntervalSeconds, GameSetupService.ITEM_INTERVALS, false)
        val smash = ClassicSidebar.spawnSetting("Smash", setup.crystals && setup.enabledCrystalTiers.isNotEmpty(),
            setup.crystalIntervalSeconds, GameSetupService.CRYSTAL_INTERVALS, true)
        val difficulty = text("Difficulty: ", NamedTextColor.WHITE).append(text(setup.difficultyName, if(setup.botsIdle)NamedTextColor.GRAY else difficultyColor(setup.difficulty)))
        // By what the match is, not by what was asked for: two teams only where there are sides.
        val mode = text("Mode: ", NamedTextColor.WHITE).append(modeText(
            if (game.sides.isNotEmpty()) MatchMode.TEAMS else setup.matchMode.takeIf { it != MatchMode.TEAMS } ?: MatchMode.FFA, everyone.size))

        fun entry(row: Row): Component = cell(row, Bukkit.getPlayerExact(row.name)?.let { BotManager.displayNameOf(it) } ?: row.name)
        // In a match of two teams a column is a side, Blue on the left; otherwise the list runs
        // left to right and down, most lives first, as a single column would.
        val (left, right) =
            if (shape.columns == 1) rows to emptyList()
            else if (game.sides.isNotEmpty()) rows.partition { it.side == dev.betrix.superSmashMobsBrawl.minigames.TeamSide.BLUE }
            else SidebarLayout.columns(rows.size).let { (l, r) -> l.map(rows::get) to r.map(rows::get) }
        val cells = left.map(::entry)
        // One place for the second column to start, for the settings and the fighters alike:
        // just past the widest thing that stands in the first.
        val column = COLUMN_GAP + maxOf(
            if (shape.compactSettings) maxOf(EnergyHud.widthOf(items), EnergyHud.widthOf(difficulty)) else 0,
            if (shape.columns == 2) cells.maxOfOrNull(EnergyHud::widthOf) ?: 0 else 0)

        val lines = mutableListOf<Component>()
        if (!shape.compactSettings) lines += listOf(items, smash, difficulty, mode)
        else {
            lines += EnergyHud.beside(items, smash, column, pack)
            lines += EnergyHud.beside(difficulty, mode, column, pack)
        }
        lines += text(ClassicSidebar.DIVIDER, NamedTextColor.GREEN).let {
            if (pack) it.append(Component.text("\uEFFF")).font(ResourcePackService.SIDEBAR_DIVIDER_FONT) else it
        }
        lines += text("Players", NamedTextColor.YELLOW, bold = true).let {
            if (pack) it.font(ResourcePackService.SIDEBAR_HEADING_FONT) else it
        }
        lines += Component.empty()
        fun playerLine(line: Component) = if (pack) line.font(ResourcePackService.SIDEBAR_PLAYER_FONT) else line
        if (shape.columns == 1) cells.forEach { lines += playerLine(it) }
        else for (i in 0 until minOf(maxOf(left.size, right.size), SidebarLayout.FIGHTER_LINES)) lines += playerLine(
            EnergyHud.beside(cells.getOrNull(i) ?: Component.empty(), right.getOrNull(i)?.let(::entry) ?: Component.empty(), column, pack))
        lines += Component.empty()
        lines += text(FOOTER, NamedTextColor.GOLD)
            .append(text(" ${game.displayCode}", NamedTextColor.DARK_GRAY)).let {
            if (pack) it.font(ResourcePackService.SIDEBAR_FOOTER_FONT) else it
        }
        return lines
    }

    /**
     * Debug: the sidebar of [game] as text, one string per line. A gap made with the pack's spacer
     * glyphs is written as the pixel the text after it starts at, in angle brackets: the same
     * number down a column is that column standing straight.
     */
    fun describe(game: BrawlMinigame): List<String> {
        val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        return matchLines(game, pack = true).map { line ->
            val parts = line.children()
            val spacer = parts.getOrNull(1)?.takeIf { it.font() == EnergyHud.FONT }
            if (parts.size == 3 && spacer != null) {
                val gap = plain.serialize(spacer).single().code - 0xF000 - 512
                "${plain.serialize(parts[0])} <${EnergyHud.widthOf(parts[0]) + gap}> ${plain.serialize(parts[2])}"
            } else plain.serialize(line)
        }
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
