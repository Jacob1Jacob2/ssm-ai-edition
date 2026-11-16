package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.models.SpawnPoint
import dev.betrix.superSmashMobsBrawl.models.brawlData.GameMapDef
import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.nbt.*
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.StairsShape
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo

/** Reflect a disposable, not-yet-loaded arena across X=0. Never edits the map template.
 * Blocks occupy [x,x+1], hence block x -> -x-1, while exact positions use x -> -x.
 * Working on regions off the server thread also preserves containers and avoids block physics.
 */
object MapMirror {
    fun definition(map: GameMapDef): GameMapDef = map.copy(
        spawnPoints = map.spawnPoints.map(::spawn),
        spectatorSpawnPoint = spawn(map.spectatorSpawnPoint),
        itemSpawnPoints = map.itemSpawnPoints.map(::spawn),
        crystalSpawnPoints = map.crystalSpawnPoints.map(::spawn),
        worldBorderCenterX = -map.worldBorderCenterX,
        markerBlocks = map.markerBlocks.map { position ->
            val (x, y, z) = position.split(' ').map(String::toInt)
            "${-x - 1} $y $z"
        },
    )

    private fun spawn(p: SpawnPoint) = p.copy(x = -p.x, yaw = -p.yaw)

    fun mirrorCopy(copy: Path) {
        require(copy.fileName.toString().startsWith("ssmbworld_")) { "Only disposable worlds may be mirrored" }
        val folders = Files.walk(copy).use { paths ->
            paths.filter { Files.isDirectory(it) && it.fileName.toString() in setOf("region", "entities", "poi") }.toList()
        }
        val paletteCache = HashMap<Tag, Tag>()
        for (folder in folders) mirrorRegionFolder(folder, paletteCache)
    }

    private fun mirrorRegionFolder(folder: Path, paletteCache: MutableMap<Tag, Tag>) {
        val kind = folder.fileName.toString()
        val regionPattern = Regex("r\\.(-?\\d+)\\.(-?\\d+)\\.mca")
        val regions = Files.list(folder).use { paths -> paths.filter { regionPattern.matches(it.fileName.toString()) }.toList() }
        if (regions.isEmpty()) return
        val output = Files.createDirectory(folder.resolveSibling("$kind-mirror"))
        val info = RegionStorageInfo("ssm-mirror", Level.OVERWORLD, kind)
        try {
            for (path in regions) {
                val match = regionPattern.matchEntire(path.fileName.toString())!!
                val rx = match.groupValues[1].toInt()
                val rz = match.groupValues[2].toInt()
                RegionFile(info, path, folder, false).use { source ->
                    RegionFile(info, output.resolve("r.${-rx - 1}.$rz.mca"), output, false).use { target ->
                        for (z in 0..31) for (x in 0..31) {
                            val before = ChunkPos(rx * 32 + x, rz * 32 + z)
                            val nbt = source.getChunkDataInputStream(before)?.use { NbtIo.read(it) } ?: continue
                            val after = ChunkPos(-before.x - 1, before.z)
                            when (kind) {
                                "region" -> mirrorChunk(nbt, paletteCache)
                                "entities" -> {
                                    nbt.putIntArray("Position", intArrayOf(after.x, after.z))
                                    nbt.getListOrEmpty("Entities").forEach { if (it is CompoundTag) mirrorEntity(it) }
                                }
                                "poi" -> nbt.getCompoundOrEmpty("Sections").values().forEach { section ->
                                    (section as? CompoundTag)?.getListOrEmpty("Records")?.forEach { record ->
                                        if (record is CompoundTag) mirrorBlockPosition(record, "pos")
                                    }
                                }
                            }
                            target.getChunkDataOutputStream(after).use { NbtIo.write(nbt, it) }
                        }
                    }
                }
            }
            // Only disposable copy data is replaced, after every source region was read successfully.
            Files.list(folder).use { paths -> paths.filter { it.toString().endsWith(".mca") || it.toString().endsWith(".mcc") }.forEach(Files::delete) }
            Files.list(output).use { paths -> paths.forEach { Files.move(it, folder.resolve(it.fileName)) } }
        } finally {
            Files.list(output).use { paths -> paths.forEach(Files::delete) }
            Files.delete(output)
        }
    }

