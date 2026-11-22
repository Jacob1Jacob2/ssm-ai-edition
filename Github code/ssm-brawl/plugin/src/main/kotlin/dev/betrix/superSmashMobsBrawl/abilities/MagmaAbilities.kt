package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.extensions.disguise
import gg.flyte.twilight.event.event
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.util.Vector

class MagmaBlastAbility(player: Player) : ClassicKitAbility("magma_blast", player) {
    private data class Ball(val entity: org.bukkit.entity.ItemDisplay, var position: Location, var velocity: Vector,
                            var attacker: Player, var reflectedAt: Long = 0)
    private val balls = mutableListOf<Ball>()
    override fun setup() {
        super.setup()
        listeners.add(event<PlayerInteractEvent> {
            if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) return@event
            val hitter = this.player
            balls.toList().forEach { ball ->
                if (hitter.world != ball.position.world || minigameService.getMinigameForPlayer(hitter) !== game ||
                    hitter.gameMode != GameMode.SURVIVAL || System.currentTimeMillis() - ball.reflectedAt < 250) return@forEach
                val delta = ball.position.toVector().subtract(hitter.eyeLocation.toVector())
                if (delta.length() < 3.5 && delta.clone().normalize().dot(hitter.eyeLocation.direction) > 0.75 &&
                    hitter.world.rayTraceBlocks(hitter.eyeLocation, delta.clone().normalize(), delta.length()) == null) {
                    ball.attacker = hitter
                    ball.velocity = hitter.eyeLocation.direction.multiply(upgradedValue("speed", 0.9))
                    ball.reflectedAt = System.currentTimeMillis()
                    hitter.world.playSound(ball.position, Sound.ENTITY_GHAST_SHOOT, 0.7f, 1.2f)
                }
            }
        })
    }

    override fun activate() {
        super.activate()
        val position = player.eyeLocation.add(player.eyeLocation.direction)
        val ball = Ball(item(position, Material.FIRE_CHARGE).apply { transformation = org.bukkit.util.Transformation(org.joml.Vector3f(),org.joml.Quaternionf(),org.joml.Vector3f((5.4 * itemBoost).toFloat()),org.joml.Quaternionf()) }, position, position.direction.multiply(upgradedValue("speed", 0.9)), player)
        balls.add(ball)
        player.velocity = player.eyeLocation.direction.multiply(-(upgradedValue("recoil", 1.0)))
        player.world.playSound(player.location, Sound.ENTITY_CREEPER_DEATH, 2f, 1.5f)
        loop(finish = { remove(ball.entity); balls.remove(ball) }) { age ->
            if (age >= 100 || !ball.entity.isValid) false
            else {
                val collision = collision(ball.position, ball.velocity, 0.45, ball.attacker)
                if (collision != null) {
                    val at = collision.first
                    at.world.spawnParticle(Particle.EXPLOSION, at, 1)
                    breakNearbyTerrain(at)
                    at.world.spawnParticle(Particle.LAVA, at, 50, 0.1, 0.1, 0.1, 0.1)
                    at.world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 4f, 1f)
                    targets(at, upgradedValue("radius", 4.0), attacker = ball.attacker).forEach { target ->
                        if (hit(target, metadata.double("damage") ?: 8.0, 2.0, at, ball.attacker)) {
                            if (target == collision.second) burn(target, 4, ball.attacker)
                        }
                    }
                    false
                } else {
                    ball.position = ball.position.add(ball.velocity)
                    ball.entity.teleport(ball.position)
                    ball.entity.velocity = Vector()
                    ball.position.world.spawnParticle(Particle.FLAME, ball.position, 4, 0.15, 0.15, 0.15, 0.01)
                    true
                }
            }
        }
    }
    override fun teardown() { super.teardown(); balls.clear() }
}

class FlameDashAbility(player: Player) : ClassicKitAbility("flame_dash", player) {
    private var dashing = false
    private var endRequested = false
    /** Whether the dash is under way at this moment. Only read (by bots, to know when to end it). */
    val dashingNow get() = dashing
    override fun canActivate(sendMessage: Boolean) = dashing || super.canActivate(sendMessage)
    override fun activate() {
        if (dashing) { endRequested = true; return }
        super.activate()
        dashing = true
        endRequested = false
        val life = ownerKit
        val start = player.location
        val direction = start.direction.setY(0.0)
        if (direction.lengthSquared() < 0.01) direction.z = 1.0
        direction.normalize().multiply(upgradedValue("speed", 1.1))
        life?.setInvincible(true)
        player.disguise?.setInvisible(true)
        loop(finish = {
            dashing = false
            life?.setInvincible(false)
            player.disguise?.setInvisible(false)
        }) { age ->
            val blocked = player.world.rayTraceBlocks(player.location.add(0.0, 0.4, 0.0), direction.clone().normalize(), direction.length()) != null
            if (endRequested || age >= (metadata.int("durationTicks") ?: 25) || blocked) {
                val travelled = if (start.world == player.world) start.distance(player.location) else 0.0
                val damage = ((metadata.double("baseDamage") ?: 4.0) + travelled * 0.25).coerceAtMost(10.0)
                player.world.spawnParticle(Particle.EXPLOSION, player.location, 1)
                player.world.spawnParticle(Particle.FLAME, player.location, 100, 0.1, 0.1, 0.1, 0.3)
                player.world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f)
                targets(player.location, upgradedValue("radius", 4.0)).forEach { hit(it, damage, 2.0) }
                false
            } else {
                player.velocity = direction
                player.fallDistance = 0f
                player.world.spawnParticle(Particle.FLAME, player.location.add(0.0, 0.4, 0.0), 3, 0.2, 0.2, 0.2, 0.0)
                player.world.playSound(player.location, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.2f)
                true
            }
        }
    }
    override fun teardown() {
        player.disguise?.setInvisible(false)
        dashing = false
        super.teardown()
    }
}
