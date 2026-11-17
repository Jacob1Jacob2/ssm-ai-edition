package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.gui.BrawlGui.Companion.openInventory
import dev.betrix.superSmashMobsBrawl.gui.brawlGui
import dev.betrix.superSmashMobsBrawl.models.brawlData.GameMapDef
import io.papermc.paper.datacomponent.DataComponentTypes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.ShadowColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** An explicit, persistent map choice. Random is the default for new players. */
object MapVoteService : KoinComponent {
    private val dataService: DataService by inject()
    private val plugin: dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl by inject()
    private val file get() = plugin.dataFolder.toPath().resolve("map-choices.properties")

    fun setup() {
        votes.clear()
        if (!java.nio.file.Files.exists(file)) return
        val properties = java.util.Properties()
        java.nio.file.Files.newInputStream(file).use { properties.load(it) }
        properties.stringPropertyNames().forEach { key ->
            runCatching { UUID.fromString(key) }.getOrNull()?.let { votes[it] = properties.getProperty(key) }
        }
    }
    @Synchronized private fun save() {
        java.nio.file.Files.createDirectories(file.parent)
        val properties = java.util.Properties()
        votes.forEach { (uuid, choice) -> properties.setProperty(uuid.toString(), choice) }
        val temp = file.resolveSibling("map-choices.properties.tmp")
        java.nio.file.Files.newOutputStream(temp).use { properties.store(it, "SSM AI Edition - chosen maps") }
        java.nio.file.Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    fun requireChoice(player: Player, players: Int = 2): Boolean {
        val choice = votes.getOrPut(player.uniqueId) { RANDOM }
        val map = choice?.let { dataService.getGameMap(it) }
        if (choice !in setOf(RANDOM, RANDOM_OLD, RANDOM_NEW) && map == null) {
            player.sendMessage(plain("Choose a map or a Random option before starting.", NamedTextColor.YELLOW))
            openVoteGui(player)
            return false
        }
        if (map != null && capacity(map) < players) {
            player.sendMessage(plain("${map.displayName ?: map.id} has ${capacity(map)} starting spots. Lower the player count or choose another map.", NamedTextColor.YELLOW))
            openVoteGui(player)
            return false
        }
        return true
    }
    fun capacity(map: GameMapDef) = (map.spawnPoints + map.itemSpawnPoints + map.crystalSpawnPoints)
        .distinctBy { Triple(it.x, it.y, it.z) }.size

    private const val RANDOM = "@random"
    private const val RANDOM_OLD = "@random_old"
    private const val RANDOM_NEW = "@random_new"

    private val votes = ConcurrentHashMap<UUID, String>()

    fun clearVote(player: OfflinePlayer) {
        votes.remove(player.uniqueId)
        save()
    }

    fun voteOf(uuid: UUID): String? = votes[uuid] ?: RANDOM

    fun selectedMapName(player: Player): String = votes[player.uniqueId]?.let(::choiceName) ?: choiceName(RANDOM)

    /** Casts a vote without the menu (used for bot test games). */
    fun setVote(uuid: UUID, choice: String) {
        votes[uuid] = choice
        save()
    }

    private fun voteCount(choice: String) = votes.values.count { it == choice }

    private fun plain(text: String, color: NamedTextColor) =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private fun choiceName(choice: String): String =
        when (choice) {
            RANDOM -> "Random Any Map"
            RANDOM_OLD -> "Random Old Map"
            RANDOM_NEW -> "Random New Map"
            else -> dataService.getGameMap(choice)?.displayName ?: choice
        }

    // ------------------------------------------------------------------ menu

    fun openVoteGui(player: Player) {
        // Original maps A-Z first, then the newer maps A-Z.
        val maps =
            dataService.getAllGameMaps().sortedWith(compareBy({ it.isNew }, { (it.displayName ?: it.id).lowercase() }))
        // 45 maps, followed by one complete row of controls.
        val controlSlots = (45..53).toSet()
        val mapSlots = (0 until 54).filter { it !in controlSlots }

        val gui =
            brawlGui(Component.text("Choose a map"), 54, selectionEffects=true) {
                onClick { isCancelled = true }

                maps.take(mapSlots.size).forEachIndexed { i, map ->
                    set(mapSlots[i], mapItem(player, map)) { vote(player, map.id) }
                }

                set(45, MenuIcons.navigation(false).apply {
                    editMeta {it.displayName(plain("Back",NamedTextColor.RED))}
                }) {GameSetupService.openMenu(player)}
                set(53, MenuIcons.navigation(true).apply {
                    editMeta {it.displayName(plain("Start",NamedTextColor.GREEN));it.lore(listOf(plain("Start with your chosen map and settings",NamedTextColor.GRAY)))}
                }) {player.closeInventory();GameSetupService.startGame(player)}
                set(46, randomItem(player, RANDOM, Material.ENDER_EYE, ResourcePackService.GREEN_EYE_MODEL, "Any map")) { vote(player, RANDOM) }
                set(47, randomItem(player, RANDOM_OLD, Material.ENDER_EYE, ResourcePackService.YELLOW_EYE_MODEL, "One of the original maps")) {
                    vote(player, RANDOM_OLD)
                }
                set(48, randomItem(player, RANDOM_NEW, Material.ENDER_EYE, ResourcePackService.PURPLE_EYE_MODEL, "One of the newer maps")) {
                    vote(player, RANDOM_NEW)
                }
                val setup = GameSetupService.setupOf(player)
                fun control(slot: Int, name: String, model: Key, active: Boolean, lore: List<String>, click: () -> Unit) {
                    set(slot, ItemStack.of(Material.ENDER_EYE).apply {
                        editMeta {
                            it.displayName(plain(name, if (active) NamedTextColor.AQUA else NamedTextColor.GRAY))
                            it.lore(lore.map { line -> plain(line,NamedTextColor.GRAY) })
                        }
                        setData(DataComponentTypes.ITEM_MODEL,model)
                        setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE,active)
                    }) { click(); GameSetupService.save(); player.playSound(player.location,Sound.UI_BUTTON_CLICK,1f,1.2f); openVoteGui(player) }
                }
                control(49,"Reverse: ${setup.reverseMode.label}",ResourcePackService.REVERSE_EYE_MODEL,setup.reverseMode!=ReverseMode.OFF,
                    listOf("Off → On → Random", "Mirror the whole arena left-to-right", "Random chooses once when the match starts")) {
                    setup.reverseMode=ReverseMode.entries[(setup.reverseMode.ordinal+1)%3]
                }
                control(50,"Reflect: ${setup.reflectMode.label}",Key.key("ssm","eye_reflect"),setup.reflectMode!=ReflectMode.OFF,
                    listOf("Off → Left → Right", "Keep that half and mirror it across the center", "Reflection happens before Reverse and size")) {
                    setup.reflectMode=ReflectMode.entries[(setup.reflectMode.ordinal+1)%3]
                }
                control(51,"2× Bigger: ${if(setup.mapScale==MapScale.BIG) "On" else "Off"}",Key.key("ssm","eye_expand"),setup.mapScale==MapScale.BIG,
                    listOf("Twice the width, height and depth", "Slabs and stairs become full-block shapes")) {
                    setup.mapScale=if(setup.mapScale==MapScale.BIG)MapScale.NORMAL else MapScale.BIG
                }
                control(52,"2× Smaller: ${if(setup.mapScale==MapScale.SMALL) "On" else "Off"}",Key.key("ssm","eye_shrink"),setup.mapScale==MapScale.SMALL,
                    listOf("Half the width, height and depth", "Preserves smaller platforms and steps")) {
                    setup.mapScale=if(setup.mapScale==MapScale.SMALL)MapScale.NORMAL else MapScale.SMALL
                }
            }
        player.openInventory(gui)
    }

