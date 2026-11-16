package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.models.brawlData.GameMapDef
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.minecraft.nbt.*
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo
import kotlin.math.*

/** Offline voxel resampling, on the IO dispatcher before Paper loads the disposable world.
 * Reads a bounded chunk cache and writes one destination chunk at a time, even at 8× volume.
 * All original map templates remain untouched. Empty voxels never become solid blocks. */
object MapResampler {
    private val air: Tag = StringTag.valueOf("minecraft:air")
    private data class Cell(val x: Int, val y: Int, val z: Int)
    private data class Column(val x: Int, val z: Int, val mirror: Boolean)
    private data class Decoded(val tag: CompoundTag, val sections: Map<Int, IntArray>, val entities: Map<Cell, CompoundTag>)

    private class Regions(val folder: Path, val writable: Boolean) : AutoCloseable {
        private val open = LinkedHashMap<Pair<Int,Int>, RegionFile>(16, .75f, true)
        private fun region(x: Int, z: Int): RegionFile? {
            val key = (x shr 5) to (z shr 5)
            open[key]?.let { return it }
            val path = folder.resolve("r.${key.first}.${key.second}.mca")
            if (!writable && !Files.exists(path)) return null
            if (open.size >= 12) { val first = open.entries.first(); first.value.close(); open.remove(first.key) }
            return RegionFile(RegionStorageInfo("ssm-resample", Level.OVERWORLD, folder.fileName.toString()), path, folder, false).also { open[key] = it }
        }
        fun read(x: Int,z: Int): CompoundTag? = region(x,z)?.getChunkDataInputStream(ChunkPos(x,z))?.use { NbtIo.read(it) }
        fun write(x: Int,z: Int,tag: CompoundTag) { region(x,z)!!.getChunkDataOutputStream(ChunkPos(x,z)).use { NbtIo.write(tag,it) } }
        override fun close() { open.values.forEach { it.close() }; open.clear() }
    }

