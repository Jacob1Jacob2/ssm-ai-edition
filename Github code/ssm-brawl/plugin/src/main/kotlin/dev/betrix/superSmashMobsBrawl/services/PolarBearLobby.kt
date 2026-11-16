package dev.betrix.superSmashMobsBrawl.services

import org.bukkit.*
import org.bukkit.persistence.PersistentDataType

/** One-time addition to the existing build; later builder edits remain authoritative. */
object PolarBearLobby {
    private val installed = NamespacedKey("ssm", "polar_bear_platform_v1")
    fun install(world: World) {
        if (world.persistentDataContainer.has(installed)) return
        world.getChunkAt(-2, -1).load()
        if (world.entities.any { it.persistentDataContainer.get(LobbyBuildService.KIT_KEY, PersistentDataType.STRING) == "polar_bear" }) {
            world.persistentDataContainer.set(installed, PersistentDataType.BYTE, 1)
            return
        }
        // Existing empty platform immediately north of Phantom: same 3x3 footprint and slab height.
        val at = Location(world, -19.5, 62.0, -11.5, 270f, 0f)
        val top = (-21..-19).flatMap { x -> (-13..-11).map { z -> world.getBlockAt(x, 61, z) } }
        if (top.any { it.type != Material.STONE_BRICK_SLAB }) {
            Bukkit.getLogger().warning("Polar Bear platform was changed; automatic placement skipped.")
            return
        }
        for (x in -21..-19) for (z in -13..-11) world.getBlockAt(x, 60, z).let {
            if (it.type.name.endsWith("_WOOL")) it.setType(Material.MAGENTA_WOOL, false)
        }
        val bear = LobbyBuildService.place("polar_bear", at)
        world.persistentDataContainer.set(installed, PersistentDataType.BYTE, 1)
        world.save()
        Bukkit.getLogger().info("Polar Bear selector installed beside Phantom at ${bear.x}, ${bear.y}, ${bear.z} (magenta platform)")
    }
}
