package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.extensions.getLocationInFrontOfEyes
import dev.betrix.superSmashMobsBrawl.extensions.interpolateTo
import dev.betrix.superSmashMobsBrawl.extensions.locationAlong
import dev.betrix.superSmashMobsBrawl.utils.ClassicEffects
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player

class BlinkAbility(player: Player) : BrawlAbility("blink", player) {

    private val teleportDistance get() = upgradedValue("teleportDistance", 16.0)

    /** Where a Blink would bring its player out if they were looking along [look]. Only read (by bots, before they press). */
    fun comesOut(look: org.bukkit.util.Vector): Location = player.locationAlong(look, teleportDistance)

    override fun activate() {
        super.activate()

        val startLocation = player.eyeLocation.clone()
        val endLocation = player.getLocationInFrontOfEyes(teleportDistance)

        doTpEffect(startLocation)

        delay(1) { doTpEffect(endLocation) }

        startLocation.interpolateTo(endLocation, 0.25).forEach { location ->
            Particle.SMOKE.builder()
                .count(1)
                .extra(0.0)
                .location(location)
                .receivers(96, true)
                .spawn()
        }

        player.teleport(endLocation)
    }

    private fun doTpEffect(location: Location) {
        ClassicEffects.fireworkBurst(location, Color.BLACK)

        location.world.playSound(location, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f)
    }
}
