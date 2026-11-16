package dev.betrix.superSmashMobsBrawl.passives

import dev.betrix.superSmashMobsBrawl.abilities.VillagerArt
import dev.betrix.superSmashMobsBrawl.abilities.VillagerArtsAbility
import dev.betrix.superSmashMobsBrawl.disguises.MagmaCubeDisguise
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.BrawlKillEvent
import dev.betrix.superSmashMobsBrawl.events.Damager
import dev.betrix.superSmashMobsBrawl.extensions.disguise
import gg.flyte.twilight.event.event
import gg.flyte.twilight.scheduler.repeatingTask
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/** Each successful melee hit adds a stack with its own three-second lifetime. */
class RavagePassive(player: Player) : BrawlPassive("ravage", player) {
    private val expires = ArrayDeque<Long>()
    fun bonus(): Double {
        val now = System.currentTimeMillis()
        while (expires.firstOrNull()?.let { it <= now } == true) expires.removeFirst()
        return expires.size.toDouble()
    }
    override fun setup() {
        super.setup()
        listeners.add(event<BrawlDamageEvent>(priority = EventPriority.MONITOR) {
            if (appliedDamage <= 0 || (damageType != null && damageType != dev.betrix.superSmashMobsBrawl.events.BrawlDamageType.MeleeAttack) || (damager as? Damager.DamagerLivingEntity)?.livingEntity != player) return@event
            bonus()
            val empowered = dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"wolf:0")
            if (expires.size >= if(empowered) 5 else 3) expires.removeFirst()
            expires.addLast(System.currentTimeMillis() + if (empowered) 7000 else 3000)
            updateBar()
        })
        runnables.add(repeatingTask(1) {
            updateBar()
        })
    }
    private fun updateBar() {
        player.exp = (bonus() / if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"wolf:0")) 5.0 else 3.0).toFloat().coerceIn(0f,1f)
    }
}

class FuelTheFirePassive(player: Player) : BrawlPassive("fuel_the_fire", player) {
    var stacks = 0
        private set
    val armorBonus get() = stacks * (if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"magma_cube:0")) 1.0 else metadata.double("armorPerKill") ?: 0.5)
    val knockbackScale get() = 1.0 - stacks * (if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"magma_cube:0")) 0.25 else metadata.double("knockbackReductionPerKill") ?: 0.15)
    override fun setup() {
        super.setup()
        listeners.add(event<BrawlKillEvent> {
            if (killer != player) return@event
            stacks = (stacks + 1).coerceAtMost(3)
            (player.disguise as? MagmaCubeDisguise)?.setSize(stacks + 1)
            // Match the visible armor bar to the half-icon armor gained at each growth stage.
            if (stacks >= 1) player.inventory.setItem(org.bukkit.inventory.EquipmentSlot.FEET, org.bukkit.inventory.ItemStack.of(org.bukkit.Material.IRON_BOOTS))
            if (stacks >= 2) player.inventory.setItem(org.bukkit.inventory.EquipmentSlot.LEGS, org.bukkit.inventory.ItemStack.of(org.bukkit.Material.IRON_LEGGINGS))
            if (stacks >= 3) player.inventory.setItem(org.bukkit.inventory.EquipmentSlot.CHEST, org.bukkit.inventory.ItemStack.of(org.bukkit.Material.IRON_CHESTPLATE))
            if (!dev.betrix.superSmashMobsBrawl.smash.SmashService.ownsXp(player)) player.exp = stacks / 3f
            player.world.playSound(player.location, Sound.ENTITY_MAGMA_CUBE_SQUISH, 1f, 1f - stacks * 0.12f)
        })
    }
}

class WolfJumpPassive(player: Player) : DoubleJumpPassive("wolf_jump", player) {
    override fun launchVelocity(direction: org.bukkit.util.Vector): org.bukkit.util.Vector = direction.clone().multiply(1.1)
    override fun activate() {
        player.velocity = launchVelocity(player.eyeLocation.direction)
    }
}

class VillagerJumpPassive(player: Player) : DoubleJumpPassive("villager_jump", player) {
    private fun speedster(): Boolean {
        val art = (kitService.getKitForPlayer(player)?.getAbility("villager_arts") as? VillagerArtsAbility)?.activeArt
        return art == VillagerArt.SPEEDSTER || dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"villager:3")
    }
    override fun launchVelocity(direction: org.bukkit.util.Vector): org.bukkit.util.Vector =
        (if (speedster()) direction.clone().multiply(1.1) else super.launchVelocity(direction))
            .multiply(if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"villager:1")) 1.3 else 1.0)
    override fun activate() {
        if (speedster()) player.velocity = player.eyeLocation.direction.multiply(1.1)
        else super.activate()
        if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"villager:1"))player.velocity=player.velocity.multiply(1.3)
    }
}
