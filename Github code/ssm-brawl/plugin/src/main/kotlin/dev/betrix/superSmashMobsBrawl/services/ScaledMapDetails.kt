package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import gg.flyte.twilight.event.event
import java.util.UUID
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.*
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Half
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent

/** One model per source detail. Invisible native carriers retain climbing and walkable
 * surfaces. Carpets/trapdoors keep vanilla thickness so client and server collision agree;
 * their width/height along the surface doubles. Rails and ladders scale uniformly. */
object ScaledMapDetails {
    const val TAG = "ssm_scaled_detail"
    private const val ORIGIN = "ssm_detail_origin="
    private val air get() = Blocks.AIR.defaultBlockState()
    private val trapdoorCarrier by lazy { BuiltInRegistries.BLOCK.first {
        BuiltInRegistries.BLOCK.getKey(it).path == "waxed_weathered_copper_trapdoor"
    } }
    val carrierMaterials = setOf(Material.PALE_MOSS_CARPET, Material.WEEPING_VINES, Material.WAXED_WEATHERED_COPPER_TRAPDOOR)

    fun isDetail(state: BlockState) = state.block is CarpetBlock || state.block is BaseRailBlock ||
        state.block is TrapDoorBlock || state.block is LadderBlock

    /** Only the exterior plane gets carriers, never the entire 2x2x2 cube. */
    fun enlarged(state: BlockState): Array<BlockState>? {
        if (!isDetail(state)) return null
        val result = Array(8) { air }
        for (y in 0..1) for (z in 0..1) for (x in 0..1) {
            val carrier = when (state.block) {
                is CarpetBlock -> if (y == 0) Blocks.PALE_MOSS_CARPET.defaultBlockState() else air
                is LadderBlock -> if (onFace(state.getValue(LadderBlock.FACING), x, z))
                    Blocks.WEEPING_VINES.defaultBlockState().setValue(BlockStateProperties.AGE_25, 25) else air
                is TrapDoorBlock -> {
                    val open = state.getValue(TrapDoorBlock.OPEN)
                    val facing = state.getValue(TrapDoorBlock.FACING)
                    val half = state.getValue(TrapDoorBlock.HALF)
                    if ((open && onFace(facing, x, z)) || (!open && y == if (half == Half.TOP) 1 else 0))
                        trapdoorCarrier.defaultBlockState()
                            .setValue(TrapDoorBlock.OPEN, open).setValue(TrapDoorBlock.FACING, facing)
                            .setValue(TrapDoorBlock.HALF, half)
                    else air
                }
                else -> air // Rails have no solid collision in vanilla either.
            }
            result[y*4+z*2+x] = carrier
        }
        return result
    }
    // Facing points away from the supporting block, so the model is at the opposite edge.
    private fun onFace(facing: Direction, x: Int, z: Int) = when (facing) {
        Direction.NORTH -> z == 1
        Direction.SOUTH -> z == 0
        Direction.WEST -> x == 1
        Direction.EAST -> x == 0
        else -> false
    }

    data class Detail(val x: Int, val y: Int, val z: Int, val state: BlockState) {
        fun nbt(): CompoundTag {
            val tag = ScaledMapPlants.Plant(x,y,z,state).nbt()
            tag.put("Tags", ListTag().apply {
                add(StringTag.valueOf(TAG)); add(StringTag.valueOf("$ORIGIN$x,$y,$z"))
            })
            val scale = floatArrayOf(2f,2f,2f)
            val shift = floatArrayOf(-1f,0f,-1f)
            if (state.block is CarpetBlock) scale[1] = 1f
            if (state.block is TrapDoorBlock) {
                if (!state.getValue(TrapDoorBlock.OPEN)) {
                    scale[1] = 1f
                    if (state.getValue(TrapDoorBlock.HALF) == Half.TOP) shift[1] = 1f
                } else {
                    val facing = state.getValue(TrapDoorBlock.FACING)
                    val axis = if (facing.axis == Direction.Axis.X) 0 else 2
                    scale[axis] = 1f
                    if (facing == Direction.NORTH || facing == Direction.WEST) shift[axis] = 0f
                }
            }
            tag.getCompoundOrEmpty("transformation").apply {
                put("scale", ListTag().apply { scale.forEach { add(FloatTag.valueOf(it)) } })
                put("translation", ListTag().apply { shift.forEach { add(FloatTag.valueOf(it)) } })
            }
            return tag
        }
    }

