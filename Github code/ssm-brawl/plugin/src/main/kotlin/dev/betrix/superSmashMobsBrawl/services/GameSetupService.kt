package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.bots.BotDifficulty
import dev.betrix.superSmashMobsBrawl.bots.BotManager
import dev.betrix.superSmashMobsBrawl.bots.BotPersonality
import dev.betrix.superSmashMobsBrawl.events.QueuePopEvent
import dev.betrix.superSmashMobsBrawl.gui.BrawlGui.Companion.openInventory
import dev.betrix.superSmashMobsBrawl.gui.brawlGui
import io.papermc.paper.datacomponent.DataComponentTypes
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * SSM AI Edition: the hub compass. Pick the bots' difficulty, how many to fight, whether items /
 * smash crystals spawn, then Play starts a Super Smash Mobs game against
 * that many AI bots.
 */
object GameSetupService : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val minigameService: MinigameService by inject()
    private val dataService: DataService by inject()

    private const val MINIGAME_ID = "super_smash_mobs"

    /** Game sizes you can pick, counting yourself (2 = you vs 1 bot). One, the sandbox, is picked apart from these. */
    val PLAYER_COUNTS = listOf(2, 4, 8, 16)

    data class Setup @JvmOverloads constructor(
        var difficulty: BotDifficulty? = BotDifficulty.NORMAL, // null = mixed
        var players: Int = 4,
        var items: Boolean = false,
        var crystals: Boolean = true,
        var itemIntervalSeconds: Int = 12,
        var crystalIntervalSeconds: Int = 180,
        var allBotsVsPlayer: Boolean = false,
        var botsIdle: Boolean = false,
        var selectedBotNames: List<String> = emptyList(),
        var enabledCrystalTiers: Set<dev.betrix.superSmashMobsBrawl.smash.SmashTier> = dev.betrix.superSmashMobsBrawl.smash.SmashTier.entries.toSet(),
        var reverseMap: Boolean = false,
        var reverseRandom: Boolean = false,
        var reflectMode: ReflectMode = ReflectMode.OFF,
        var mapScale: MapScale = MapScale.NORMAL,
        var enabledItems: Set<dev.betrix.superSmashMobsBrawl.items.ArenaItem> = dev.betrix.superSmashMobsBrawl.items.ArenaItem.entries.toSet(),
        var botPersonality: BotPersonality = BotPersonality.SILENT,
        var botKits: Map<String,String> = emptyMap(),
        /** Lives every fighter starts the match with. */
        var lives: Int = 4,
        /** A free-for-all in which the bots may team up on a player who gets on their nerves (see bots/TeamUps). */
        var teamUps: Boolean = false,
        /** Two even teams (2v2, 4v4, 8v8): the player and [teammates] against the rest (see minigames/TeamSides). */
        var teams: Boolean = false,
        /** The bots picked for the player's own team; seats nobody was picked for are filled at random. */
        var teammates: List<String> = emptyList(),
        /** A new number of players (2, 4, 8 or 16) for every match; [players] holds the one rolled for the next. */
        var playersRandom: Boolean = false,
    ) {
        /** How the fighters stand to each other. */
        var matchMode: MatchMode
            get() = if (teams) MatchMode.TEAMS else if (allBotsVsPlayer) MatchMode.BOTS_VS_YOU else if (teamUps) MatchMode.TEAM_UPS else MatchMode.FFA
            set(value) { teams = value == MatchMode.TEAMS; allBotsVsPlayer = value == MatchMode.BOTS_VS_YOU; teamUps = value == MatchMode.TEAM_UPS }

        /** Nobody to fight: the arena to oneself, to try out items and smashes. */
        val sandbox: Boolean
            get() = players <= 1

        /** True when this gives a match of two teams, which takes four fighters or more. */
        val teamGame: Boolean
            get() = teams && players >= 4

        /** What the match is called: FFA, Team-Ups, 3v1, 2v2, 1v1 or Sandbox. */
        val modeLabel: String
            get() = matchMode.label(players)

        /** Seats for bots on the player's own team. */
        val teammateSlots: Int
            get() = if (teamGame) players / 2 - 1 else 0

        /** How many of the bots are opponents. */
        val opponentSlots: Int
            get() = (bots - teammateSlots).coerceAtLeast(0)

        /** The most teammates and opponents that can be picked: on Random the match may turn out its largest. */
        val teammateLimit: Int
            get() = if (playersRandom) PLAYER_COUNTS.max() / 2 - 1 else teammateSlots
        val opponentLimit: Int
            get() = if (!playersRandom) opponentSlots else if (teams) PLAYER_COUNTS.max() / 2 else PLAYER_COUNTS.max() - 1

        /** The picked teammates there is a seat for in a match of this size. */
        val effectiveTeammates: List<String>
            get() = teammates.take(teammateSlots)

        /** The picked opponents there is a seat for. Somebody picked for both is a teammate. */
        val effectiveOpponents: List<String>
            get() = selectedBotNames.filter { name -> effectiveTeammates.none { it.equals(name, true) } }.take(opponentSlots)

        /** Every bot asked for by name, the player's own team first. */
        val wantedBotNames: List<String>
            get() = effectiveTeammates + effectiveOpponents

        var reverseMode: ReverseMode
            get() = if (reverseRandom) ReverseMode.RANDOM else if (reverseMap) ReverseMode.ON else ReverseMode.OFF
            set(value) { reverseRandom = value == ReverseMode.RANDOM; reverseMap = value == ReverseMode.ON }

        fun resolveTransform() = MapTransform(reverseMode.resolve(), reflectMode, mapScale)

        val bots: Int
            get() = players - 1

        val difficultyName: String
            get() = if(botsIdle) "None" else difficulty?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Random"

        fun itemDelaySeconds():Int = if(itemIntervalSeconds<0) kotlin.random.Random.nextInt(3,121) else itemIntervalSeconds
        fun crystalDelaySeconds():Int = if(crystalIntervalSeconds<0) kotlin.random.Random.nextInt(60,901) else crystalIntervalSeconds
    }

    private val setups = ConcurrentHashMap<UUID, Setup>()
    private val starting = ConcurrentHashMap.newKeySet<UUID>()

    private val file: File
        get() = File(plugin.dataFolder, "game-setups.properties")

    private const val FORMAT_KEY = "personality-format"
    private val required = setOf("Difficulty", "Items", "Crystals", "Personality", "Mode", "Players")
    fun settingsChosen(player:Player) = true
    fun setup() {
        if (!file.exists()) return
        val props = Properties()
        file.inputStream().use { props.load(it) }
        // Saves from before Toxic was split off from Salty have no format line: their "Salty" is today's Toxic.
        val legacyPersonalities = (props.getProperty(FORMAT_KEY)?.toIntOrNull() ?: 1) < 2
        props.stringPropertyNames().forEach { key ->
            if (key == FORMAT_KEY) return@forEach
            val parts = props.getProperty(key).split(",")
            if (parts.size < 4) return@forEach
            val savedPlayers = parts[1].toIntOrNull() ?: 4
            setups[UUID.fromString(key)] =
                Setup(
                    difficulty = parts[0].takeIf { it != "mixed" }?.let { BotDifficulty.parse(it) },
                    players = if (savedPlayers <= 1) 1 else PLAYER_COUNTS.minBy { kotlin.math.abs(it - savedPlayers) },
                    items = parts[2].toBoolean(),
                    crystals = parts[3].toBoolean(),
                    itemIntervalSeconds = migrateInterval(parts.getOrNull(4)?.toIntOrNull()?.let { if(parts.size<10) it else when(it) { 9 -> 12; 90 -> 120; else -> it } },listOf(30,60,90,120),ITEM_INTERVALS,parts.size<10),
                    crystalIntervalSeconds = migrateInterval(parts.getOrNull(5)?.toIntOrNull()?.let { when(it) { 540 -> 420; 1200 -> 900; else -> it } },listOf(60,90,120,180),CRYSTAL_INTERVALS,parts.size<10),
                    allBotsVsPlayer = parts.getOrNull(6)?.toBoolean() ?: false,
                    botsIdle = parts.getOrNull(7)?.toBoolean() ?: false,
                    selectedBotNames = parts.getOrNull(8)?.split('|')?.filter {it.isNotBlank()} ?: emptyList(),
                    enabledCrystalTiers = parts.getOrNull(9)?.split('|')?.mapNotNull {id -> dev.betrix.superSmashMobsBrawl.smash.SmashTier.entries.find {it.name==id}}?.toSet()
                        ?: dev.betrix.superSmashMobsBrawl.smash.SmashTier.entries.toSet(),
                    reverseMap = parts.getOrNull(10)?.toBoolean() ?: false,
                    reverseRandom = parts.getOrNull(11)?.toBoolean() ?: false,
                    reflectMode = ReflectMode.entries.find { it.name == parts.getOrNull(12) } ?: ReflectMode.OFF,
                    mapScale = MapScale.entries.find { it.name == parts.getOrNull(13) } ?: MapScale.NORMAL,
                    enabledItems = dev.betrix.superSmashMobsBrawl.items.ArenaItem.restoreSelection(
                        parts.getOrNull(14), parts.getOrNull(15)?.toIntOrNull() ?: 0),
                    botPersonality = BotPersonality.restore(parts.getOrNull(16), legacyPersonalities),
                    botKits = parts.getOrNull(17)?.split('|')?.mapNotNull {entry -> entry.split(':').takeIf {it.size==2}?.let {it[0].lowercase() to it[1]}}?.toMap() ?: emptyMap(),
                    lives = parts.getOrNull(18)?.toIntOrNull()?.coerceIn(1, 8) ?: 4,
                    teamUps = parts.getOrNull(19)?.toBoolean() ?: false,
                    teams = parts.getOrNull(20)?.toBoolean() ?: false,
                    teammates = parts.getOrNull(21)?.split('|')?.filter { it.isNotBlank() } ?: emptyList(),
                    playersRandom = parts.getOrNull(22)?.toBoolean() ?: false,
                )
        }
    }

    fun forgetBot(name:String) {
        setups.values.forEach {s -> s.selectedBotNames=s.selectedBotNames.filterNot {it.equals(name,true)};s.teammates=s.teammates.filterNot {it.equals(name,true)};s.botKits=s.botKits-name.lowercase()}
        save()
    }

    internal fun save() {
        val props = Properties()
        props.setProperty(FORMAT_KEY, "2")
        setups.forEach { (uuid, s) ->
            props.setProperty(
                uuid.toString(),
                "${s.difficulty?.name?.lowercase() ?: "mixed"},${s.players},${s.items},${s.crystals},${s.itemIntervalSeconds},${s.crystalIntervalSeconds},${s.allBotsVsPlayer},${s.botsIdle},${s.selectedBotNames.joinToString("|")},${s.enabledCrystalTiers.sortedBy {it.ordinal}.joinToString("|") {it.name}},${s.reverseMap},${s.reverseRandom},${s.reflectMode.name},${s.mapScale.name},${s.enabledItems.sortedBy { it.ordinal }.joinToString("|") { it.id }},${dev.betrix.superSmashMobsBrawl.items.ArenaItem.CATALOG_VERSION},${s.botPersonality.name},${s.botKits.entries.joinToString("|") {"${it.key}:${it.value}"}},${s.lives},${s.teamUps},${s.teams},${s.teammates.joinToString("|")},${s.playersRandom}",
            )
        }
        file.parentFile.mkdirs()
        file.outputStream().use { props.store(it, "SSM AI Edition - game setup per player") }
    }

    fun setupOf(player: Player): Setup = setups.getOrPut(player.uniqueId) { Setup() }

    private fun plain(text: String, color: NamedTextColor) =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    // ------------------------------------------------------------------ menu

    val ITEM_INTERVALS = listOf(3, 12, 30, 120)
    val CRYSTAL_INTERVALS = listOf(60, 180, 420, 900)
    private fun migrateInterval(saved:Int?,old:List<Int>,current:List<Int>,legacy:Boolean):Int {
        if(saved==-1)return -1
        if(legacy && saved in old)return current[old.indexOf(saved)]
        return saved?.takeIf {it in current} ?: current[1]
    }
    /** A number of players picked by hand (1 is the sandbox). Opponents picked for a bigger match do not all fit a smaller one. */
    private fun choosePlayers(player: Player, s: Setup, count: Int) {
        s.playersRandom = false
        s.players = count
        s.selectedBotNames = s.selectedBotNames.take(s.bots)
        changed(player)
    }

    /** Rolls the number of players of a setup on Random: one of the sizes the chosen map and the server have room for. */
    internal fun rollPlayers(player: Player, s: Setup) {
        val fitting = PLAYER_COUNTS.filter { MapVoteService.hasRoomFor(player, it) && minigameService.capacityRejection(it) == null }
        s.players = fitting.ifEmpty { listOf(PLAYER_COUNTS.first()) }.random()
    }

    private val CHOICE_COLUMNS = listOf(2, 3, 4, 5, 7)
    private val CHOICE_COLORS = listOf(NamedTextColor.GREEN, NamedTextColor.YELLOW, NamedTextColor.GOLD, NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE)
    /** The top row: a block of wool for each number of players, in the colour of its column. */
    private val WOOLS = listOf(Material.LIME_WOOL, Material.YELLOW_WOOL, Material.ORANGE_WOOL, Material.RED_WOOL, Material.MAGENTA_WOOL)
    private val PERSONALITY_COLORS = listOf(NamedTextColor.GRAY, NamedTextColor.GREEN, NamedTextColor.GOLD, NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE)
    private val DYES = listOf(Material.LIME_DYE, Material.YELLOW_DYE, Material.ORANGE_DYE, Material.RED_DYE, Material.MAGENTA_DYE)
    private val CANDLES = listOf(Material.LIME_CANDLE, Material.YELLOW_CANDLE, Material.ORANGE_CANDLE, Material.RED_CANDLE, Material.MAGENTA_CANDLE)
    private val DIFFICULTIES = listOf(BotDifficulty.NORMAL, BotDifficulty.HARD, BotDifficulty.HARDER, BotDifficulty.EXPERT, null)

    internal fun difficultyLabel(difficulty: BotDifficulty): Component = plain(
        difficulty.name.lowercase().replaceFirstChar { it.uppercase() },
        CHOICE_COLORS[DIFFICULTIES.indexOf(difficulty)])

    private fun menuItem(material: Material, name: String, color: NamedTextColor = NamedTextColor.YELLOW,
                         lore: List<String> = emptyList(), selected: Boolean = false): ItemStack =
        ItemStack.of(material).apply {
            editMeta {
                it.displayName(plain(name, color))
                it.lore(lore.map { line -> plain(line, NamedTextColor.GRAY) } +
                    if (selected) listOf(plain("Selected", NamedTextColor.GREEN)) else emptyList())
            }
            setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, selected)
        }

    fun openMenu(player: Player) {
        val s = setupOf(player)
        val chosen = required.toMutableSet()
        val gui = brawlGui(Component.text("Game Settings"), 54, selectionEffects=true) {
            onClick { isCancelled = true
                if(clickedInventory==inventory) {
                    val group=when(slot) {
                        0,9,18,27,36 -> "Personality"
                        10,11,12,13,14,16 -> "Difficulty"
                        19,20,21,22,23,25 -> "Items"
                        28,29,30,31,32,34 -> "Crystals"
                        37,38,39,40 -> "Mode"
                        1,2,3,4,5,7 -> "Players"
                        else -> null
                    }
                    if(group!=null)chosen.add(group)
                }
            }
            for (slot in 0 until 54) set(slot, menuItem(Material.GRAY_STAINED_GLASS_PANE, ""))
            // One white column, top to bottom, between the four fixed choices of a row and its Random.
            for (row in 0..5) set(row * 9 + 6, menuItem(Material.WHITE_STAINED_GLASS_PANE, ""))

            // Top row: how many fighters, yourself included. The size of each stack says the same.
            set(1, menuItem(Material.BARRIER, "No Players", NamedTextColor.RED, listOf("Sandbox: the arena to yourself,", "to try out items and smashes"),
                "Players" in chosen && !s.playersRandom && s.sandbox)) { choosePlayers(player, s, 1) }
            PLAYER_COUNTS.forEachIndexed { i, count ->
                set(CHOICE_COLUMNS[i], menuItem(WOOLS[i], "$count Players", CHOICE_COLORS[i], listOf("You vs ${count - 1} bot${if (count == 2) "" else "s"}"),
                    "Players" in chosen && !s.playersRandom && s.players == count).apply { amount = count }) { choosePlayers(player, s, count) }
            }
            set(CHOICE_COLUMNS[4], menuItem(WOOLS[4], "Random", CHOICE_COLORS[4], listOf("2, 4, 8 or 16 players,", "rolled again for every match"),
                "Players" in chosen && s.playersRandom)) {
                s.playersRandom = true
                rollPlayers(player, s)
                changed(player)
            }

            set(8, menuItem(Material.HOPPER, "Select Lives", lore = listOf("Current: ${s.lives} ${if (s.lives == 1) "life" else "lives"}", "How many lives every fighter starts with"))) { SetupSelectionMenus.lives(player) }
            set(10,menuItem(Material.BARRIER,"None",NamedTextColor.RED,listOf("Bots stand still and do not attack"),"Difficulty" in chosen && s.botsIdle)) {s.botsIdle=true;changed(player)}
            set(17,menuItem(Material.CHEST,"Choose Opponents",lore=listOf("Choose players from the bot roster",if(s.effectiveOpponents.isEmpty())"Random opponents" else "${s.effectiveOpponents.size}/${s.opponentSlots} chosen — random fills the rest"))) {SetupSelectionMenus.opponents(player)}
            DIFFICULTIES.forEachIndexed { i, difficulty ->
                val name = difficulty?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Random"
                set(9 + CHOICE_COLUMNS[i], menuItem(DYES[i], name, CHOICE_COLORS[i],
                    listOf(if (difficulty == null) "Each bot uses a random difficulty" else "Set all bots to $name"), "Difficulty" in chosen && !s.botsIdle && s.difficulty == difficulty)) {
                    s.difficulty = difficulty
                    s.botsIdle = false
                    changed(player)
                }
            }

            set(19,menuItem(Material.BARRIER,"None",NamedTextColor.RED,listOf("Disable item drops"),"Items" in chosen && !s.items)) {
                s.items = false
                changed(player)
            }
            set(26,menuItem(Material.CHEST,"Choose Items",lore=listOf("Toggle which items can spawn"))) {dev.betrix.superSmashMobsBrawl.items.ItemMenus.open(player,true)}
            (ITEM_INTERVALS + -1).forEachIndexed { i, seconds ->
                val name = if (seconds < 0) "Random" else "$seconds seconds"
                set(18 + CHOICE_COLUMNS[i], menuItem(CANDLES[i], name, CHOICE_COLORS[i], listOf(
                    if (seconds < 0) "Any interval from 3 to 120 seconds" else "Item spawn interval: $seconds seconds",
                    "A fresh interval is rolled after each spawn"), "Items" in chosen && s.items && s.itemIntervalSeconds == seconds)) {
                    s.items = true
                    s.itemIntervalSeconds = seconds
                    changed(player)
                }
            }

            set(28,menuItem(Material.BARRIER,"None",NamedTextColor.RED,listOf("Disable Smash Crystal drops"),"Crystals" in chosen && !s.crystals)) {
                s.crystals = false
                changed(player)
            }
            set(35,menuItem(Material.CHEST,"Choose Smash Crystals",lore=listOf("Toggle which crystal tiers can spawn"))) {SetupSelectionMenus.crystals(player)}
            (CRYSTAL_INTERVALS + -1).forEachIndexed { i, seconds ->
                val name = if (seconds < 0) "Random" else "${seconds/60} minute${if(seconds==60) "" else "s"}"
                set(27 + CHOICE_COLUMNS[i], menuItem(Material.NETHER_STAR, name,
                    dev.betrix.superSmashMobsBrawl.utils.ClassicSidebar.intervalColor(seconds, CRYSTAL_INTERVALS), listOf(
                    if (seconds < 0) "Any interval from 1 to 15 minutes" else "Smash Crystal interval: ${seconds/60} minutes",
                    "Controls when the next crystal descends"), "Crystals" in chosen && s.crystals && s.crystalIntervalSeconds == seconds).apply { MenuIcons.star(this,i);SelectionIcons.mark(this,"Crystals" in chosen && s.crystals && s.crystalIntervalSeconds==seconds) }) {
                    s.crystals = true
                    s.crystalIntervalSeconds = seconds
                    changed(player)
                }
            }

            // The personality column, quietest at the top.
            listOf(
                Triple(Material.MINECART, BotPersonality.SILENT, listOf("Bots do not chat")),
                Triple(Material.FURNACE_MINECART, BotPersonality.CHILL, listOf("Friendly: gf, nice combo, its just a game")),
                Triple(Material.CHEST_MINECART, BotPersonality.SALTY, listOf("Complains when things go wrong", "Gets mad at whoever rubs it in")),
                Triple(Material.TNT_MINECART, BotPersonality.TOXIC, listOf("Trash talk: tries to make everyone else mad")),
                // Every bot is dealt its own, so the lobby argues with itself.
                Triple(Material.COMMAND_BLOCK_MINECART, BotPersonality.RANDOM, listOf("Each bot gets its own personality",
                    "They argue, take sides and go after each other", "No more than half are Silent")),
            ).forEachIndexed { row, (material, personality, lore) ->
                set(row * 9, menuItem(material, personality.label, PERSONALITY_COLORS[row], lore,
                    "Personality" in chosen && s.botPersonality == personality)) { s.botPersonality = personality; changed(player) }
            }
            val kit = KitService.currentSelectedKitForPlayer(player)
            set(44, menuItem(Material.ENDER_CHEST, "Change Kit Stats", NamedTextColor.LIGHT_PURPLE, listOf(
                "Health regen, armor, attack damage", "and cooldowns of your selected kit", "Current: ${KitStatsService.verdict(player.uniqueId, kit).label}"))) {
                KitStatsService.openMenu(player)
            }
            // Always in its place: dark while there is nothing to put back, lit once there is.
            if (KitStatsService.changed(player.uniqueId, kit)) set(43, menuItem(Material.COPPER_CHEST, "Reset to Normal Kit Stats", NamedTextColor.GOLD,
                listOf("Puts your selected kit back to normal"))) {
                KitStatsService.reset(player.uniqueId, kit)
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, 1.2f)
                openMenu(player)
            } else set(43, MenuIcons.dark(menuItem(Material.COPPER_CHEST, "Reset to Normal Kit Stats", NamedTextColor.DARK_GRAY,
                listOf("Your kit's stats are normal")))) { unavailable(player) }

            // Fifth row: the mode, one tool each. A mode that cannot be played with this many fighters
            // is there but dark, like the head beside it: all four in the sandbox, where there is
            // nobody to fight, and all but FFA with two players, who can only be a duel.
            // (And a mode that cannot be chosen is not left chosen either. The sandbox has no mode
            // at all, so there the one picked for a real match is kept for when there is one again.)
            if (!s.sandbox && !s.matchMode.available(s.players, s.playersRandom)) { s.matchMode = MatchMode.FFA; save() }
            val mode = s.matchMode
            fun modeButton(slot: Int, material: Material, value: MatchMode, name: String, darkName: String, lore: List<String>) {
                val why = value.unavailable(s.players, s.playersRandom)
                if (why != null) set(slot, MenuIcons.dark(menuItem(material, darkName, NamedTextColor.DARK_GRAY, listOf(why)))) { unavailable(player) }
                else set(slot, menuItem(material, name, NamedTextColor.AQUA, lore, "Mode" in chosen && mode == value)) {
                    s.matchMode = value
                    changed(player)
                }
            }
            modeButton(37, Material.COPPER_AXE, MatchMode.FFA, "FFA", "FFA", listOf("Everybody for themselves"))
            modeButton(38, Material.IRON_AXE, MatchMode.TEAM_UPS, "Team-Ups", "Team-Ups", listOf("FFA, but the bots can team on you", "if you camp or get annoying"))
            modeButton(39, Material.COPPER_SWORD, MatchMode.BOTS_VS_YOU, if (s.playersRandom) "All vs 1" else "${s.bots}v1", "All vs 1",
                listOf("Every bot is on one team against you"))
            modeButton(40, Material.IRON_SWORD, MatchMode.TEAMS, if (s.playersRandom) "Teams" else "${s.players / 2}v${s.players / 2}", "2v2",
                listOf("Two even teams, yours is blue", "Pick your team with the head"))
            // Lit while the two teams mode is chosen, the same way as the chest: who is on your team, and their kits.
            if (s.teams && (s.playersRandom || s.players >= 4)) set(41, menuItem(Material.PLAYER_HEAD, "Your Team", NamedTextColor.BLUE,
                listOf("Choose your teammates", "and the kits they play",
                    if (s.effectiveTeammates.isEmpty()) "Random teammates" else "${s.effectiveTeammates.size}/${s.teammateSlots} chosen — random fills the rest"))) {
                SetupSelectionMenus.team(player)
            } else set(41, MenuIcons.dark(menuItem(Material.PLAYER_HEAD, "Your Team", NamedTextColor.DARK_GRAY,
                listOf(if (s.teams) "Takes 4 or more players" else "Only for 2v2, 4v4 and 8v8")))) { unavailable(player) }

            val versus = when {
                s.playersRandom -> "A random number of ${s.difficultyName} bots"
                s.sandbox -> "Sandbox: nobody to fight"
                s.teamGame -> "${s.modeLabel} with ${s.difficultyName} bots"
                else -> "You vs ${s.bots} ${s.difficultyName} bot${if (s.bots == 1) "" else "s"}"
            }
            set(45, menuItem(Material.EMERALD_BLOCK, "Play", NamedTextColor.GREEN, listOf(versus, "Start with your selected kit and map"))) {
                player.closeInventory()
                startGame(player)
            }
            set(47, menuItem(Material.FILLED_MAP,"Choose Map",lore=listOf("Choose the arena for your next match"))) {MapVoteService.openVoteGui(player)}
            set(49, menuItem(Material.REDSTONE_BLOCK, "Close", NamedTextColor.RED)) { player.closeInventory() }
            set(53, menuItem(Material.CREEPER_HEAD, "Choose Kit", lore = listOf("Choose your mob"))) {
                KitService.openKitSelectionGui(player)
            }
        }
        player.openInventory(gui)
    }

    /** A button that is there but dark: nothing to do with it yet. */
    private fun unavailable(player: Player) = player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.4f, 0.7f)

    private fun changed(player: Player) {
        save()
        LobbyBotService.configure(player)
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, 1.2f)
        openMenu(player)
    }

    // ------------------------------------------------------------------ starting a game

    fun startGame(player: Player) {
        val lobbyId = LobbyService.lobbyOf(player)
        if (LobbyService.busy(lobbyId)) {
            player.sendMessage(plain("Lobby $lobbyId already has a game. Wait here or type /lobby to change lobbies.", NamedTextColor.YELLOW))
            return
        }
        if (minigameService.isPlayerInMinigame(player)) {
            player.sendMessage(plain("You're already in a game. Use /hub to leave it.", NamedTextColor.RED))
            return
        }
        if (!MapVoteService.requireChoice(player, setupOf(player).players)) return
        if (!starting.add(player.uniqueId)) {
            player.sendMessage(plain("Your game is already starting...", NamedTextColor.GRAY))
            return
        }
        val def = dataService.getMinigame(MINIGAME_ID)
        if (def == null) {
            starting.remove(player.uniqueId)
            player.sendMessage(plain("Game mode '$MINIGAME_ID' is missing.", NamedTextColor.RED))
            return
        }
        QueueService.removePlayer(player)

        val s = setupOf(player).copy()
        minigameService.capacityRejection(s.players)?.let { message ->
            starting.remove(player.uniqueId)
            player.sendMessage(plain(message, NamedTextColor.YELLOW))
            return
        }
        val bots = LobbyBotService.takeForMatch(player, s.bots)
        if (bots.size!=s.bots) {
            bots.forEach {BotManager.despawn(it)}
            starting.remove(player.uniqueId)
            player.sendMessage(plain("Some chosen opponents are already playing or unavailable. Choose other names and try again.", NamedTextColor.RED))
            return
        }
        try {
            bots.forEach { LobbyService.assign(it, lobbyId); HubService.tryTeleportToDefaultHub(it) }
            // World setup registers the participants synchronously before its first suspension.
            // No simulated login wait: the selected opponents already exist at this point.
            QueuePopEvent(def.id, listOf(player) + bots, s.copy(), player.uniqueId, lobbyId).callEvent()
            // On Random the match after this one gets a size of its own.
            setupOf(player).takeIf { it.playersRandom }?.let { rollPlayers(player, it); save() }
        } catch (error: Exception) {
            bots.filter { !minigameService.isPlayerInMinigame(it) }.forEach(BotManager::despawn)
            throw error
        } finally {
            starting.remove(player.uniqueId)
        }
    }
}