    private fun vote(player: Player, choice: String) {
        setVote(player.uniqueId, choice)
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1f, 1.2f)
        player.sendMessage(
            plain("Map chosen: ", NamedTextColor.GRAY).append(plain(choiceName(choice), NamedTextColor.GOLD))
        )
        openVoteGui(player)
    }

    private fun footer(player: Player, choice: String): List<Component> =
        listOf(
            Component.empty(),
            if (voteOf(player.uniqueId) == choice) plain("Chosen", NamedTextColor.GREEN)
            else plain("Click to choose", NamedTextColor.AQUA),
        )

    /** Green = small, yellow = medium, red = large. */
    private fun sizeColor(size: String?): NamedTextColor =
        when (size?.trim()?.lowercase()) {
            "small" -> NamedTextColor.GREEN
            "medium" -> NamedTextColor.YELLOW
            "large" -> NamedTextColor.RED
            else -> NamedTextColor.GOLD
        }

    private fun mapItem(player: Player, map: GameMapDef): ItemStack {
        val iconMaterial = map.icon?.let { Material.matchMaterial(it) }
        // Lava has no item form: show a magma block, re-skinned as lava by the resource pack.
        val isLava = iconMaterial == Material.LAVA
        val material = if (isLava) Material.MAGMA_BLOCK else iconMaterial?.takeIf { it.isItem } ?: Material.PAPER
        val mine = votes[player.uniqueId] == map.id
        val color = sizeColor(map.size)

        return ItemStack.of(material).apply {
            editMeta { meta ->
                meta.displayName(plain(map.displayName ?: map.id, color))
                val lore = mutableListOf<Component>()
                // The map's picture, drawn by the resource pack's font (only if the player has it).
                val glyph = ResourcePackService.glyphFor(map.id)
                if (glyph != null && ResourcePackService.hasPack(player)) {
                    lore +=
                        Component.text(glyph)
                            .font(ResourcePackService.MAP_FONT)
                            .color(NamedTextColor.WHITE)
                            .shadowColor(ShadowColor.none())
                            .decoration(TextDecoration.ITALIC, false)
                    repeat(ResourcePackService.PICTURE_BLANK_LINES) { lore += Component.empty() }
                }
                map.size?.let { lore += plain("Size: ${it.trim().replaceFirstChar(Char::uppercase)}", color) }
                lore += footer(player, map.id)
                meta.lore(lore)
            }
            if (isLava) setData(DataComponentTypes.ITEM_MODEL, ResourcePackService.LAVA_BLOCK_MODEL)
            setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, mine)
        }
    }

    private fun randomItem(player: Player, choice: String, material: Material, model: Key?, description: String): ItemStack {
        val mine = voteOf(player.uniqueId) == choice
        return ItemStack.of(material).apply {
            editMeta { meta ->
                meta.displayName(plain(choiceName(choice), NamedTextColor.LIGHT_PURPLE))
                meta.lore(listOf(plain(description, NamedTextColor.GRAY)) + footer(player, choice))
            }
            if (model != null) setData(DataComponentTypes.ITEM_MODEL, model)
            setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, mine)
        }
    }

    // ------------------------------------------------------------------ choosing

    /**
     * Chooses from explicit choices without consuming them.
     * Returns the map, how many votes the winning choice had, and that choice's name.
     */
    fun pickMap(players: List<OfflinePlayer>, candidates: List<GameMapDef>): Triple<GameMapDef, Int, String>? {
        if (candidates.isEmpty()) return null
        val allowed = candidates.associateBy { it.id }
        val tally =
            players
                .map { votes[it.uniqueId] ?: RANDOM }
                .filter { it in allowed || it in setOf(RANDOM, RANDOM_OLD, RANDOM_NEW) }
                .groupingBy { it }
                .eachCount()
        if (tally.isEmpty()) return null

        val best = tally.values.max()
        val choice = tally.filterValues { it == best }.keys.random()
        val map =
            when (choice) {
                RANDOM -> candidates.random()
                RANDOM_OLD -> candidates.filter { !it.isNew }.ifEmpty { candidates }.random()
                RANDOM_NEW -> candidates.filter { it.isNew }.ifEmpty { candidates }.random()
                else -> allowed.getValue(choice)
            }
        val label = if (choice.startsWith("@")) choiceName(choice).lowercase() else ""
        return Triple(map, best, label)
    }
}
