package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.models.brawlData.GameMapDef
import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.nbt.*
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo
import kotlin.math.floor

/** Imported worlds are templates, not generators. Only modify the disposable copy. */
object ArenaSanitizer {
    fun prepare(copy: Path, map: GameMapDef? = null) {
        require(copy.fileName.toString().startsWith("ssmbworld_"))
        val path = copy.resolve("data/minecraft/world_gen_settings.dat")
        val settings = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap())
        val data = settings.getCompoundOrEmpty("data")
        data.putBoolean("generate_structures", false)
        data.getCompoundOrEmpty("dimensions").getCompoundOrEmpty("minecraft:overworld").put("generator",
            CompoundTag().apply {
                putString("type", "minecraft:flat")
                put("settings", CompoundTag().apply {
                    putBoolean("features", false); putBoolean("lakes", false)
                    put("layers", ListTag()); put("structure_overrides", ListTag())
                    putString("biome", "minecraft:the_void")
                })
            })
        NbtIo.writeCompressed(settings, path)
        if (map == null) return
        // The saved vanilla structures are outside the authored arena bounds. Discard those
        // chunks, including their entity/POI data, and clear generation starts/references.
        val half = map.worldBorderSize / 2
        val minX = floor(map.worldBorderCenterX - half).toInt() shr 4
        val maxX = floor(map.worldBorderCenterX + half).toInt() shr 4
        val minZ = floor(map.worldBorderCenterZ - half).toInt() shr 4
        val maxZ = floor(map.worldBorderCenterZ + half).toInt() shr 4
        val regions = Files.walk(copy).use { s -> s.filter {
            it.fileName.toString().endsWith(".mca") && it.parent.fileName.toString() in setOf("region", "entities", "poi")
        }.toList() }
        for (file in regions) {
            val parts = file.fileName.toString().split('.')
            val rx = parts[1].toInt(); val rz = parts[2].toInt()
            RegionFile(RegionStorageInfo("ssm-clean", Level.OVERWORLD, file.parent.fileName.toString()), file, file.parent, false).use { region ->
                for (z in 0..31) for (x in 0..31) {
                    val pos = ChunkPos(rx * 32 + x, rz * 32 + z)
                    if (!region.hasChunk(pos)) continue
                    if (pos.x !in minX..maxX || pos.z !in minZ..maxZ) { region.clear(pos); continue }
                    val kind = file.parent.fileName.toString()
                    if (kind != "region" && !(kind == "entities" && map.id == "build")) continue
                    val nbt = region.getChunkDataInputStream(pos)?.use { NbtIo.read(it) } ?: continue
                    if (kind == "entities") {
                        val entities = nbt.getListOrEmpty("Entities")
                        entities.removeIf { it is CompoundTag && it.getStringOr("id", "") == "minecraft:chicken" &&
                            it.getStringOr("CustomName", "").equals("Barbie", ignoreCase = true) }
                        nbt.put("Entities", entities)
                    } else nbt.put("structures", CompoundTag())
                    region.getChunkDataOutputStream(pos).use { NbtIo.write(nbt, it) }
                }
            }
        }
    }
}
