package dev.betrix.superSmashMobsBrawl.abilities

import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.entity.WitherSkeleton
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector

class GuidedWitherSkullAbility(player: Player) : ClassicKitAbility("guided_wither_skull", player) {
    private var guiding = false
    override fun canActivate(sendMessage: Boolean) = !guiding && super.canActivate(sendMessage)
    override fun activate() {
        super.activate()
        guiding = true
        var position = player.eyeLocation.add(player.eyeLocation.direction)
        val skull = item(position, Material.WITHER_SKELETON_SKULL)
        var released = false
        var velocity = player.eyeLocation.direction.multiply(0.4)
        player.world.playSound(position, Sound.ENTITY_WITHER_SHOOT, 1f, 1f)
        loop(finish = { guiding = false; remove(skull) }) { age ->
            if (age > (metadata.int("lifetimeTicks") ?: 100) || !skull.isValid) false
            else {
                if (!released && age > 2 && (!player.isBlocking || !isCorrectItemForAbility(player.inventory.itemInMainHand))) {
                    released = true
                    guiding = false
                    velocity = velocity.normalize().multiply(upgradedValue("releaseSpeed", 1.6))
                }
                if (!released) {
                    val aim = player.eyeLocation.add(player.eyeLocation.direction.multiply(24.0 * itemBoost))
                    velocity = aim.toVector().subtract(position.toVector()).normalize().multiply(upgradedValue("guideSpeed", 0.45))
                }
                val impact = collision(position, velocity, 0.4)
                if (impact != null) {
                    val at = impact.first
                    at.world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1)
                    breakNearbyTerrain(at)
                    at.world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 2.5f, 0.4f)
                    targets(at, upgradedValue("radius", 6.0), self = true).forEach {
                        hit(it, metadata.double("damage") ?: 10.0, 1.5, at, self = true)
                    }
                    false
                } else {
                    position = position.add(velocity)
                    skull.teleport(position)
                    skull.velocity = Vector()
                    position.world.spawnParticle(Particle.SMOKE, position, 3, 0.1, 0.1, 0.1, 0.01)
                    true
                }
            }
        }
    }
}

class WitherImageAbility(player: Player) : ClassicKitAbility("wither_image", player) {
    private var image: WitherSkeleton? = null
    private var swappedAt = 0L
    override fun canActivate(sendMessage: Boolean): Boolean =
        if (image?.isValid == true) System.currentTimeMillis() - swappedAt >= 2000 else super.canActivate(sendMessage)

    override fun rechargeProgress(): Double = if (image?.isValid == true)
        ((System.currentTimeMillis() - swappedAt) / 2000.0).coerceIn(0.0, 1.0) else super.rechargeProgress()
    override fun rechargeSecondsLeft(): Double = if (image?.isValid == true)
        ((2000 - (System.currentTimeMillis() - swappedAt)) / 1000.0).coerceAtLeast(0.0) else super.rechargeSecondsLeft()

    override fun activate() {
        image?.takeIf { it.isValid }?.let { clone ->
            val oldPosition = player.location
            val destination = clone.location
            if (destination.world != player.world || !destination.block.isPassable || !destination.clone().add(0.0, 1.0, 0.0).block.isPassable) return
            player.teleport(destination)
            clone.teleport(oldPosition)
            player.fallDistance = 0f
            swappedAt = System.currentTimeMillis()
            player.world.playSound(destination, Sound.ENTITY_WITHER_SPAWN, 1f, 2f)
            player.sendMessage(lang.t("messages.abilities.use.success") { "abilityId" to "wither_swap" })
            return
        }
        super.activate()
        player.world.playSound(player.location, Sound.ENTITY_WITHER_SPAWN, 1f, 1f)
        val clone = player.world.spawn(player.location.add(0.0, 0.2, 0.0), WitherSkeleton::class.java)
        summon(clone, metadata.double("health") ?: 16.0)
        clone.equipment.setItemInMainHand(ItemStack.of(Material.IRON_AXE))
        clone.equipment.itemInMainHandDropChance = 0f
        clone.velocity = player.eyeLocation.direction.multiply(2.4 * itemBoost)
        image = clone
        swappedAt = System.currentTimeMillis()
        var lastAttack = -20
        loop(finish = {
            if(clone.world==player.world) {
                clone.world.spawnParticle(Particle.ITEM,clone.location.add(0.0,0.5,0.0),12,0.3,0.3,0.3,0.1,ItemStack.of(Material.BONE))
                clone.world.playSound(clone.location,Sound.ENTITY_WITHER_HURT,1f,0.75f)
            }
            remove(clone); if (image === clone) image = null
        }) { age ->
            if (!clone.isValid || age >= (metadata.int("durationTicks") ?: 170)) false
            else {
                val target = targets(clone.location, 20.0).filterIsInstance<Player>().minByOrNull { it.location.distanceSquared(clone.location) }
                if (target != null && age >= 8) {
                    val delta = target.location.toVector().subtract(clone.location.toVector())
                    val flat = delta.clone().setY(0.0)
                    if (flat.lengthSquared() > 1.0) {
                        flat.normalize().multiply(0.23 * itemBoost)
                        val obstacle = clone.location.clone().add(flat.clone().normalize()).block
                        flat.y = if (clone.isOnGround && (!obstacle.isPassable || delta.y > 0.8)) 0.42 else clone.velocity.y
                        clone.velocity = flat
                    }
                    clone.lookAt(target)
                    if (delta.length() < 2.5 && age - lastAttack >= 20 && clone.hasLineOfSight(target)) {
                        clone.swingMainHand()
                        hit(target, metadata.double("damage") ?: 5.0, 1.0, clone.location)
                        lastAttack = age
                    }
                }
                true
            }
        }
    }
    override fun teardown() { image = null; super.teardown() }
}
