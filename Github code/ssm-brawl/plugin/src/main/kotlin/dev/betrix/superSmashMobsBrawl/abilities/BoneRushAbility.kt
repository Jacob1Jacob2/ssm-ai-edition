package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageType
import dev.betrix.superSmashMobsBrawl.events.Damager
import dev.betrix.superSmashMobsBrawl.projectiles.BrawlProjectile
import dev.betrix.superSmashMobsBrawl.projectiles.ProjectileAction
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import kotlin.random.Random

/** Classic forward bone stream. Sneaking stops the rider, not the bones. */
class BoneRushAbility(player: Player) : BrawlAbility("bone_rush", player) {
    private val bones = mutableSetOf<BrawlProjectile>()

    override fun activate() {
        super.activate()
        val game = minigameService.getMinigameForPlayer(player) ?: return
        var age = 0
        runnables.add(repeatingTask(1) {
            if (!player.isOnline || player.isDead || player.gameMode != org.bukkit.GameMode.SURVIVAL ||
                minigameService.getMinigameForPlayer(player) !== game ||
                age++ >= (metadata.int("durationTicks") ?: 30)) {
                cancel()
                return@repeatingTask
            }
            val aim = player.eyeLocation.direction
            player.velocity = if (player.isSneaking) Vector() else aim.clone().multiply(upgradedValue("speed", 0.6))
            bones.removeIf {it.projectileEntity?.isValid!=true}
            bones.addAll(BoneRushVolley.emit(player,game,(metadata.double("damagePerBone")?:.4)*1.5,
                displayName,itemBoost,metadata.int("bonesPerTick")?:4))
        })
    }

    override fun teardown() {
        super.teardown()
        bones.toList().forEach { it.teardown() }
        bones.clear()
    }
}