    private data class Managed(val display: BlockDisplay, val origin: Location)
    private val loaded = hashMapOf<UUID, Managed>()
    private val bigWorlds = hashSetOf<UUID>()
    fun register(world: org.bukkit.World, scale: MapScale) {
        if (scale == MapScale.BIG) bigWorlds.add(world.uid)
        track(world.entities)
    }
    fun trackRestored(entity:Entity) = track(listOf(entity))
    private fun track(entities: Collection<Entity>) {
        entities.filterIsInstance<BlockDisplay>().filter { TAG in it.scoreboardTags }.forEach { d ->
            val coords = d.scoreboardTags.firstOrNull { it.startsWith(ORIGIN) }?.removePrefix(ORIGIN)
                ?.split(',')?.mapNotNull(String::toIntOrNull) ?: return@forEach
            if (coords.size == 3) loaded[d.uniqueId] = Managed(d, Location(d.world, coords[0].toDouble(),coords[1].toDouble(),coords[2].toDouble()))
        }
    }
    fun setup(plugin: SuperSmashMobsBrawl) {
        event<EntitiesLoadEvent> { track(entities) }
        event<EntitiesUnloadEvent> { entities.forEach { loaded.remove(it.uniqueId) } }
        event<WorldUnloadEvent> {
            bigWorlds.remove(world.uid); loaded.entries.removeIf { it.value.display.world == world }
        }
        event<BlockPhysicsEvent> {
            if (block.world.uid in bigWorlds && block.type in carrierMaterials) isCancelled = true
        }
        event<PlayerInteractEvent> {
            if (player.world.uid in bigWorlds && clickedBlock?.type in carrierMaterials) isCancelled = true
        }
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { removeUnsupported() }, 20L, 20L)
    }
    private fun carriers(m: Managed) = (0..1).flatMap { dy -> (0..1).flatMap { dz -> (0..1).map { dx ->
        m.origin.world.getBlockAt(m.origin.blockX+dx,m.origin.blockY+dy,m.origin.blockZ+dz)
    } } }.filter { it.type in carrierMaterials }
    private fun remove(m: Managed, itemSound: Boolean = false) {
        dev.betrix.superSmashMobsBrawl.smash.SmashService.existingArena(m.display.world)?.rememberDecoration(m.display,carriers(m),itemSound)
        carriers(m).forEach { it.setType(Material.AIR, false) }
        m.display.remove(); loaded.remove(m.display.uniqueId)
    }
    fun removeUnsupported() {
        val doomed = loaded.values.filter { m ->
            if (!m.display.isValid) return@filter true
            val o = m.origin
            if (!o.world.isChunkLoaded(o.blockX shr 4, o.blockZ shr 4)) return@filter false
            val data = m.display.block
            when {
                data is org.bukkit.block.data.type.Ladder -> {
                    val facing = data.facing.oppositeFace
                    val dx = if (facing.modX > 0) 2 else if (facing.modX < 0) -1 else 0
                    val dz = if (facing.modZ > 0) 2 else if (facing.modZ < 0) -1 else 0
                    (0..1).all { dy -> (0..1).all { tangent ->
                        o.world.getBlockAt(o.blockX+dx+if (dx==0) tangent else 0, o.blockY+dy,
                            o.blockZ+dz+if (dz==0) tangent else 0).isPassable
                    } } || carriers(m).isEmpty()
                }
                data is org.bukkit.block.data.type.TrapDoor -> carriers(m).isEmpty()
                else -> (0..1).all { dx -> (0..1).all { dz -> o.world.getBlockAt(o.blockX+dx,o.blockY-1,o.blockZ+dz).isPassable } }
            }
        }
        doomed.forEach { remove(it) }
    }
    fun destroy(center: Location, radius: Double, itemSound: Boolean = false) {
        loaded.values.filter { it.display.world == center.world &&
            it.origin.clone().add(1.0,1.0,1.0).distanceSquared(center) <= (radius+1)*(radius+1) }
            .toList().forEach { remove(it,itemSound) }
    }
    fun addAssets(files: MutableMap<String, ByteArray>) {
        files["assets/ssm/models/block/scaled_collision.json"] = """{"textures":{"particle":"minecraft:block/oak_planks"},"elements":[]}""".toByteArray()
        for (block in listOf("pale_moss_carpet", "waxed_weathered_copper_trapdoor")) {
            files["assets/minecraft/blockstates/$block.json"] =
                """{"variants":{"":{"model":"ssm:block/scaled_collision"}}}""".toByteArray()
        }
        files["assets/minecraft/blockstates/weeping_vines.json"] = """{"multipart":[{"when":{"age":"25"},"apply":{"model":"ssm:block/scaled_collision"}},{"when":{"age":"${(0..24).joinToString("|")}"},"apply":{"model":"minecraft:block/weeping_vines"}}]}""".toByteArray()
    }
}
