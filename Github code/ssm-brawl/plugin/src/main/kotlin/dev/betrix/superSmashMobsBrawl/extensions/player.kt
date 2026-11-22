package dev.betrix.superSmashMobsBrawl.extensions

import dev.betrix.superSmashMobsBrawl.services.DebugService
import dev.betrix.superSmashMobsBrawl.services.KitService
import dev.betrix.superSmashMobsBrawl.services.LangService
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import org.koin.core.context.GlobalContext

/**
 * Check if debug mode is enabled for this player.
 *
 * Usage:
 * ```kotlin
 * if (player.hasDebugEnabled()) {
 *     player.sendMessage("Some debug information")
 * }
 * ```
 */
fun Player.hasDebugEnabled(): Boolean {
    return DebugService.isDebugEnabled(this)
}

fun Player.sendDebugMessage(message: String) {
    if (!hasDebugEnabled()) {
        return
    }

    val lang: LangService = GlobalContext.get().get()

    sendMessage(lang.t("messages.debug") { "message" to message })
}

fun Player.hasPassive(passiveId: String): Boolean {
    val kitService: KitService = GlobalContext.get().get()

    val brawlKit = kitService.getKitForPlayer(this) ?: return false

    return brawlKit.getPassive(passiveId) != null
}

/**
 * Gets a location in front of the player's eyes.
 *
 * @param distance How far to look ahead (max distance if raycasting)
 */
fun Player.getLocationInFrontOfEyes(distance: Double): Location = locationAlong(eyeLocation.direction, distance)

/**
 * The same for any direction looked in from where the eyes are now: what
 * [getLocationInFrontOfEyes] gives once the player has turned to look along [look]. (A bot works
 * out where a Blink would take it before it turns its head.)
 */
fun Player.locationAlong(look: Vector, distance: Double): Location {
    require(distance > 0) { "distance must be > 0, was $distance" }

    val world = this.world
    val eyeLocation = this.eyeLocation.clone()
    val direction = look.clone().normalize()

    val result = world.rayTraceBlocks(eyeLocation, direction, distance)

    return if (result != null && result.hitBlock != null) {
        val hitPos = result.hitPosition
        val safeLocation = hitPos.toLocation(world).subtract(direction.multiply(0.5))
        safeLocation.y += 0.1
        safeLocation.setDirection(direction)
    } else {
        val target = eyeLocation.add(direction.multiply(distance))
        target.setDirection(direction)
    }
}

fun Player.playSound(sound: Sound, pitch: Float = 1f, volume: Float = 1f) {
    playSound(eyeLocation, sound, volume, pitch)
}

fun Player.isCreativeMode(): Boolean {
    return gameMode == GameMode.CREATIVE
}

fun Player.playSuccessSound() {
    playSound(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, pitch = 1f, volume = 1f)
}

fun Player.playErrorSound() {
    playSound(Sound.ENTITY_VILLAGER_NO, pitch = 1f, volume = 1f)
}
