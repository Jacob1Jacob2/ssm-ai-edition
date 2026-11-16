package dev.betrix.superSmashMobsBrawl.utils

import gg.flyte.twilight.scheduler.delay
import org.bukkit.Color
import org.bukkit.EntityEffect
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Firework
import org.bukkit.util.Vector

/** Cosmetic legacy firework bursts. Never detonate: modern fireworks also deal damage. */
object ClassicEffects {
    /** The same small gray puff used by Ink Shotgun, trailing the missile's body. */
    fun chickenMissileSmoke(at: Location, direction: Vector) {
        val behind=direction.clone()
        if(behind.lengthSquared()>0.0)behind.normalize().multiply(0.2)
        Particle.POOF.builder().count(1).extra(0.0)
            .location(at.clone().subtract(behind).add(0.0,0.15,0.0))
            .receivers(96,true).spawn()
    }

    fun fireworkBurst(at: Location, color: Color, crackleDelayTicks: Long = 1) {
        val firework = at.world.spawn(at, Firework::class.java) { rocket ->
            rocket.isPersistent = false
            rocket.isSilent = true
            rocket.setGravity(false)
            rocket.velocity = Vector()
            val meta = rocket.fireworkMeta
            meta.clearEffects()
            meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL).withColor(color).flicker(true).build())
            meta.power = 0
            rocket.fireworkMeta = meta
            rocket.ticksToDetonate = 600
        }
        delay(crackleDelayTicks) { at.world.playSound(at, org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 1f) }
        // Let clients receive the spawn before sending the visual-only entity status.
        delay(1) {
            if (firework.isValid) {
                firework.playEffect(EntityEffect.FIREWORK_EXPLODE)
                firework.remove()
            }
        }
    }
}