    fun mirrorChunk(chunk: CompoundTag, paletteCache: MutableMap<Tag, Tag> = HashMap()) {
        require(!chunk.contains("Level")) { "Map needs conversion to the current Minecraft version before mirroring" }
        chunk.putInt("xPos", -chunk.getIntOr("xPos", 0) - 1)
        chunk.getListOrEmpty("sections").forEach { tag ->
            if (tag !is CompoundTag) return@forEach
            val blocks = tag.getCompoundOrEmpty("block_states")
            val palette = blocks.getListOrEmpty("palette")
            for (i in palette.indices) {
                palette[i] = paletteCache.getOrPut(palette[i]) {
                    val state = BlockState.CODEC.parse(NbtOps.INSTANCE, palette[i]).getOrThrow()
                    BlockState.CODEC.encodeStart(NbtOps.INSTANCE, mirrorState(state)).getOrThrow()
                }
            }
            mirrorPaletteData(blocks, 16, 4)
            mirrorPaletteData(tag.getCompoundOrEmpty("biomes"), 4, 1)
            for (key in listOf("BlockLight", "SkyLight")) {
                tag.getByteArray(key).ifPresent { data ->
                    require(data.size == 2048)
                    val reflected = ByteArray(data.size)
                    for (i in 0 until 4096) {
                        val value = (data[i / 2].toInt() ushr ((i % 2) * 4)) and 15
                        val dest = i xor 15
                        reflected[dest / 2] = (reflected[dest / 2].toInt() or (value shl ((dest % 2) * 4))).toByte()
                    }
                    tag.putByteArray(key, reflected)
                }
            }
        }
        // 384-high overworld height maps have nine bits per column, padded to whole longs.
        val heights = chunk.getCompoundOrEmpty("Heightmaps")
        heights.keySet().toList().forEach { key ->
            heights.getLongArray(key).ifPresent { data ->
                val bits = (1..32).firstOrNull { (256 + 64 / it - 1) / (64 / it) == data.size }
                    ?: error("Unrecognized height map packing")
                heights.putLongArray(key, reflectPacked(data, bits, 256, 16))
            }
        }
        for (key in listOf("block_entities", "block_ticks", "fluid_ticks")) {
            chunk.getListOrEmpty(key).forEach { if (it is CompoundTag && it.contains("x")) it.putInt("x", -it.getIntOr("x", 0) - 1) }
        }
        for (key in listOf("entities", "Entities")) chunk.getListOrEmpty(key).forEach { if (it is CompoundTag) mirrorEntity(it) }
        chunk.getListOrEmpty("PostProcessing").forEach { section ->
            if (section is ListTag) for (i in section.indices) {
                val packed = (section[i] as NumericTag).intValue()
                section[i] = ShortTag.valueOf((packed xor 15).toShort())
            }
        }
        mirrorStructureMetadata(chunk.getCompoundOrEmpty("structures"))
    }

    private fun mirrorPaletteData(container: CompoundTag, width: Int, minimumBits: Int) {
        val size = container.getListOrEmpty("palette").size
        if (size <= 1) return
        val bits = maxOf(minimumBits, 32 - Integer.numberOfLeadingZeros(size - 1))
        container.getLongArray("data").ifPresent { data ->
            container.putLongArray("data", reflectPacked(data, bits, width * width * width, width))
        }
    }

    fun mirrorState(state: BlockState): BlockState {
        var mirrored = state.mirror(Mirror.FRONT_BACK)
        // Vanilla structure mirroring leaves some inner stair corners with the original handedness.
        // Reflection always exchanges left/right, including stairs facing along the mirror plane.
        if (state.hasProperty(BlockStateProperties.STAIRS_SHAPE)) {
            mirrored = mirrored.setValue(BlockStateProperties.STAIRS_SHAPE, when (state.getValue(BlockStateProperties.STAIRS_SHAPE)) {
                StairsShape.INNER_LEFT -> StairsShape.INNER_RIGHT
                StairsShape.INNER_RIGHT -> StairsShape.INNER_LEFT
                StairsShape.OUTER_LEFT -> StairsShape.OUTER_RIGHT
                StairsShape.OUTER_RIGHT -> StairsShape.OUTER_LEFT
                else -> StairsShape.STRAIGHT
            })
        }
        // Double chests also need their left/right halves exchanged, preserving both inventories.
        if (state.hasProperty(BlockStateProperties.CHEST_TYPE)) {
            mirrored = mirrored.setValue(BlockStateProperties.CHEST_TYPE, when (state.getValue(BlockStateProperties.CHEST_TYPE)) {
                ChestType.LEFT -> ChestType.RIGHT
                ChestType.RIGHT -> ChestType.LEFT
                else -> ChestType.SINGLE
            })
        }
        return mirrored
    }

