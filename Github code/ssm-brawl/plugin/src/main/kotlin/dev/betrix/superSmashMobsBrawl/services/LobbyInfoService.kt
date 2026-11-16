package dev.betrix.superSmashMobsBrawl.services

import java.util.UUID
import org.bukkit.*
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f

/** Personal lobby signs: each visitor sees their own map and clock settings. */
object LobbyInfoService {
    private lateinit var plugin: JavaPlugin
    private val worlds = mutableSetOf<World>()
    private val migrationKey = NamespacedKey("ssm", "lobby_signs_v1")
    private val views = mutableMapOf<UUID, View>()
    private val tickets = mutableSetOf<Triple<World, Int, Int>>()
    private data class View(val state: List<String>, val entities: List<Entity>)

    fun initialize(plugin: JavaPlugin, world: World) {
        this.plugin = plugin
        worlds.add(world)
        // These are the two original lettering rows only. Never clear an arbitrary lobby cuboid.
        if (!world.persistentDataContainer.has(migrationKey)) {
            var removed = 0
            plugin.getResource("data/lobby-sign-blocks.csv")!!.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val p = line.split(',')
                    val block = world.getBlockAt(p[0].toInt(), p[1].toInt(), p[2].toInt())
                    if (block.type == Material.valueOf(p[3])) {
                        block.setType(Material.AIR, false)
                        removed++
                    }
                }
            }
            world.persistentDataContainer.set(migrationKey, PersistentDataType.BYTE, 1.toByte())
            world.save()
            plugin.logger.info("Replaced $removed original lobby sign blocks; platforms and kit selectors preserved.")
        }
        // Keep managed displays loaded, including for players standing beyond simulation distance.
        for (x in -3..2) {
            if (world.addPluginChunkTicket(x, 3, plugin)) tickets += Triple(world, x, 3)
        }
    }

    fun stateFor(player: Player): List<String> {
        val s = GameSetupService.setupOf(player)
        val opponents = if (s.selectedBotNames.isEmpty()) s.bots else s.selectedBotNames.size
        val mode = if (s.allBotsVsPlayer) "${opponents}v1" else "FFA"
        val items = if (!s.items || s.enabledItems.isEmpty()) "Off"
            else if (s.itemIntervalSeconds < 0) "Random 3-90 sec"
            else "Every ${s.itemIntervalSeconds} sec"
        val crystals = if (!s.crystals || s.enabledCrystalTiers.isEmpty()) "Off"
            else if (s.crystalIntervalSeconds < 0) "Random 1-15 min"
            else "Every ${s.crystalIntervalSeconds / 60} min"
        return listOf(MapVoteService.selectedMapName(player),
            "Difficulty: ${s.difficultyName} - $mode - ${opponents + 1} Players",
            "Items: $items - Smash Crystals: $crystals",
            "Reverse: ${s.reverseMode.label} - Reflect: ${s.reflectMode.label} - ${s.mapScale.label}")
    }

    fun refresh() {
        val players = worlds.flatMap { it.players }.filterNot(dev.betrix.superSmashMobsBrawl.bots.BotManager::isBot).associateBy { it.uniqueId }
        views.keys.toList().filter { it !in players }.forEach(::remove)
        players.values.forEach { player ->
            val world = player.world
            val state = stateFor(player)
            val old = views[player.uniqueId]
            if (old?.state == state && old.entities.all { it.isValid && it.world == world }) return@forEach
            remove(player.uniqueId)
            val entities = mutableListOf<Entity>()
            state.forEachIndexed { index, line ->
                val rows = letterRows(line)
                val colors = pixelColors(player, index, line)
                val width = rows.first().length
                val scale = minOf(if (index == 0) 1.0 else 0.7, 77.0 / width)
                val bottom = if (index == 0) 91.0 else 85.0 - (index - 1) * 6.0
                rows.forEachIndexed { row, pixels ->
                    var col = 0
                    while (col < pixels.length) {
                        if (pixels[col] != '#') { col++; continue }
                        val start = col
                        val material = colors[col]
                        while (col < pixels.length && pixels[col] == '#' && colors[col] == material) col++
                        val at = Location(world, 1.5 + width * scale / 2 - col * scale,
                            bottom + (4 - row) * scale, 50.0)
                        val display = world.spawn(at, BlockDisplay::class.java) {
                            configure(it)
                            it.block = material.createBlockData()
                            it.transformation = transform(((col - start) * scale).toFloat(), scale.toFloat(),
                                if (index == 0) 1f else 0.7f)
                        }
                        player.showEntity(plugin, display)
                        entities += display
                    }
                }
            }
            views[player.uniqueId] = View(state, entities)
        }
    }

    private fun pixelColors(player: Player, row: Int, line: String): List<Material> {
        val s = GameSetupService.setupOf(player)
        val letters = MutableList(line.length) { if (row == 0) Material.YELLOW_TERRACOTTA else Material.QUARTZ_BLOCK }
        fun mark(word: String, color: Material) {
            var start = line.indexOf(word, ignoreCase = true)
            while (start >= 0) {
                for (i in start until start + word.length) letters[i] = color
                start = line.indexOf(word, start + word.length, ignoreCase = true)
            }
        }
        if (row > 0) {
            mark("Off", Material.RED_CONCRETE)
            mark("On", Material.LIME_CONCRETE)
            mark("Random", Material.MAGENTA_CONCRETE)
            mark("FFA", Material.LIME_CONCRETE)
            mark("None", Material.LIGHT_GRAY_CONCRETE)
            if (row == 1) {
                mark(s.difficultyName, when (s.difficultyName.lowercase()) {
                    "normal" -> Material.LIME_CONCRETE; "hard" -> Material.YELLOW_CONCRETE
                    "harder" -> Material.ORANGE_CONCRETE; "expert" -> Material.RED_CONCRETE
                    "random" -> Material.MAGENTA_CONCRETE; else -> Material.LIGHT_GRAY_CONCRETE
                })
                val count = if (s.selectedBotNames.isEmpty()) s.bots else s.selectedBotNames.size
                if (s.allBotsVsPlayer) { mark("${count}v1", Material.RED_CONCRETE); letters[line.indexOf("${count}v1") + "${count}v".length] = Material.LIME_CONCRETE }
            }
            // The same green-to-red scale as the dyes and candles in the settings menu.
            val scale = listOf(Material.LIME_CONCRETE, Material.YELLOW_CONCRETE, Material.ORANGE_CONCRETE, Material.RED_CONCRETE)
            fun shade(seconds: Int, steps: List<Int>) = scale[steps.indexOf(seconds).takeIf { it >= 0 } ?: scale.lastIndex]
            if (row == 2 && s.items) mark("Every ${s.itemIntervalSeconds} sec", shade(s.itemIntervalSeconds, GameSetupService.ITEM_INTERVALS))
            if (row == 2 && s.crystals) mark("Every ${s.crystalIntervalSeconds / 60} min", shade(s.crystalIntervalSeconds, GameSetupService.CRYSTAL_INTERVALS))
        }
        return line.uppercase(java.util.Locale.ROOT).flatMapIndexed { i, c ->
            List((font[c] ?: font.getValue('?'))[0].length + if (i == line.lastIndex) 0 else 1) { letters[i] }
        }
    }

    private fun configure(display: Display) {
        display.isPersistent = false
        display.isVisibleByDefault = false
        display.brightness = Display.Brightness(15, 15)
        display.viewRange = 4f
        display.displayWidth = 160f
        display.displayHeight = 32f
    }

    private fun transform(x: Float, y: Float, z: Float) =
        Transformation(Vector3f(), Quaternionf(), Vector3f(x, y, z), Quaternionf())

    fun remove(id: UUID) { views.remove(id)?.entities?.forEach(Entity::remove) }

    fun teardown() {
        views.keys.toList().forEach(::remove)
        tickets.forEach { (world, x, z) -> world.removePluginChunkTicket(x, z, plugin) }
        worlds.clear()
        tickets.clear()
    }

    private fun letterRows(text: String): List<String> {
        val letters = text.uppercase(java.util.Locale.ROOT).map { font[it] ?: font.getValue('?') }
        return (0..4).map { row -> letters.joinToString(" ") { it[row] } }
    }

    // Matches the original lobby's four-wide / five-high terracotta lettering.
    private val font = mapOf(
        'A' to "####/#..#/####/#..#/#..#", 'B' to "###./#..#/###./#..#/###.",
        'C' to "####/#.../#.../#.../####", 'D' to "###./#..#/#..#/#..#/###.",
        'E' to "####/#.../###./#.../####", 'F' to "####/#.../###./#.../#...",
        'G' to "####/#.../#.##/#..#/####", 'H' to "#..#/#..#/####/#..#/#..#",
        'I' to "###/.#./.#./.#./###", 'J' to "..##/...#/...#/#..#/####",
        'K' to "#..#/#.#./##../#.#./#..#", 'L' to "#.../#.../#.../#.../####",
        'M' to "#####/#.#.#/#.#.#/#...#/#...#", 'N' to "#..#/##.#/#.##/#..#/#..#",
        'O' to "####/#..#/#..#/#..#/####", 'P' to "####/#..#/####/#.../#...",
        'Q' to "####/#..#/#..#/#.##/####", 'R' to "####/#..#/###./#..#/#..#",
        'S' to "####/#.../####/...#/####", 'T' to "#####/..#../..#../..#../..#..",
        'U' to "#..#/#..#/#..#/#..#/####", 'V' to "#...#/#...#/#...#/.#.#./..#..",
        'W' to "#...#/#...#/#.#.#/#.#.#/#####", 'X' to "#...#/.#.#./..#../.#.#./#...#",
        'Y' to "#...#/.#.#./..#../..#../..#..", 'Z' to "####/...#/.##./#.../####",
        '0' to "####/#..#/#..#/#..#/####", '1' to ".#./##./.#./.#./###",
        '2' to "####/...#/####/#.../####", '3' to "####/...#/.###/...#/####",
        '4' to "#..#/#..#/####/...#/...#", '5' to "####/#.../####/...#/####",
        '6' to "####/#.../####/#..#/####", '7' to "####/...#/..#./.#../.#..",
        '8' to "####/#..#/####/#..#/####", '9' to "####/#..#/####/...#/####",
        ' ' to ".../.../.../.../...", '-' to ".../.../###/.../...",
        '×' to ".../#.#/.#./#.#/...",
        '\'' to "#/#/././.", '.' to "././././#", ':' to "./#/./#/.",
        '&' to ".##./#.../.##./#..#/.###", '?' to ".##./...#/.##./..../.#..",
    ).mapValues { (_, pattern) -> pattern.split('/') }
}