    private class Source(folder: Path, val markers: Set<Cell>) : AutoCloseable {
        private val regions = Regions(folder, false)
        private val chunks = LinkedHashMap<Pair<Int,Int>, Decoded?>(128,.75f,true)
        val palette = arrayListOf(air)
        val entityStates = hashSetOf<Int>()
        private val ids = hashMapOf(air to 0)
        private val mirrored = hashMapOf(0 to 0)
        private val states = hashMapOf<Int, BlockState>()
        private val plants = hashMapOf<Int, Boolean>()
        private val details = hashMapOf<Int, Boolean>()
        private val surfaceCover = hashMapOf<Int, Boolean>()
        private val enlarged = hashMapOf<Int, IntArray>()
        private val reduced = object : LinkedHashMap<List<Int>, Int>(1024,.75f,true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Int>,Int>?) = size > 8192
        }
        fun state(id: Int) = states.getOrPut(id) { BlockState.CODEC.parse(NbtOps.INSTANCE,palette[id]).getOrThrow() }
        fun isPlant(id: Int) = plants.getOrPut(id) { ScaledMapPlants.isPlant(state(id)) }
        fun isDetail(id: Int) = details.getOrPut(id) { ScaledMapDetails.isDetail(state(id)) }
        fun surface(id: Int, x: Int, y: Int, z: Int): Int {
            if (!MapBlockShapes.isSurfaceSoil(state(id))) return id
            val above = block(x,y+1,z)
            val covered = surfaceCover.getOrPut(above) {
                val s = state(above)
                s.isSolidRender // Glass/leaves above surface soil must not turn it into buried dirt.
            }
            return if (covered) stateId(Blocks.DIRT.defaultBlockState()) else id
        }
        fun stateId(state: BlockState) = id(BlockState.CODEC.encodeStart(NbtOps.INSTANCE,state).getOrThrow())
        fun big(id: Int, x: Int, y: Int, z: Int): Int = enlarged.getOrPut(id) {
            MapBlockShapes.enlarged(state(id)).map(::stateId).toIntArray()
        }[(y and 1)*4+(z and 1)*2+(x and 1)]
        fun small(ids: List<Int>): Int = reduced.getOrPut(ids) {
            stateId(MapBlockShapes.reduced(ids.map(::state).toTypedArray()))
        }
        fun id(tag: Tag): Int {
            val name = if (tag is CompoundTag) tag.getStringOr("id", "") else tag.asString().orElse("")
            if (name in setOf("minecraft:air","minecraft:cave_air","minecraft:void_air")) return 0
            return ids.getOrPut(tag) { palette.add(tag); palette.lastIndex }
        }
        fun mirror(id: Int): Int = mirrored.getOrPut(id) {
            val state = BlockState.CODEC.parse(NbtOps.INSTANCE, palette[id]).getOrThrow()
            id(BlockState.CODEC.encodeStart(NbtOps.INSTANCE, MapMirror.mirrorState(state)).getOrThrow()).also { if(id in entityStates)entityStates.add(it) }
        }
        fun chunk(x: Int,z: Int): Decoded? {
            val key=x to z
            if (chunks.containsKey(key)) return chunks[key]
            if (chunks.size>=128) chunks.remove(chunks.keys.first())
            val tag=regions.read(x,z)
            val decoded=tag?.let {
                require(!it.contains("Level")) { "Map requires conversion to current Minecraft format" }
                val sections=hashMapOf<Int,IntArray>()
                for (s in it.getListOrEmpty("sections")) if (s is CompoundTag) {
                    val states=s.getCompoundOrEmpty("block_states")
                    val pal=states.getListOrEmpty("palette").map { t -> id(t) }
                    if (pal.isEmpty() || pal.all { v -> v==0 }) continue
                    val data=unpack(states,4096,4)
                    sections[s.getByteOr("Y",0).toInt()]=IntArray(4096) { i -> pal[data[i]] }
                }
                // Strip old wool/plate markers before shrinking, not after voting on blocks.
                markers.filter { m -> m.x shr 4 == x && m.z shr 4 == z }.forEach { m ->
                    sections[m.y shr 4]?.set(((m.y and 15) shl 8) or ((m.z and 15) shl 4) or (m.x and 15),0)
                }
                val blockEntities=it.getListOrEmpty("block_entities").filterIsInstance<CompoundTag>().associateBy { e -> Cell(e.getIntOr("x",0),e.getIntOr("y",0),e.getIntOr("z",0)) }
                blockEntities.keys.forEach { e -> sections[e.y shr 4]?.get(((e.y and 15) shl 8) or ((e.z and 15) shl 4) or (e.x and 15))?.let(entityStates::add) }
                Decoded(it,sections,blockEntities)
            }
            chunks[key]=decoded
            return decoded
        }
        fun block(x: Int,y: Int,z: Int): Int = chunk(x shr 4,z shr 4)?.sections?.get(y shr 4)?.get(((y and 15) shl 8) or ((z and 15) shl 4) or (x and 15)) ?: 0
        override fun close() { regions.close() }
    }

    fun transformCopy(copy: Path, map: GameMapDef, transform: MapTransform) {
        require(copy.fileName.toString().startsWith("ssmbworld_")) { "Only disposable arena copies may be transformed" }
        val folders=Files.walk(copy).use { s -> s.filter { Files.isDirectory(it) && it.fileName.toString()=="region" }.toList() }
        require(folders.size==1) { "Expected one arena dimension, found ${folders.size}" }
        val folder=folders.single()
        val plants=resampleTerrain(folder,map,transform)
        transformEntities(folder.resolveSibling("entities"),map,transform)
        appendPlants(folder.resolveSibling("entities"),plants)
        // POIs and queued physics refer to old coordinates; rebuild them from transformed blocks.
        val poi=folder.resolveSibling("poi")
        if(Files.isDirectory(poi)) Files.list(poi).use { s -> s.filter { it.toString().endsWith(".mca") || it.toString().endsWith(".mcc") }.forEach(Files::delete) }
        if(transform.scale==MapScale.BIG) {
            val settings=copy.resolve("data/minecraft/world_gen_settings.dat")
            val nbt=NbtIo.readCompressed(settings,NbtAccounter.unlimitedHeap())
            nbt.getCompoundOrEmpty("data").getCompoundOrEmpty("dimensions").getCompoundOrEmpty("minecraft:overworld").putString("type","ssm:large_arena")
            NbtIo.writeCompressed(nbt,settings)
        }
    }

    private fun column(x: Int,z: Int,map: GameMapDef,t: MapTransform): Column {
        var sx=if(t.reverse)-x-1 else x
        var mirror=t.reverse
        if(!t.keeps(sx+0.5,map.worldBorderCenterX)) { sx=(2*map.worldBorderCenterX).roundToInt()-sx-1; mirror=!mirror }
        return Column(sx,z,mirror)
    }

    private fun resampleTerrain(folder: Path,map: GameMapDef,t: MapTransform): List<CompoundTag> {
        val plants=ArrayList<CompoundTag>()
        val output=Files.createDirectory(folder.resolveSibling("region-transform"))
        val markers=map.markerBlocks.map { val v=it.split(' ').map(String::toInt); Cell(v[0],v[1],v[2]) }.toSet()
        val half=map.worldBorderSize/2
        val a=(if(t.reverse)-map.worldBorderCenterX else map.worldBorderCenterX)-half
        val b=(if(t.reverse)-map.worldBorderCenterX else map.worldBorderCenterX)+half
        val minX=floor(a*t.scale.factor).toInt() shr 4
        val maxX=ceil(b*t.scale.factor).toInt() shr 4
        val minZ=floor((map.worldBorderCenterZ-half)*t.scale.factor).toInt() shr 4
        val maxZ=ceil((map.worldBorderCenterZ+half)*t.scale.factor).toInt() shr 4
        try {
            Source(folder,markers).use { source -> Regions(output,true).use { target ->
                for(cz in minZ..maxZ) for(cx in minX..maxX) {
                    val span=if(t.scale==MapScale.SMALL)2 else 1
                    val cols=Array(256) { i -> Array(span*span) { offset ->
                        column(t.unscaledCell(cx*16+(i and 15))+offset%span,
                            t.unscaledCell(cz*16+(i shr 4))+offset/span,map,t)
                    } }
                    val relevant=cols.flatMap { it.toList() }.map { (it.x shr 4) to (it.z shr 4) }.distinct().mapNotNull { source.chunk(it.first,it.second) }
                    val ys=relevant.flatMap { it.sections.keys }.flatMap { y -> when(t.scale) {
                        MapScale.BIG -> (y*2..y*2+1).toList()
                        MapScale.SMALL -> listOf(Math.floorDiv(y,2))
                        MapScale.NORMAL -> listOf(y)
                    } }.distinct().sorted()
                    if(ys.isEmpty())continue
                    val sections=ListTag(); val entities=ListTag()
                    for(sy in ys) {
                        val cells=IntArray(4096)
                        for(ly in 0..15) for(i in 0..255) {
                            val y=sy*16+ly; val by=t.unscaledCell(y)
                            var chosenX=0; var chosenY=0; var chosenZ=0
                            val samples=ArrayList<Int>(8)
                            for(dy in 0 until span) for(col in cols[i]) {
                                var id=source.block(col.x,by+dy,col.z)
                                if(t.scale==MapScale.BIG) id=source.surface(id,col.x,by+dy,col.z)
                                if(col.mirror)id=source.mirror(id)
                                if(id!=0) { chosenX=col.x; chosenY=by+dy; chosenZ=col.z }
                                samples.add(id)
                            }
                            val best=when(t.scale) {
                                MapScale.SMALL -> source.small(samples)
                                MapScale.BIG -> source.big(samples[0],cx*16+(i and 15),y,cz*16+(i shr 4))
                                MapScale.NORMAL -> samples[0]
                            }
                            if(t.scale==MapScale.BIG && ((cx*16+(i and 15)) or y or (cz*16+(i shr 4))) and 1 == 0 &&
                                (source.isPlant(samples[0]) || source.isDetail(samples[0]))) {
                                val state=source.state(samples[0])
                                plants.add(if(source.isDetail(samples[0]))
                                    ScaledMapDetails.Detail(cx*16+(i and 15),y,cz*16+(i shr 4),state).nbt()
                                else ScaledMapPlants.Plant(cx*16+(i and 15),y,cz*16+(i shr 4),state,ScaledMapPlants.rootY(y,state)).nbt())
                            }
                            cells[(ly shl 8) or i]=best
                            if(best!=0 && best in source.entityStates) {
                                val e=source.chunk(chosenX shr 4,chosenZ shr 4)?.entities?.get(Cell(chosenX,chosenY,chosenZ))
                                if(e!=null) {
                                    val copy=e.copy()
                                    copy.putInt("x",cx*16+(i and 15));copy.putInt("y",y);copy.putInt("z",cz*16+(i shr 4))
                                    entities.add(copy)
                                }
                            }
                        }
                        if(cells.all { it==0 })continue
                        sections.add(CompoundTag().apply {
                            putByte("Y",sy.toByte());put("block_states",pack(cells,source.palette))
                            put("biomes",CompoundTag().apply { put("palette",ListTag().apply { add(StringTag.valueOf("minecraft:plains")) }) })
                        })
                    }
                    if(sections.isEmpty())continue
                    val chunk=CompoundTag().apply {
                        putInt("DataVersion",relevant.first().tag.getIntOr("DataVersion",5023))
                        putInt("xPos",cx);putInt("zPos",cz);putInt("yPos",if(t.scale==MapScale.BIG)-16 else -4)
                        putString("Status","minecraft:full");putLong("LastUpdate",0);putLong("InhabitedTime",0)
                        put("sections",sections);put("block_entities",entities);putBoolean("isLightOn",false)
                        put("Heightmaps",CompoundTag());put("structures",CompoundTag());put("PostProcessing",ListTag())
                        put("block_ticks",ListTag());put("fluid_ticks",ListTag())
                    }
                    target.write(cx,cz,chunk)
                }
            } }
            replaceRegions(folder,output)
        } finally { cleanOutput(output) }
        return plants
    }

    private fun appendPlants(folder: Path, plants: List<CompoundTag>) {
        if(plants.isEmpty())return
        Files.createDirectories(folder)
        Regions(folder,true).use { target ->
            plants.groupBy { val p=it.getListOrEmpty("Pos"); (floor(p.getDoubleOr(0,0.0)).toInt() shr 4) to (floor(p.getDoubleOr(2,0.0)).toInt() shr 4) }.forEach { (pos, list) ->
                val tag=target.read(pos.first,pos.second) ?: CompoundTag().apply {
                    putInt("DataVersion",5023);putIntArray("Position",intArrayOf(pos.first,pos.second));put("Entities",ListTag())
                }
                val entities=tag.getListOrEmpty("Entities")
                list.forEach { entities.add(it) }
                tag.put("Entities",entities);target.write(pos.first,pos.second,tag)
            }
        }
    }

    private fun transformEntities(folder: Path,map: GameMapDef,t: MapTransform) {
        if(!Files.isDirectory(folder))return
        val output=Files.createDirectory(folder.resolveSibling("entities-transform"))
        try {
            Regions(output,true).use { target ->
                val paths=Files.list(folder).use { s -> s.filter { it.toString().endsWith(".mca") }.toList() }
                for(path in paths) {
                    val parts=path.fileName.toString().split('.');val rx=parts[1].toInt();val rz=parts[2].toInt()
                    RegionFile(RegionStorageInfo("ssm-transform",Level.OVERWORLD,"entities"),path,folder,false).use { input ->
                        for(z in 0..31)for(x in 0..31) {
                            val n=input.getChunkDataInputStream(ChunkPos(rx*32+x,rz*32+z))?.use { NbtIo.read(it) } ?: continue
                            for(e in n.getListOrEmpty("Entities"))if(e is CompoundTag) {
                                val pos=e.getListOrEmpty("Pos");val ex=pos.getDoubleOr(0,0.0);val ez=pos.getDoubleOr(2,0.0)
                                if(!t.keeps(ex,map.worldBorderCenterX) || abs(ex-map.worldBorderCenterX)>map.worldBorderSize/2 || abs(ez-map.worldBorderCenterZ)>map.worldBorderSize/2)continue
                                val copies=if(t.reflect!=ReflectMode.OFF && ex!=map.worldBorderCenterX) listOf(false,true) else listOf(false)
                                for(reflected in copies) {
                                    val copy=e.copy(); moveEntity(copy,map,t,reflected)
                                    val p=copy.getListOrEmpty("Pos");val cx=floor(p.getDoubleOr(0,0.0)).toInt() shr 4;val cz=floor(p.getDoubleOr(2,0.0)).toInt() shr 4
                                    val dest=target.read(cx,cz) ?: CompoundTag().apply { putInt("DataVersion",n.getIntOr("DataVersion",5023));putIntArray("Position",intArrayOf(cx,cz));put("Entities",ListTag()) }
                                    dest.getListOrEmpty("Entities").add(copy);target.write(cx,cz,dest)
                                }
                            }
                        }
                    }
                }
            }
            replaceRegions(folder,output)
        } finally { cleanOutput(output) }
    }

    private fun moveEntity(e: CompoundTag,map: GameMapDef,t: MapTransform,reflect: Boolean) {
        // Mirror metadata/orientation first; then move positions in every dimension.
        val passengers=e.remove("Passengers")
        if(reflect xor t.reverse)MapMirror.mirrorEntity(e)
        val offset=if(reflect)(if(t.reverse)-2 else 2)*map.worldBorderCenterX else 0.0
        val p=e.getListOrEmpty("Pos")
        if(p.size==3)for(axis in 0..2)p[axis]=DoubleTag.valueOf((p.getDoubleOr(axis,0.0)+if(axis==0)offset else 0.0)*t.scale.factor)
        for((axis,key) in listOf("x" to "TileX","y" to "TileY","z" to "TileZ"))if(e.contains(key)) {
            e.putInt(key,floor((e.getIntOr(key,0)+if(axis=="x")offset else 0.0)*t.scale.factor).toInt())
        }
        e.getIntArray("block_pos").ifPresent { a -> if(a.size==3)e.putIntArray("block_pos",IntArray(3){ i -> floor((a[i]+if(i==0)offset else 0.0)*t.scale.factor).toInt() }) }
        if(reflect){val u=UUID.randomUUID();e.putIntArray("UUID",intArrayOf((u.mostSignificantBits shr 32).toInt(),u.mostSignificantBits.toInt(),(u.leastSignificantBits shr 32).toInt(),u.leastSignificantBits.toInt()))}
        if(passengers is ListTag){ passengers.filterIsInstance<CompoundTag>().forEach { moveEntity(it,map,t,reflect) };e.put("Passengers",passengers) }
    }

    private fun replaceRegions(folder: Path,output: Path) {
        Files.list(folder).use { s -> s.filter { it.toString().endsWith(".mca") || it.toString().endsWith(".mcc") }.forEach(Files::delete) }
        Files.list(output).use { s -> s.forEach { Files.move(it,folder.resolve(it.fileName)) } }
    }
    private fun cleanOutput(output: Path) { if(Files.exists(output)){Files.list(output).use { s -> s.forEach(Files::delete) };Files.delete(output)} }

    private fun unpack(states: CompoundTag,count: Int,minBits: Int): IntArray {
        val size=states.getListOrEmpty("palette").size
        if(size<=1)return IntArray(count)
        val bits=maxOf(minBits,32-Integer.numberOfLeadingZeros(size-1));val per=64/bits;val mask=(1L shl bits)-1
        val data=states.getLongArray("data").orElseThrow()
        return IntArray(count){ i -> ((data[i/per] ushr (i%per*bits)) and mask).toInt() }
    }
    private fun pack(cells: IntArray,global: List<Tag>): CompoundTag {
        val local=linkedMapOf<Int,Int>();cells.forEach { local.getOrPut(it){local.size} }
        return CompoundTag().apply {
            put("palette",ListTag().apply { local.keys.forEach { add(global[it]) } })
            if(local.size>1){val bits=maxOf(4,32-Integer.numberOfLeadingZeros(local.size-1));val per=64/bits;val data=LongArray((4096+per-1)/per)
                cells.forEachIndexed { i,id -> data[i/per]=data[i/per] or (local.getValue(id).toLong() shl (i%per*bits)) };putLongArray("data",data)}
        }
    }
}