    /** Minecraft's padded paletted storage: X is the fastest-changing coordinate. */
    fun reflectPacked(data: LongArray, bits: Int, count: Int, width: Int): LongArray {
        val perLong = 64 / bits
        require(data.size == (count + perLong - 1) / perLong) { "Unexpected packed array length" }
        val mask = (1L shl bits) - 1
        val out = LongArray(data.size)
        for (i in 0 until count) {
            val value = (data[i / perLong] ushr (i % perLong * bits)) and mask
            val dest = i / width * width + width - 1 - i % width
            out[dest / perLong] = out[dest / perLong] or (value shl (dest % perLong * bits))
        }
        return out
    }

    private fun mirrorBlockPosition(nbt: CompoundTag, key: String) {
        nbt.getIntArray(key).ifPresent { pos -> if (pos.size == 3) nbt.putIntArray(key, intArrayOf(-pos[0] - 1, pos[1], pos[2])) }
    }

    fun mirrorEntity(entity: CompoundTag) {
        for (key in listOf("Pos", "Motion")) {
            val values = entity.getListOrEmpty(key)
            if (values.size == 3) values[0] = DoubleTag.valueOf(-values.getDoubleOr(0, 0.0))
        }
        val rotation = entity.getListOrEmpty("Rotation")
        if (rotation.size == 2) rotation[0] = FloatTag.valueOf(-rotation.getFloatOr(0, 0f))
        mirrorBlockPosition(entity, "block_pos") // paintings / item frames in 26.3
        for (key in listOf("TileX", "APX", "SleepingX")) if (entity.contains(key)) entity.putInt(key, -entity.getIntOr(key, 0) - 1)
        if (entity.contains("Facing")) {
            val facing = entity.getByteOr("Facing", 0).toInt()
            if (facing == 4 || facing == 5) entity.putByte("Facing", (9 - facing).toByte())
        }
        if (entity.contains("ItemRotation")) entity.putByte("ItemRotation", ((8 - entity.getByteOr("ItemRotation", 0)) % 8).toByte())
        val leash = entity.getCompoundOrEmpty("leash")
        mirrorBlockPosition(leash, "pos")
        val legacyLeash = entity.getCompoundOrEmpty("Leash")
        if (legacyLeash.contains("X")) legacyLeash.putInt("X", -legacyLeash.getIntOr("X", 0) - 1)
        val pose = entity.getCompoundOrEmpty("Pose")
        for (key in pose.keySet().toList()) {
            val angles = pose.getListOrEmpty(key)
            if (angles.size == 3) for (axis in 1..2) angles[axis] = FloatTag.valueOf(-angles.getFloatOr(axis, 0f))
        }
        for ((left, right) in listOf("LeftArm" to "RightArm", "LeftLeg" to "RightLeg")) {
            val a = pose.remove(left); val b = pose.remove(right)
            if (a != null) pose.put(right, a)
            if (b != null) pose.put(left, b)
        }
        // Saved villager beds/workplaces must follow the reflected buildings as well.
        val memories = entity.getCompoundOrEmpty("Brain").getCompoundOrEmpty("memories")
        memories.values().forEach { memory ->
            if (memory is CompoundTag) mirrorBlockPosition(memory.getCompoundOrEmpty("value"), "pos")
        }
        entity.getListOrEmpty("Passengers").forEach { if (it is CompoundTag) mirrorEntity(it) }
    }

    private fun mirrorStructureMetadata(structures: CompoundTag) {
        val references = structures.getCompoundOrEmpty("References")
        references.keySet().toList().forEach { key -> references.getLongArray(key).ifPresent { values ->
            references.putLongArray(key, values.map { packed -> packed xor 0xffffffffL }.toLongArray())
        } }
        fun reflectTree(tag: CompoundTag) {
            if (tag.contains("ChunkX")) tag.putInt("ChunkX", -tag.getIntOr("ChunkX", 0) - 1)
            tag.getIntArray("BB").ifPresent { bb -> if (bb.size == 6) tag.putIntArray("BB", intArrayOf(-bb[3]-1, bb[1], bb[2], -bb[0]-1, bb[4], bb[5])) }
            tag.values().forEach { value ->
                if (value is CompoundTag) reflectTree(value)
                if (value is ListTag) value.forEach { if (it is CompoundTag) reflectTree(it) }
            }
        }
        reflectTree(structures.getCompoundOrEmpty("starts"))
    }
}
