package dev.betrix.superSmashMobsBrawl.models.brawlData

import dev.betrix.superSmashMobsBrawl.models.SpawnPoint
import kotlinx.serialization.Serializable

@Serializable
data class MapDefFile(
    val gameMaps: List<GameMapDef> = emptyList(),
    val hubMaps: List<HubMapDef> = emptyList(),
)

@Serializable
sealed class MapDef {
    abstract val id: String
    abstract val worldBorderSize: Double
    abstract val voidLevel: Double
    abstract val creators: List<String>
    abstract val spawnPoints: List<SpawnPoint>
}

@Serializable
data class GameMapDef(
    override val id: String,
    override val worldBorderSize: Double,
    override val voidLevel: Double,
    val maxPlayers: Int,
    override val creators: List<String> = emptyList(),
    override val spawnPoints: List<SpawnPoint>,
    val spectatorSpawnPoint: SpawnPoint,
    // --- SSM AI Edition: filled in by MapScanService from the wool/pressure-plate markers ---
    /** Red wool + iron plate markers. */
    val itemSpawnPoints: List<SpawnPoint> = emptyList(),
    /** Yellow wool + wooden plate markers. */
    val crystalSpawnPoints: List<SpawnPoint> = emptyList(),
    /** Marker blocks ("x y z") to turn into air whenever a copy of this map is loaded. */
    val markerBlocks: List<String> = emptyList(),
    val worldBorderCenterX: Double = 0.0,
    val worldBorderCenterZ: Double = 0.0,
    /** From "map info.txt". */
    val displayName: String? = null,
    val icon: String? = null,
    val size: String? = null,
    val isNew: Boolean = false,
    /**
     * Which of its layouts this is, when the map modifiers have made another arena of it
     * (MapTransform.key: "rev", "left+small" ...). Empty for the map as it was built.
     */
    val layout: String = "",
) : MapDef()

@Serializable
data class HubMapDef(
    override val id: String,
    override val worldBorderSize: Double,
    override val voidLevel: Double,
    override val creators: List<String> = emptyList(),
    override val spawnPoints: List<SpawnPoint>,
) : MapDef()
