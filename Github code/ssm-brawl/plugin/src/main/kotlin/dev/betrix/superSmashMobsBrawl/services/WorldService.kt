package dev.betrix.superSmashMobsBrawl.services

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onFailure
import com.github.shynixn.mccoroutine.bukkit.minecraftDispatcher
import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.models.BrawlGameWorld
import dev.betrix.superSmashMobsBrawl.models.BrawlHubWorld
import dev.betrix.superSmashMobsBrawl.models.BrawlWorld
import dev.betrix.superSmashMobsBrawl.models.brawlData.GameMapDef
import dev.betrix.superSmashMobsBrawl.models.brawlData.HubMapDef
import dev.betrix.superSmashMobsBrawl.models.brawlData.MapDef
import gg.flyte.twilight.event.event
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.GameRule
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.event.world.EntitiesLoadEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object WorldService : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()

    private val loadedWorlds = hashMapOf<String, BrawlWorld>()
    private val gameWorldNames = hashSetOf<String>()

    private const val WORLD_PREFIX = "ssmbworld_"

    fun setup() {
        ScaledMapPlants.setup(plugin)
        ScaledMapDetails.setup(plugin)
        // A thrown egg is a projectile here (Egg Blaster), never a way to get chickens: one in
        // eight used to hatch a chick that then stood about the arena for the rest of the match.
        event<com.destroystokyo.paper.event.entity.ThrownEggHatchEvent> { isHatching = false }
        event<EntitiesLoadEvent> {
            if (world.name in gameWorldNames) removeLegacyMarkerLabels(entities)
        }
    }

    /** Imported map labels (including Nixuge) are empty invisible marker stands. */
    private fun removeLegacyMarkerLabels(entities: Collection<Entity>) {
        entities.filterIsInstance<ArmorStand>().forEach { stand ->
            if (!stand.isMarker || stand.isVisible || !stand.isCustomNameVisible) return@forEach
            val name = stand.customName()?.let { PlainTextComponentSerializer.plainText().serialize(it) }
                ?.replace(Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE), "") ?: return@forEach
            if (name.isBlank()) return@forEach
            val equipment = stand.equipment
            if (equipment.armorContents.any { it != null && !it.type.isAir } ||
                !equipment.itemInMainHand.type.isAir || !equipment.itemInOffHand.type.isAir) return@forEach
            stand.remove()
        }
    }

    /**
     * Called from onDisable. The Bukkit scheduler (and so minecraftDispatcher) is already gone at
     * this point, so this unloads and deletes synchronously on the calling (main) thread instead of
     * going through deleteWorld(). Anything that still fails is removed by deleteStaleWorlds() on the
     * next startup.
     */
    fun teardown() {
        val serverFolder = Bukkit.getWorldContainer().toPath()
        loadedWorlds.keys.toList().forEach { name ->
            runCatching {
                    val actualName = getActualWorldName(name) ?: return@runCatching
                    val world = Bukkit.getWorld(actualName)
                    val folder = world?.worldFolder?.toPath()
                    if (world != null) Bukkit.unloadWorld(world, false)
                    listOfNotNull(
                            folder,
                            serverFolder.resolve(actualName),
                            dimensionFolder(serverFolder, actualName),
                        )
                        .distinct()
                        .filter { Files.exists(it) }
                        .forEach { deleteWorldFolder(it).getOrThrow() }
                }
                .onFailure { plugin.logger.warning("Failed to delete world $name: ${it.message}") }
            loadedWorlds.remove(name)
        }
        gameWorldNames.clear()
    }

    suspend fun copyAndLoadWorld(
        map: MapDef,
        customWorldName: String = UUID.randomUUID().toString(),
        reverse: Boolean = false,
        transform: MapTransform = MapTransform(reverse),
    ): Result<BrawlWorld, Exception> =
        withContext(Dispatchers.IO) {
            val copyResult = runCatching {
                val serverFolder = Bukkit.getWorldContainer().toPath()
                val worldsFolder = serverFolder.resolve("worlds")
                val sourceWorldPath = worldsFolder.resolve(map.id)

                require(Files.exists(sourceWorldPath)) {
                    "Source world '$map.id' does not exist in ./worlds directory!"
                }

                require(Files.isDirectory(sourceWorldPath)) {
                    "Source '$map.id' is not a directory!"
                }

                val prefixedWorldName = addWorldPrefix(customWorldName)

                val targetWorldPath = serverFolder.resolve(prefixedWorldName)
                require(
                    !Files.exists(targetWorldPath) && Bukkit.getWorld(prefixedWorldName) == null
                ) {
                    "World with name '$prefixedWorldName' already exists!"
                }

                // Copy the world folder
                try {
                    copyWorldFolder(sourceWorldPath, targetWorldPath).getOrThrow()
                    if (map is GameMapDef) ArenaSanitizer.prepare(targetWorldPath, map)
                    if (transform.active && map is GameMapDef) MapResampler.transformCopy(targetWorldPath, map, transform)
                } catch (error: Exception) {
                    if (Files.exists(targetWorldPath)) deleteWorldFolder(targetWorldPath)
                    throw error
                }
                prefixedWorldName
            }

            withContext(plugin.minecraftDispatcher) {
                copyResult.fold(
                    onSuccess = { worldName ->
                        runCatching {
                                val worldCreator = WorldCreator(worldName).generateStructures(false)
                                val world = Bukkit.createWorld(worldCreator)

                                require(world != null) {
                                    "Failed to create world: $worldName (Bukkit.createWorld returned null)"
                                }

                                setupWorld(world, if (transform.active && map is GameMapDef) transform.definition(map) else map)
                                if(map is GameMapDef) {
                                    ScaledMapPlants.register(world,transform.scale)
                                    ScaledMapDetails.register(world,transform.scale)
                                }
                                if (transform.active && map is GameMapDef) {
                                    val updated = transform.definition(map)
                                    val p = updated.spectatorSpawnPoint
                                    world.setSpawnLocation(org.bukkit.Location(world,p.x,p.y,p.z,p.yaw,p.pitch))
                                    require(transform.scale != MapScale.BIG || world.maxHeight >= 1280) { "Large arena dimension was not loaded" }
                                }

                                world
                            }
                            .fold(
                                onSuccess = { world ->
                                    val loadedWorld =
                                        when (map) {
                                            is GameMapDef -> BrawlGameWorld(world, if (transform.active) transform.definition(map) else map, transform)
                                            is HubMapDef -> BrawlHubWorld(world, map)
                                        }

                                    loadedWorlds[customWorldName] = loadedWorld

                                    Ok(loadedWorld)
                                },
                                onFailure = { Err(RuntimeException(it.message, it)) },
                            )
                    },
                    onFailure = {
                        return@fold Err(RuntimeException(it.message, it))
                    },
                )
            }
        }

    /** The lobby is editable and survives restarts; only match worlds are disposable. */
    suspend fun loadPersistentHub(map: HubMapDef, lobbyId: Int = 1): Result<BrawlHubWorld, Exception> {
        return try {
            require(lobbyId in LobbyRouting.ids)
            val name = if (lobbyId == 1) "ssm_lobby" else "ssm_lobby_$lobbyId"
            val primary = Bukkit.getWorld("ssm_lobby")
            if (lobbyId != 1) primary?.save()
            val source = primary?.worldFolder?.toPath()
            withContext(Dispatchers.IO) {
                val root = Bukkit.getWorldContainer().toPath()
                if (!Files.isDirectory(root.resolve(name)) && !Files.isDirectory(dimensionFolder(root, name))) {
                    // A live 26.x world is already in the dimension-storage format. Importing
                    // that as a legacy world silently generates new terrain instead of copying it.
                    val target = if (lobbyId == 1) root.resolve(name) else dimensionFolder(root, name)
                    try {
                        copyWorldFolder(if (lobbyId == 1) root.resolve("worlds").resolve(map.id) else requireNotNull(source), target).getOrThrow()
                        if (lobbyId != 1) HubClone.renewEntityIds(target)
                    } catch (error: Exception) {
                        if (Files.exists(target)) deleteWorldFolder(target).getOrThrow()
                        throw error
                    }
                }
            }
            withContext(plugin.minecraftDispatcher) {
                val world = Bukkit.getWorld(name) ?: Bukkit.createWorld(WorldCreator(name))
                    ?: error("Could not load the persistent lobby")
                setupWorld(world, map)
                world.isAutoSave = true
                Ok(BrawlHubWorld(world, map))
            }
        } catch (error: Exception) { Err(error) }
    }

    suspend fun deleteWorld(world: World): Result<Unit, Exception> {
        val loadedWorld = loadedWorlds.entries.find { it.value.world == world }?.value

        if (loadedWorld == null) {
            return Err(Exception("No loaded world found for world $world"))
        }

        return deleteWorld(loadedWorld)
    }

    suspend fun deleteWorld(loadedWorld: BrawlWorld): Result<Unit, Exception> =
        withContext(plugin.minecraftDispatcher) mainContext@{
            val loadedWorldEntry = loadedWorlds.entries.find { it.value == loadedWorld }

            if (loadedWorldEntry == null) {
                return@mainContext Err(Exception("No loaded world found for $loadedWorld"))
            }

            val worldName = loadedWorldEntry.key

            runCatching {
                    val actualWorldName =
                        getActualWorldName(worldName)
                            ?: error(
                                "World '$worldName' not found (checked both prefixed and non-prefixed versions)"
                            )

                    val world = Bukkit.getWorld(actualWorldName)
                    // Paper 26.x migrates copied worlds into world/dimensions/minecraft/<name>,
                    // so remember the real folder before unloading.
                    world?.worldFolder?.toPath()?.let { migratedFolders[worldName] = it }
                    if (world != null) {
                        val mainWorld =
                            Bukkit.getWorlds().firstOrNull { it != world }
                                ?: error("No other world available to move players to!")

                        world.players.forEach { player -> player.teleport(mainWorld.spawnLocation) }

                        require(Bukkit.unloadWorld(world, false)) {
                            "Failed to unload world: $actualWorldName"
                        }
                    }
                    gameWorldNames.remove(actualWorldName)
                    actualWorldName
                }
                .fold(
                    onSuccess = { actualWorldName ->
                        return@mainContext withContext(Dispatchers.IO) {
                            runCatching {
                                    val serverFolder = Bukkit.getWorldContainer().toPath()
                                    val worldPath = serverFolder.resolve(actualWorldName)

                                    if (Files.exists(worldPath)) {
                                        deleteWorldFolder(worldPath).getOrThrow()
                                    }

                                    val migrated =
                                        migratedFolders.remove(worldName)
                                            ?: dimensionFolder(serverFolder, actualWorldName)
                                    if (Files.exists(migrated)) {
                                        deleteWorldFolder(migrated).getOrThrow()
                                    }

                                    loadedWorlds.remove(worldName)
                                }
                                .fold(
                                    onSuccess = { Ok(Unit) },
                                    onFailure = { Err(RuntimeException(it.message, it)) },
                                )
                        }
                    },
                    onFailure = { err ->
                        return@mainContext Err(RuntimeException(err.message, err))
                    },
                )
        }

    /**
     * Copies worlds/<sourceId> to a throwaway world and loads it (used by the map scanner). The
     * source folder is never loaded directly, because Paper 26.x would migrate (move) its files.
     */
    suspend fun loadScratchWorld(sourceId: String): World {
        val name = addWorldPrefix("scan_" + UUID.randomUUID().toString().take(8))
        withContext(Dispatchers.IO) {
            val serverFolder = Bukkit.getWorldContainer().toPath()
            val source = serverFolder.resolve("worlds").resolve(sourceId)
            require(Files.isDirectory(source)) { "worlds/$sourceId does not exist" }
            copyWorldFolder(source, serverFolder.resolve(name)).getOrThrow()
            ArenaSanitizer.prepare(serverFolder.resolve(name))
        }
        return withContext(plugin.minecraftDispatcher) {
            Bukkit.createWorld(WorldCreator(name).generateStructures(false)) ?: error("Failed to load scratch world $name")
        }
    }

    /** Unloads and deletes a world created by [loadScratchWorld]. Must be called on the main thread. */
    suspend fun deleteScratchWorld(world: World) {
        val name = world.name
        val folder = world.worldFolder.toPath()
        withContext(plugin.minecraftDispatcher) { Bukkit.unloadWorld(world, false) }
        withContext(Dispatchers.IO) {
            val serverFolder = Bukkit.getWorldContainer().toPath()
            listOf(folder, serverFolder.resolve(name), dimensionFolder(serverFolder, name))
                .distinct()
                .filter { Files.exists(it) }
                .forEach { deleteWorldFolder(it) }
        }
    }

    private fun setupWorld(world: World, map: MapDef) {
        if (map is GameMapDef) {
            gameWorldNames.add(world.name)
            removeLegacyMarkerLabels(world.entities)
            // Strip the wool/pressure-plate spawn markers so players never see them.
            map.markerBlocks.forEach { pos ->
                val (x, y, z) = pos.split(' ').map { it.toInt() }
                world.getBlockAt(x, y, z).setType(org.bukkit.Material.AIR, false)
            }
        }
        // SSM AI Edition: no world border (the void is the edge of the map).
        world.worldBorder.reset()
        // ...and no fire spreading or burning the map.
        @Suppress("DEPRECATION") world.setGameRule(GameRule.DO_FIRE_TICK, false)
        world.setGameRule(org.bukkit.GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0)
        world.setStorm(false)
        world.isVoidDamageEnabled = false
        world.time = 5000L
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
        world.setGameRule(GameRule.ALLOW_FIRE_TICKS_AWAY_FROM_PLAYER, false)
        world.setGameRule(GameRule.DO_MOB_LOOT, false)
        world.setGameRule(GameRule.DO_VINES_SPREAD, false)
        world.setGameRule(GameRule.MOB_GRIEFING, false)
        // Kit armor would otherwise spam "has made the advancement [Suit Up]" etc.
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false)
    }

    /**
     * Gets the actual world name that might be prefixed, checking both prefixed and non-prefixed
     * versions
     */
    private fun getActualWorldName(worldName: String): String? {
        val prefixedName = addWorldPrefix(worldName)
        val nonPrefixedName = removeWorldPrefix(worldName)

        return when {
            Bukkit.getWorld(prefixedName) != null -> prefixedName
            Bukkit.getWorld(nonPrefixedName) != null -> nonPrefixedName
            else -> {
                val serverFolder = Bukkit.getWorldContainer().toPath()
                when {
                    Files.exists(serverFolder.resolve(prefixedName)) -> prefixedName
                    Files.exists(serverFolder.resolve(nonPrefixedName)) -> nonPrefixedName
                    else -> null
                }
            }
        }
    }

    private fun deleteWorldFolder(worldPath: Path): kotlin.Result<Unit> {
        return runCatching {
            Files.walkFileTree(
                worldPath,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc != null) throw exc
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }

    private fun copyWorldFolder(source: Path, target: Path): kotlin.Result<Unit> {
        return runCatching {
            Files.walkFileTree(
                source,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        val targetDir = target.resolve(source.relativize(dir))
                        val dirName = dir.fileName.toString()

                        // Skip problematic directories
                        if (dirName == "session.lock" || dirName == "uid.dat") {
                            return FileVisitResult.SKIP_SUBTREE
                        }

                        Files.createDirectories(targetDir)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        val fileName = file.fileName.toString()
                        if (fileName == "session.lock" || fileName == "uid.dat") {
                            return FileVisitResult.CONTINUE
                        }
                        // Map pictures / info / downloader notes aren't world data.
                        val lower = fileName.lowercase()
                        if (lower.endsWith(".png") || lower == "map info.txt" || lower == "worlddownloader.txt") {
                            return FileVisitResult.CONTINUE
                        }

                        val targetFile = target.resolve(source.relativize(file))
                        Files.createDirectories(targetFile.parent)
                        Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING)

                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }

    /** Where Paper 26.x stores a custom world after its legacy-folder import. */
    private fun dimensionFolder(serverFolder: Path, worldName: String): Path =
        serverFolder.resolve("world").resolve("dimensions").resolve("minecraft").resolve(worldName)

    /**
     * Deletes temporary game/hub worlds left behind by a crash or hard kill. Must run in onLoad,
     * before the server loads any worlds.
     */
    fun deleteStaleWorlds(serverFolder: Path, log: (String) -> Unit) {
        val candidates =
            listOf(serverFolder, serverFolder.resolve("world/dimensions/minecraft")).flatMap { dir ->
                if (!Files.isDirectory(dir)) emptyList()
                else
                    Files.list(dir).use { s ->
                        s.filter {
                                Files.isDirectory(it) &&
                                    it.fileName.toString().startsWith(WORLD_PREFIX)
                            }
                            .toList()
                    }
            }
        candidates.forEach { path ->
            deleteWorldFolder(path)
                .onSuccess { log("Deleted stale temporary world ${path.fileName}") }
                .onFailure { log("Could not delete stale world ${path.fileName}: ${it.message}") }
        }
    }

    private val migratedFolders = hashMapOf<String, Path>()

    private fun addWorldPrefix(worldName: String): String = "$WORLD_PREFIX$worldName"

    private fun removeWorldPrefix(worldName: String): String = worldName.removePrefix(WORLD_PREFIX)
}
