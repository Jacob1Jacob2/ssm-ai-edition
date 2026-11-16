package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.extensions.playSound
import dev.betrix.superSmashMobsBrawl.projectiles.WebProjectile
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.util.Vector

class SpinWebAbility(player: Player) : BrawlAbility("spin_web", player) {

    private val webCount get() = upgradedCount(metadata.int("webCount") ?: 25)
    private val webDamage = metadata.double("webDamage") ?: 4.0
    private val webKnockbackMultiplier = metadata.double("webKnockbackMultiplier") ?: 0.0
    private val webHitboxSize = metadata.double("webHitboxSize") ?: 0.5
    private val webBlockDurationTicks = metadata.long("webBlockDurationTicks") ?: 40L
    private val launchVelocityStrength get() = upgradedValue("launchVelocityStrength", 1.2)
    private val launchVelocityY get() = upgradedValue("launchVelocityY", 0.2)
    private val poisonDurationTicks = metadata.int("poisonDurationTicks") ?: 40
    private val poisonAmplifier = metadata.int("poisonAmplifier") ?: 0

    private val activeProjectiles = mutableListOf<WebProjectile>()

    override fun activate() {
        super.activate()
        launchWebs()
        launchPlayer()
    }

    override fun teardown() {
        activeProjectiles.toList().also { activeProjectiles.clear() }.forEach { it.teardown() }
        super.teardown()
    }

    private fun launchWebs() {
        player.world.playSound(player.location, Sound.ENTITY_SPIDER_AMBIENT, 2f, 0.6f)

        repeat(webCount) {
            val web = WebProjectile(
                player,
                displayName,
                webDamage,
                webKnockbackMultiplier,
                webBlockDurationTicks,
                poisonDurationTicks,
                poisonAmplifier,
                groundDirected = player.location.pitch > 32f,
            )
                .setupHitCallbacks()
                .projectileSize(webHitboxSize)
                .onTeardown { activeProjectiles.remove(it) }
                .upgraded().launch()

            activeProjectiles.add(web as WebProjectile)
        }
    }

    private fun launchPlayer() {
        if (player.location.pitch > 32f) {
            val direction = player.location.direction.setY(0.0)
            if (direction.lengthSquared() > 0.001) direction.normalize().multiply(0.65)
            player.velocity = direction.setY(1.0).multiply(itemBoost)
            player.fallDistance = 0f
            return
        }
        // Launch player forward
        val forwardDirection = player.location.direction
        player.velocity = Vector(
            forwardDirection.x * launchVelocityStrength,
            launchVelocityY,
            forwardDirection.z * launchVelocityStrength,
        )
    }
}
