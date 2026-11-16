package dev.betrix.superSmashMobsBrawl.utils

import dev.betrix.superSmashMobsBrawl.smash.SmashArena
import dev.betrix.superSmashMobsBrawl.smash.SmashEffect
import org.bukkit.*
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.util.Vector
import kotlin.random.Random

/** Cosmetic fragments never place blocks or drop items. The terrain journal owns restoration. */
object TerrainDebris {
    fun toss(arena: SmashArena, at: Location, data: BlockData, velocity: Vector = Vector(
        Random.nextDouble(-.35,.35), Random.nextDouble(.3,.8), Random.nextDouble(-.35,.35))) {
        if (arena.entities.count { it is BlockDisplay } >= 220) return
        val display = at.world.spawn(at, BlockDisplay::class.java) {
            it.block = data; it.teleportDuration = 1; it.interpolationDuration = 1
        }
        arena.track(display)
        var pos = at.clone(); var motion = velocity.clone(); var age = 0; var landed = -1
        arena.effect(object : SmashEffect {
            override fun step(): Boolean {
                if (!display.isValid || ++age > 200 || pos.y < at.world.minHeight) return false
                if (landed >= 0) return age - landed < 30
                motion.y -= .045
                val hit = at.world.rayTraceBlocks(pos.clone().add(.5,.5,.5), motion.clone().normalize(), motion.length(), FluidCollisionMode.NEVER, true)
                if (hit != null && age > 2) { pos = hit.hitPosition.toLocation(at.world).subtract(.5,0.0,.5); landed = age }
                else pos.add(motion)
                display.teleport(pos)
                return true
            }
            override fun close() { display.remove() }
        })
    }
}
