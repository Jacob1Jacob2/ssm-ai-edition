package dev.betrix.superSmashMobsBrawl.services

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.minecraft.nbt.*
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo

/** Runs only on a new hub copy. Blocks, NPC positions and PDC are retained; identities are new. */
object HubClone {
    fun renewEntityIds(copy: Path) {
        require(copy.fileName.toString() in (2..5).map { "ssm_lobby_$it" })
        val ids = mutableMapOf<List<Int>, IntArray>()
        fun fresh(old: IntArray): IntArray = ids.getOrPut(old.toList()) {
            val id = UUID.randomUUID()
            intArrayOf((id.mostSignificantBits shr 32).toInt(), id.mostSignificantBits.toInt(),
                (id.leastSignificantBits shr 32).toInt(), id.leastSignificantBits.toInt())
        }
        fun rewrite(tag: Tag) {
            when (tag) {
                is CompoundTag -> tag.keySet().toList().forEach { key ->
                    val value = tag.get(key) ?: return@forEach
                    if (value is IntArrayTag && value.asIntArray.size == 4 &&
                        (key.contains("uuid", true) || key in setOf("Owner", "LoveCause", "HurtBy", "ConversionPlayer"))) {
                        tag.putIntArray(key, fresh(value.asIntArray))
                    } else rewrite(value)
                }
                is ListTag -> tag.forEach(::rewrite)
                else -> {}
            }
        }
        // Paper 26.x stores the world UUID here instead of uid.dat.
        val metadata = copy.resolve("data/paper/metadata.dat")
        if (Files.exists(metadata)) {
            val tag = NbtIo.readCompressed(metadata, NbtAccounter.unlimitedHeap())
            rewrite(tag)
            NbtIo.writeCompressed(tag, metadata)
        }
        Files.walk(copy).use { paths -> paths.filter { it.fileName.toString().endsWith(".mca") &&
            it.parent.fileName.toString() in setOf("entities", "region") }.toList() }.forEach { file ->
            val parts = file.fileName.toString().split('.')
            RegionFile(RegionStorageInfo("ssm-hub-clone", Level.OVERWORLD, file.parent.fileName.toString()), file, file.parent, false).use { region ->
                for (z in 0..31) for (x in 0..31) {
                    val pos = ChunkPos(parts[1].toInt() * 32 + x, parts[2].toInt() * 32 + z)
                    if (!region.hasChunk(pos)) continue
                    val tag = region.getChunkDataInputStream(pos)?.use { NbtIo.read(it) } ?: continue
                    rewrite(tag)
                    region.getChunkDataOutputStream(pos).use { NbtIo.write(tag, it) }
                }
            }
        }
    }
}
