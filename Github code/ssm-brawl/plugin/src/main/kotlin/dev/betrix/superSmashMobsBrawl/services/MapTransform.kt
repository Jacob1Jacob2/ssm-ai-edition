package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.models.SpawnPoint
import dev.betrix.superSmashMobsBrawl.models.brawlData.GameMapDef
import kotlin.math.floor
import kotlin.random.Random

enum class ReverseMode(val label: String) {
    OFF("Off"), ON("On"), RANDOM("Random");
    fun resolve(random: Random = Random.Default) = this == ON || (this == RANDOM && random.nextBoolean())
}
enum class ReflectMode(val label: String) { OFF("Off"), LEFT("Left"), RIGHT("Right") }
enum class MapScale(val label: String, val factor: Double) { NORMAL("Normal", 1.0), BIG("2× Bigger", 2.0), SMALL("2× Smaller", 0.5) }

/** Resolve randomness once per match. Reflection uses the original map's bounding-box center.
 * Order: keep/reflect a half, reverse the result, then scale all three axes. */
data class MapTransform(val reverse: Boolean = false, val reflect: ReflectMode = ReflectMode.OFF, val scale: MapScale = MapScale.NORMAL) {
    val active get() = reverse || reflect != ReflectMode.OFF || scale != MapScale.NORMAL
    val description get() = listOfNotNull(if (reverse) "Reversed" else null,
        if (reflect != ReflectMode.OFF) "Reflect ${reflect.label}" else null,
        scale.label.takeIf { scale != MapScale.NORMAL }).joinToString(", ")

    fun keeps(x: Double, center: Double) = when (reflect) {
        ReflectMode.OFF -> true
        ReflectMode.LEFT -> x <= center
        ReflectMode.RIGHT -> x >= center
    }

    fun position(p: SpawnPoint): SpawnPoint {
        val x = if (reverse) -p.x else p.x
        fun scaled(v: Double) = if (scale == MapScale.SMALL) floor(v / 2) + 0.5 else v * scale.factor
        return p.copy(x = scaled(x), y = if (scale == MapScale.SMALL) kotlin.math.ceil(p.y / 2) else p.y * scale.factor,
            z = scaled(p.z), yaw = if (reverse) -p.yaw else p.yaw)
    }

    /** Source cells for one destination cell, before reverse/reflect. Small selects the
     * material and shape of each 2×2×2 cube, preserving empty octants. */
    fun unscaledCell(v: Int): Int = when (scale) {
        MapScale.BIG -> Math.floorDiv(v, 2)
        MapScale.SMALL -> v * 2
        MapScale.NORMAL -> v
    }

    fun definition(map: GameMapDef): GameMapDef {
        val center = map.worldBorderCenterX
        fun points(list: List<SpawnPoint>) = list.filter { keeps(it.x, center) }.flatMap { p ->
            if (reflect == ReflectMode.OFF || p.x == center) listOf(p)
            else listOf(p, p.copy(x = 2 * center - p.x, yaw = -p.yaw))
        }.map(::position).distinctBy { Triple(it.x, it.y, it.z) }
        return map.copy(spawnPoints = points(map.spawnPoints), itemSpawnPoints = points(map.itemSpawnPoints),
            crystalSpawnPoints = points(map.crystalSpawnPoints), spectatorSpawnPoint = position(map.spectatorSpawnPoint),
            worldBorderCenterX = (if (reverse) -center else center) * scale.factor,
            worldBorderCenterZ = map.worldBorderCenterZ * scale.factor,
            worldBorderSize = map.worldBorderSize * scale.factor,
            voidLevel = map.voidLevel * scale.factor - if (scale == MapScale.SMALL) 8 else 0,
            // Markers are removed BEFORE resampling, so they cannot erase real terrain when shrinking.
            markerBlocks = emptyList())
    }
}
