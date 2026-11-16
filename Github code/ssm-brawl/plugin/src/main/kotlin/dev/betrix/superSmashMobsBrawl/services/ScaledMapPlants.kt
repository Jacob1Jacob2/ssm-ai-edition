package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import gg.flyte.twilight.event.event
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.nbt.*
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent

/** Chunk-streamed plant models, with no duplicated plants or invisible solid cubes. */
object ScaledMapPlants {
    const val TAG = "ssm_scaled_plant"
    private const val ROOT = "ssm_plant_root="
    data class Plant(val x: Int, val y: Int, val z: Int, val state: BlockState, val rootY: Int = y) {
        fun nbt(): CompoundTag = CompoundTag().apply {
            val uuid = UUID.randomUUID()
            putString("id","minecraft:block_display")
            putIntArray("UUID", intArrayOf((uuid.mostSignificantBits shr 32).toInt(),uuid.mostSignificantBits.toInt(),
                (uuid.leastSignificantBits shr 32).toInt(),uuid.leastSignificantBits.toInt()))
            put("Pos", ListTag().apply { add(DoubleTag.valueOf(x+1.0));add(DoubleTag.valueOf(y.toDouble()));add(DoubleTag.valueOf(z+1.0)) })
            put("block_state", BlockState.CODEC.encodeStart(NbtOps.INSTANCE,state).getOrThrow())
            put("transformation", CompoundTag().apply {
                fun vector(vararg values: Float) = ListTag().apply { values.forEach { add(FloatTag.valueOf(it)) } }
                put("translation", vector(-1f,0f,-1f)); put("scale", vector(2f,2f,2f))
                put("left_rotation", vector(0f,0f,0f,1f)); put("right_rotation", vector(0f,0f,0f,1f))
            })
            putString("billboard","fixed"); putFloat("width",2f);putFloat("height",2f)
            putFloat("view_range",1.5f);putFloat("shadow_radius",0f)
            putBoolean("NoGravity",true);putBoolean("Invulnerable",true)
            put("Tags", ListTag().apply { add(StringTag.valueOf(TAG));add(StringTag.valueOf("$ROOT$x,$rootY,$z")) })
        }
    }
    fun isPlant(state: BlockState): Boolean = (state.block is VegetationBlock || state.block is SugarCaneBlock) &&
        state.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).isEmpty
    fun rootY(y: Int, state: BlockState): Int =
        if(state.block is DoublePlantBlock && state.getValue(DoublePlantBlock.HALF)==DoubleBlockHalf.UPPER) y-2 else y

    private data class Managed(val display: BlockDisplay, val root: Location)
    private val loaded = hashMapOf<UUID, Managed>()
    private val smallWorlds = hashSetOf<UUID>()
    fun register(world: org.bukkit.World, scale: MapScale) {
        if(scale==MapScale.SMALL)smallWorlds.add(world.uid)
        track(world.entities)
    }
    fun trackRestored(entity:Entity) = track(listOf(entity))
    private fun track(entities: Collection<Entity>) {
        entities.filterIsInstance<BlockDisplay>().filter { TAG in it.scoreboardTags }.forEach { d ->
            val coords=d.scoreboardTags.firstOrNull { it.startsWith(ROOT) }?.removePrefix(ROOT)?.split(',')
                ?.mapNotNull(String::toIntOrNull) ?: return@forEach
            if(coords.size==3)loaded[d.uniqueId]=Managed(d,Location(d.world,coords[0].toDouble(),coords[1].toDouble(),coords[2].toDouble()))
        }
    }
    fun setup(plugin: SuperSmashMobsBrawl) {
        event<EntitiesLoadEvent> { track(entities) }
        event<EntitiesUnloadEvent> { entities.forEach { loaded.remove(it.uniqueId) } }
        event<WorldUnloadEvent> {
            smallWorlds.remove(world.uid); loaded.entries.removeIf { it.value.display.world==world }
        }
        event<BlockPhysicsEvent> {
            if(block.world.uid in smallWorlds && block.type in ScaledTreePosts.materials) isCancelled=true
        }
        Bukkit.getScheduler().runTaskTimer(plugin,Runnable { removeUnsupported() },20L,20L)
    }
    /** Removing terrain should not leave giant plants suspended over the crater. */
    fun removeUnsupported() {
        val orphanRoots=hashSetOf<Location>()
        loaded.values.forEach { (display, root) ->
            if(display.isValid && root.world.isChunkLoaded(root.blockX shr 4,root.blockZ shr 4) &&
                (0..1).all { dx -> (0..1).all { dz -> root.world.getBlockAt(root.blockX+dx,root.blockY-1,root.blockZ+dz).isPassable } })
                orphanRoots.add(root)
        }
        val doomed=loaded.values.filter { !it.display.isValid || it.root in orphanRoots }
        doomed.forEach(::remove)
    }
    fun destroy(center: Location, radius: Double) {
        val roots=loaded.values.filter { it.display.world==center.world &&
            it.display.location.clone().add(0.0,1.0,0.0).distanceSquared(center)<=(radius+1)*(radius+1) }.map { it.root }.toSet()
        loaded.values.filter { it.root in roots }.toList().forEach(::remove)
    }
    private fun remove(plant:Managed) {
        dev.betrix.superSmashMobsBrawl.smash.SmashService.existingArena(plant.display.world)?.rememberDecoration(plant.display)
        plant.display.remove();loaded.remove(plant.display.uniqueId)
    }
}
