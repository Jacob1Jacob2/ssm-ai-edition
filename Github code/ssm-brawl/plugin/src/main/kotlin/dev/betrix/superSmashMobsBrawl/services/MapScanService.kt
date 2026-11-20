package dev.betrix.superSmashMobsBrawl.services

import com.charleskorn.kaml.Yaml
import com.github.shynixn.mccoroutine.bukkit.launch
import com.github.shynixn.mccoroutine.bukkit.minecraftDispatcher
import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.models.SpawnPoint
import dev.betrix.superSmashMobsBrawl.models.brawlData.GameMapDef
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.atan2
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * SSM AI Edition map scanner.
 *
 * Every folder in <server>/worlds (except hub maps) is a game map. Map makers mark points with a
 * pressure plate on top of a column of wool:
 * - lime/green wool + gold (light weighted) plate -> player spawn
 * - red wool + iron (heavy weighted) plate -> item spawn
 * - yellow wool + wooden plate -> smash crystal spawn
 *
 * A column can hold several wool colours stacked on top of each other, which means the spot is
 * valid for each of those types. Results are cached in plugins/<plugin>/maps/<id>.yml and only
 * rebuilt when the world's level.dat is newer than the cache (or on /scanmaps).
 */
object MapScanService : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val dataService: DataService by inject()

    private val yaml =
        Yaml(configuration = Yaml.default.configuration.copy(strictMode = false))

    private val scanLock = Mutex()

    private enum class MarkerType(val label: String) {
        PLAYER("player"),
        ITEM("item"),
        CRYSTAL("crystal"),
    }

    private val woolTypes =
        mapOf(
            Material.LIME_WOOL to MarkerType.PLAYER,
            Material.GREEN_WOOL to MarkerType.PLAYER,
            Material.RED_WOOL to MarkerType.ITEM,
            Material.YELLOW_WOOL to MarkerType.CRYSTAL,
        )

    private fun plateType(material: Material): MarkerType? =
        when {
            material == Material.LIGHT_WEIGHTED_PRESSURE_PLATE -> MarkerType.PLAYER
            material == Material.HEAVY_WEIGHTED_PRESSURE_PLATE -> MarkerType.ITEM
            Tag.WOODEN_PRESSURE_PLATES.isTagged(material) -> MarkerType.CRYSTAL
            else -> null
        }

    /** The plate a marker of this type is meant to carry. */
    private fun plateFor(type: MarkerType): Material =
        when (type) {
            MarkerType.PLAYER -> Material.LIGHT_WEIGHTED_PRESSURE_PLATE
            MarkerType.ITEM -> Material.HEAVY_WEIGHTED_PRESSURE_PLATE
            MarkerType.CRYSTAL -> Material.OAK_PRESSURE_PLATE
        }

    private data class Marker(
        val x: Int,
        val plateY: Int,
        val z: Int,
        val plate: Material,
        /** Wool blocks under the plate, top to bottom. */
        val column: List<Material>,
        val below: Material,
    ) {
        val bottomY: Int
            get() = plateY - column.size
    }

    /** Lowest non-marker block in one x/z column. */
    /** One column of a map: its lowest block of any kind, and its lowest block with a top to stand on, if it has one. */
    private class Column(val x: Int, val z: Int, val minY: Int, val minMaterial: Material,
                         val floorY: Int? = null, val floorMaterial: Material = Material.AIR)

    /** Marker-coloured wool with nothing on it: either decoration or a marker missing its plate. */
    private class BareWool(val x: Int, val y: Int, val z: Int, val material: Material, val below: Material)

    private class ChunkResult(val markers: List<Marker>, val columns: List<Column>, val bare: List<BareWool>)

    /** How far past the outermost markers a block still counts as part of the map. */
    private const val MAP_MARGIN = 48

    val mapsFolder: File
        get() = File(plugin.dataFolder, "maps")

    private val reportFile: File
        get() = File(mapsFolder, "scan-report.txt")

    /** Loads cached scans and scans any map that is new or changed. Called once on startup. */
    fun setup() {
        plugin.launch { syncAll(force = false, only = null, notify = { plugin.logger.info(it) }) }
    }

    suspend fun syncAll(force: Boolean, only: String?, notify: (String) -> Unit) =
        scanLock.withLock {
            dataService.awaitReady()
            mapsFolder.mkdirs()
            val worldsDir = File(Bukkit.getWorldContainer(), "worlds")
            val hubIds = dataService.getHubMapIds()
            val ids =
                (worldsDir.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList())
                    .filter { it !in hubIds }
                    .filter { only == null || it == only }
                    .sorted()

            if (only != null && ids.isEmpty()) {
                notify("No map folder named worlds/$only")
                return@withLock
            }

            val reportLines = mutableListOf<String>()
            var scanned = 0
            ids.forEach { id ->
                val cache = File(mapsFolder, "$id.yml")
                val levelDat = File(worldsDir, "$id/level.dat")
                // "map info.txt" can name extra markers, so a change to it also calls for a rescan.
                val changed = maxOf(levelDat.lastModified(), File(worldsDir, "$id/map info.txt").lastModified())
                val upToDate = cache.exists() && cache.lastModified() >= changed
                val def =
                    if (!force && upToDate) {
                        runCatching { yaml.decodeFromString(GameMapDef.serializer(), cache.readText()) }
                            .onFailure { notify("Cached scan for $id is unreadable, rescanning") }
                            .getOrNull()
                    } else null

                val result =
                    def
                        ?: runCatching {
                                notify("Scanning map '$id'...")
                                scanned++
                                val (scannedDef, report) = scanMap(id, File(worldsDir, id))
                                cache.writeText(yaml.encodeToString(GameMapDef.serializer(), scannedDef))
                                reportLines += report
                                scannedDef
                            }
                            .onFailure {
                                notify("Scan of '$id' FAILED: ${it.message}")
                                reportLines += "[$id] FAILED: ${it.message}"
                                plugin.logger.severe(it.stackTraceToString())
                            }
                            .getOrNull()

                if (result == null) return@forEach
                // Name/icon/size/new come from the map folder's files, so re-read them every time
                // (cheap) instead of trusting the cached scan.
                val withInfo = applyMapInfo(result, File(worldsDir, id))
                if (withInfo.spawnPoints.size < 2) {
                    notify("Map '$id' has ${withInfo.spawnPoints.size} player spawn(s); it will not be used.")
                    dataService.removeGameMap(id)
                } else {
                    dataService.putGameMap(withInfo)
                }
            }

            if (reportLines.isNotEmpty()) {
                val header = "SSM AI map scan report (${java.time.LocalDateTime.now()})\n"
                withContext(Dispatchers.IO) {
                    reportFile.appendText(header + reportLines.joinToString("\n") + "\n\n")
                }
            }
            notify(
                "Maps ready: ${dataService.getAllGameMaps().size} playable " +
                    "($scanned scanned this run). Report: plugins/${plugin.name}/maps/scan-report.txt"
            )

            // Map pictures go into the server resource pack (shown when hovering in the vote menu).
            runCatching {
                ResourcePackService.rebuild(dataService.getAllGameMaps().map { it to pictureFor(it.id) })
            }.onFailure { plugin.logger.severe("Resource pack build failed: ${it.stackTraceToString()}") }
        }

    /** The map's picture (any .png in its folder except the world's icon.png). */
    fun pictureFor(id: String): File? {
        val dir = File(File(Bukkit.getWorldContainer(), "worlds"), id)
        val pngs = dir.listFiles { f -> f.isFile && f.name.endsWith(".png", true) && !f.name.equals("icon.png", true) }
            ?: return null
        val squashed = id.replace("_", "")
        return pngs.firstOrNull { it.nameWithoutExtension.replace(" ", "").equals(squashed, true) } ?: pngs.firstOrNull()
    }

    /** Fills in display name, icon, size and "new" flag from the map folder. */
    private fun applyMapInfo(def: GameMapDef, dir: File): GameMapDef {
        val info = readMapInfo(File(dir, "map info.txt"))
        return def.copy(
            displayName = info["name"] ?: displayNameFor(def.id),
            creators = (info["creators"] ?: info["creator"] ?: info["author"])?.split(',')?.map {it.trim()}?.filter {it.isNotEmpty()}
                ?: def.creators.ifEmpty {if(def.id.equals("amplified",true))listOf("CubicCubed") else emptyList()},
            icon = info["icon"]?.let { iconMaterial(it) },
            size = info["size"],
            isNew = info["new map"]?.lowercase()?.startsWith("y") == true,
        )
    }

    /**
     * Folder names of older maps have no spaces ("alpineruins") but their pictures do
     * ("alpine ruins.png"), so take the spacing from the picture when the letters match.
     */
    private fun displayNameFor(id: String): String {
        val pictureName = pictureFor(id)?.nameWithoutExtension
        val words =
            if (!id.contains('_') && pictureName != null && pictureName.contains(' ') &&
                pictureName.replace(" ", "").equals(id, ignoreCase = true)
            ) pictureName
            else id.replace('_', ' ')
        return words.trim().split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    /** Debug helper: lists the blocks from y1..y2 in one column of a map (top to bottom). */
    suspend fun describeColumn(id: String, x: Int, z: Int, y1: Int, y2: Int): List<String> {
        val world = WorldService.loadScratchWorld(id)
        try {
            return withContext(plugin.minecraftDispatcher) {
                world.getChunkAtAsync(x shr 4, z shr 4).await()
                (y2 downTo y1).map { y -> "  $id $x $y $z: ${world.getBlockAt(x, y, z).type}" }
            }
        } finally {
            WorldService.deleteScratchWorld(world)
        }
    }

    private suspend fun scanMap(id: String, sourceDir: File): Pair<GameMapDef, String> {
        val world = WorldService.loadScratchWorld(id)
        try {
            val chunks = listGeneratedChunks(world)
            require(chunks.isNotEmpty()) { "no generated chunks found in world files" }

            val results = mutableListOf<ChunkResult>()
            chunks.chunked(48).forEach { batch ->
                val snapshots =
                    withContext(plugin.minecraftDispatcher) {
                        batch
                            .map { (cx, cz) -> world.getChunkAtAsync(cx, cz) }
                            .map { it.await() }
                            .map { chunk ->
                                val snap = chunk.getChunkSnapshot(true, false, false)
                                chunk.unload(false)
                                snap
                            }
                    }
                withContext(Dispatchers.Default) {
                    snapshots.forEach { results += scanChunk(it, world.minHeight, world.maxHeight) }
                }
            }

            return buildDef(id, sourceDir, results)
        } finally {
            WorldService.deleteScratchWorld(world)
        }
    }

    /** Reads the region file headers to find which chunks actually exist in the saved map. */
    private fun listGeneratedChunks(world: World): List<Pair<Int, Int>> {
        val folder = world.worldFolder
        val regionDir =
            listOf(
                    File(folder, "region"),
                    File(folder, "dimensions/minecraft/overworld/region"),
                )
                .firstOrNull { it.isDirectory } ?: return emptyList()

        val out = mutableListOf<Pair<Int, Int>>()
        val pattern = Regex("""r\.(-?\d+)\.(-?\d+)\.mca""")
        regionDir.listFiles()?.forEach { file ->
            val m = pattern.matchEntire(file.name) ?: return@forEach
            if (file.length() < 4096) return@forEach
            val rx = m.groupValues[1].toInt()
            val rz = m.groupValues[2].toInt()
            RandomAccessFile(file, "r").use { raf ->
                for (i in 0 until 1024) {
                    if (raf.readInt() != 0) out += (rx * 32 + (i % 32)) to (rz * 32 + (i / 32))
                }
            }
        }
        return out
    }

    private fun scanChunk(snap: ChunkSnapshot, worldMinY: Int, worldMaxY: Int): ChunkResult {
        val markers = mutableListOf<Marker>()
        val columns = mutableListOf<Column>()
        val bare = mutableListOf<BareWool>()
        val baseX = snap.x shl 4
        val baseZ = snap.z shl 4
        // Every block of the chunk is looked at, bottom of the world to top. The height a column is
        // saved with ("the highest block here is at y") is not gone by: on maps that were downloaded
        // it is wrong for some columns, and a column it said was empty was skipped whole. That is how
        // the wreckage falling under Aviation's plane was missed and the void came to be above it.
        val top = worldMaxY - 1

        for (lx in 0 until 16) for (lz in 0 until 16) {
            val wx = baseX + lx
            val wz = baseZ + lz
            var colMinY = Int.MAX_VALUE
            var colMinMat = Material.AIR
            var colFloorY = Int.MAX_VALUE
            var colFloorMat = Material.AIR
            for (y in worldMinY..top) {
                val type = snap.getBlockType(lx, y, lz)
                if (type.isAir) continue
                // A map-maker can omit the final plate of a stacked marker. Only accept an
                // exposed wool cap directly on another complete wool+plate unit: ordinary
                // decorative wool, including wool pillars, must remain part of the arena.
                if (type in woolTypes && y - 2 >= worldMinY &&
                    (y >= top || snap.getBlockType(lx, y + 1, lz).isAir) &&
                    plateType(snap.getBlockType(lx, y - 1, lz)) != null &&
                    snap.getBlockType(lx, y - 2, lz) in woolTypes
                ) {
                    markers += Marker(wx, y + 1, wz, plateFor(woolTypes.getValue(type)), listOf(type), snap.getBlockType(lx, y - 1, lz))
                    continue
                }
                // A marker is exactly one wool block with a pressure plate on it. Wool further down
                // belongs to the map itself (e.g. a green wool pillar) and is left alone.
                if (plateType(type) != null && y - 1 >= worldMinY) {
                    val wool = snap.getBlockType(lx, y - 1, lz)
                    if (wool in woolTypes) {
                        val below =
                            if (y - 2 >= worldMinY) snap.getBlockType(lx, y - 2, lz) else Material.VOID_AIR
                        markers += Marker(wx, y, wz, type, listOf(wool), below)
                        continue
                    }
                }
                // Map bounds ignore marker wool so markers don't skew the void level.
                if (type in woolTypes && plateType(snap.getBlockType(lx, minOf(y + 1, top), lz)) != null) continue
                if (type in woolTypes && (y >= top || snap.getBlockType(lx, y + 1, lz).isAir)) {
                    bare += BareWool(wx, y, wz, type, if (y - 1 >= worldMinY) snap.getBlockType(lx, y - 1, lz) else Material.VOID_AIR)
                }
                if (y < colMinY) {
                    colMinY = y
                    colMinMat = type
                }
                // What the void has to be under is the lowest thing a fighter can come down on: a
                // block with a top to it. Not the lava or water some maps have far below (that kills
                // by itself), and not a vine hanging off the underside of an island.
                if (type.isSolid && y < colFloorY) { colFloorY = y; colFloorMat = type }
            }
            if (colMinY != Int.MAX_VALUE) columns += Column(wx, wz, colMinY, colMinMat, colFloorY.takeIf { it != Int.MAX_VALUE }, colFloorMat)
        }
        return ChunkResult(markers, columns, bare)
    }

    private fun buildDef(id: String, sourceDir: File, chunks: List<ChunkResult>): Pair<GameMapDef, String> {
        val info = readMapInfo(File(sourceDir, "map info.txt"))
        // Markers built without their pressure plate look exactly like decorative wool, so the
        // scanner cannot guess them. The map's "map info.txt" can name them instead:
        //   plateless markers: -7 70 -87, 8 70 -87
        val plateless =
            (info["plateless markers"] ?: "").split(',')
                .mapNotNull { spot -> spot.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 } }
                .toSet()
        val bareWool = chunks.flatMap { it.bare }
        val named = bareWool.filter { listOf(it.x, it.y, it.z) in plateless }
        val markers = chunks.flatMap { it.markers } +
            named.map { Marker(it.x, it.y + 1, it.z, plateFor(woolTypes.getValue(it.material)), listOf(it.material), it.below) }
        require(markers.isNotEmpty()) { "map has no markers" }

        // Only blocks near the markers belong to the map. Older WorldDownloader maps also contain
        // chunks from unrelated builds hundreds of blocks away, which must not affect the bounds.
        val boxMinX = markers.minOf { it.x } - MAP_MARGIN
        val boxMaxX = markers.maxOf { it.x } + MAP_MARGIN
        val boxMinZ = markers.minOf { it.z } - MAP_MARGIN
        val boxMaxZ = markers.maxOf { it.z } + MAP_MARGIN
        val solid =
            chunks
                .flatMap { it.columns }
                .filter { it.x in boxMinX..boxMaxX && it.z in boxMinZ..boxMaxZ }
        require(solid.isNotEmpty()) { "map has no blocks near its markers" }
        val minX = solid.minOf { it.x }
        val maxX = solid.maxOf { it.x }
        val minZ = solid.minOf { it.z }
        val maxZ = solid.maxOf { it.z }
        // The void goes under the lowest block a fighter could come down on (see scanChunk). A map
        // with no such block in reach of its markers falls back on its lowest block of any kind.
        val floor = solid.filter { it.floorY != null }.minByOrNull { it.floorY!! }
        val lowest = floor?.let { Column(it.x, it.z, it.floorY!!, it.floorMaterial) } ?: solid.minBy { it.minY }
        val minY = lowest.minY
        val ignoredColumns = chunks.sumOf { it.columns.size } - solid.size

        val warnings = mutableListOf<String>()
        val notes = mutableListOf<String>()
        (plateless - named.map { listOf(it.x, it.y, it.z) }.toSet()).forEach {
            warnings += "plateless marker ${it.joinToString(" ")} in map info.txt is not bare marker wool; ignored"
        }
        named.forEach { notes += "${it.material} at ${it.x} ${it.y} ${it.z} has no plate; counted as a marker (map info.txt)" }
        bareWool
            .filter { it !in named && it.x in boxMinX..boxMaxX && it.z in boxMinZ..boxMaxZ }
            .groupBy { it.material }
            .forEach { (material, blocks) ->
                notes += "bare $material x${blocks.size} (no plate): " +
                    blocks.take(40).joinToString(" | ") { "${it.x} ${it.y} ${it.z} on ${it.below}" }
            }
        if (minY <= -60) {
            warnings += "map reaches the bottom of the world (${lowest.minMaterial} at ${lowest.x} $minY ${lowest.z}); void level may be wrong"
        }

        // Stacks: wool+plate units piled directly on top of each other (an upper unit's wool sits
        // on the lower unit's plate) form one spot that is valid for every colour in the stack.
        val stacks = mutableListOf<List<Marker>>()
        markers.groupBy { it.x to it.z }.values.forEach { units ->
            var current = mutableListOf<Marker>()
            units.sortedBy { it.plateY }.forEach { u ->
                if (current.isNotEmpty() && u.plateY - 2 != current.last().plateY) {
                    stacks += current
                    current = mutableListOf()
                }
                current += u
            }
            if (current.isNotEmpty()) stacks += current
        }

        // The wool colour decides the marker type; the plate only marks the spot (some maps use
        // an iron plate on yellow crystal wool).
        val byType = MarkerType.entries.associateWith { mutableListOf<Marker>() }
        stacks.forEach { stack ->
            val bottom = stack.first()
            val top = stack.last()
            stack.forEach { u ->
                val woolType = woolTypes.getValue(u.column.first())
                if (woolType != plateType(u.plate)) {
                    notes += "${u.plate} on ${u.column.first()} at ${u.x} ${u.plateY} ${u.z} -> counted as ${woolType.label}"
                }
            }
            if (bottom.below.isAir) {
                warnings += "floating marker at ${bottom.x} ${bottom.plateY} ${bottom.z} (air under the wool)"
            }
            if (bottom.below in woolTypes) {
                notes += "wool under marker at ${bottom.x} ${bottom.bottomY - 1} ${bottom.z} (${bottom.below}) kept as part of the map"
            }
            // Represent the whole stack by its bottom unit, with the top plate as the stack top.
            val merged = bottom.copy(plateY = top.plateY, column = List(top.plateY - bottom.bottomY) { bottom.column.first() })
            stack.map { woolTypes.getValue(it.column.first()) }.toSet().forEach { byType.getValue(it) += merged }
        }

        val players = byType.getValue(MarkerType.PLAYER)
        val cx = if (players.isNotEmpty()) players.map { it.x + 0.5 }.average() else (minX + maxX) / 2.0 + 0.5
        val cz = if (players.isNotEmpty()) players.map { it.z + 0.5 }.average() else (minZ + maxZ) / 2.0 + 0.5

        fun toPoint(m: Marker): SpawnPoint {
            val x = m.x + 0.5
            val z = m.z + 0.5
            // Face the middle of the map. Minecraft yaw: 0 = +Z (south), 90 = -X (west).
            val yaw = Math.toDegrees(atan2(-(cx - x), cz - z)).toFloat()
            return SpawnPoint(x, m.bottomY.toDouble(), z, pitch = 0f, yaw = yaw)
        }

        val markerBlocks =
            markers.flatMap { m -> (m.bottomY..m.plateY).map { y -> "${m.x} $y ${m.z}" } }.distinct()

        val spawnPoints = players.map(::toPoint)
        val topSpawnY = spawnPoints.maxOfOrNull { it.y } ?: (minY + 40.0)
        val def =
            GameMapDef(
                id = id,
                worldBorderSize = max(maxX - minX, maxZ - minZ) + 60.0,
                worldBorderCenterX = (minX + maxX) / 2.0 + 0.5,
                worldBorderCenterZ = (minZ + maxZ) / 2.0 + 0.5,
                voidLevel = minY - 10.0,
                maxPlayers = spawnPoints.size,
                spawnPoints = spawnPoints,
                spectatorSpawnPoint = SpawnPoint(cx, topSpawnY + 15.0, cz, pitch = 60f, yaw = 0f),
                itemSpawnPoints = byType.getValue(MarkerType.ITEM).map(::toPoint),
                crystalSpawnPoints = byType.getValue(MarkerType.CRYSTAL).map(::toPoint),
                markerBlocks = markerBlocks,
            )

        val stacked = stacks.count { it.size > 1 }
        val report = buildString {
            append("[$id] players=${def.spawnPoints.size} items=${def.itemSpawnPoints.size} ")
            append("crystals=${def.crystalSpawnPoints.size} stacked=$stacked ")
            append("bounds x=$minX..$maxX z=$minZ..$maxZ minY=$minY (${lowest.minMaterial}) void=${def.voidLevel} ")
            if (ignoredColumns > 0) append("ignoredFarColumns=$ignoredColumns ")
            append("border=${def.worldBorderSize} icon=${def.icon} size=${def.size} new=${def.isNew}")
            warnings.forEach { append("\n    ! ").append(it) }
            notes.forEach { append("\n    - ").append(it) }
            stacks.filter { it.size > 1 }.forEach { s ->
                append("\n    stack @ ${s.first().x} ${s.first().bottomY} ${s.first().z}: ")
                append(s.joinToString(" + ") { woolTypes.getValue(it.column.first()).label })
            }
        }
        return def to report
    }

    private fun readMapInfo(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return file.readLines()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null
                else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
            }
            .filter { it.second.isNotEmpty() }
            .toMap()
    }

    private val colors =
        listOf(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
        )

    /** Old (1.8-era) or casual block names used in "map info.txt". */
    private val iconAliases =
        mapOf(
            "double stone slab" to Material.SMOOTH_STONE,
            "chiseled quartz" to Material.CHISELED_QUARTZ_BLOCK,
            "pillar quartz block" to Material.QUARTZ_PILLAR,
            "red nether brick block" to Material.RED_NETHER_BRICKS,
            "wool block" to Material.WHITE_WOOL,
            "dirt grass block savanna colored" to Material.GRASS_BLOCK,
        )

    private fun iconMaterial(text: String): String? {
        val lower = text.trim().lowercase()
        iconAliases[lower]?.let { return it.name }

        val words = lower.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        val joined = words.joinToString("_")
        val color = colors.sortedByDescending { it.length }.firstOrNull { joined.contains(it) }
        // "hardened clay" was renamed terracotta.
        if ("clay" in words || "hardened" in words || "terracotta" in words) {
            return (if (color != null) "${color.uppercase()}_TERRACOTTA" else "TERRACOTTA")
        }
        // "oak leaf block" -> OAK_LEAVES
        if ("leaf" in words || "leaves" in words) {
            val wood = words.firstOrNull { Material.matchMaterial("${it}_LEAVES") != null }
            if (wood != null) return "${wood.uppercase()}_LEAVES"
        }

        // Icons are blocks, so prefer a block over a same-named item ("nether brick" means the
        // NETHER_BRICKS block, not the NETHER_BRICK ingot).
        val guess = joined.uppercase()
        val candidates =
            listOf(guess, guess + "S", guess.removeSuffix("_BLOCK"), guess.removeSuffix("_BLOCK") + "S", guess + "_BLOCK")
                .distinct()
                .mapNotNull { Material.matchMaterial(it) }
        // LAVA has no item form; the vote menu draws it with a custom model from the resource pack.
        val material =
            candidates.firstOrNull { it.isBlock && it.isItem } ?: candidates.firstOrNull { it.isBlock } ?: candidates.firstOrNull()
        if (material == null) plugin.logger.warning("Unknown map icon '$text'")
        return material?.name
    }

}
