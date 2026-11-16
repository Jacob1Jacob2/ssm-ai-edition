package dev.betrix.superSmashMobsBrawl.passives

import dev.betrix.superSmashMobsBrawl.extensions.setVelocity
import gg.flyte.twilight.scheduler.repeatingTask
import kotlin.math.max
import kotlin.math.min
import org.bukkit.Sound
import org.bukkit.entity.Player

class FlapPassive(player: Player) : EnergyDoubleJumpPassive("flap", player) {

    override val power: Double
        get() = metadata.double("flapStrengthMultiplier") ?: 0.95

    override val height: Double
        get() = metadata.double("flapHeightMax") ?: 0.9

    private val verticalBoost: Double
        get() = metadata.double("flapVerticalBoost") ?: 0.15

    override val doubleJumpSound: Sound = Sound.ENTITY_BAT_TAKEOFF

    private val energyPerFlap: Float
        get() = (metadata.double("energyPerFlap") ?: 12.5).toFloat()

    private val minEnergyToFlap: Float
        get() = (metadata.double("minEnergyToFlap") ?: 12.5).toFloat()

    private val maxEnergy: Float
        get() = (metadata.double("maxEnergy") ?: 100.0).toFloat()

    private val rechargeTicks: Float
        get() = ((metadata.double("rechargeSeconds") ?: 3.0) * 20).toFloat().coerceAtLeast(1f)

    override fun setup() {
        super.setup()
        setupEnergyRegeneration()
    }

    private fun setupEnergyRegeneration() {
        runnables.add(
            repeatingTask(1) {
                if(minigameService.getMinigameForPlayer(player)?.getState()!=dev.betrix.superSmashMobsBrawl.minigames.MinigameState.IN_PROGRESS)return@repeatingTask
                // Only regenerate energy when on the ground
                if (!groundCheck()) {
                    return@repeatingTask
                }

                regenerateEnergy()
            }
        )
    }

    private fun regenerateEnergy() {
        val next = min(1f, player.exp + 1f / rechargeTicks)
        // A real full bar affords all eight flaps; 0.9999 prevented the eighth.
        player.exp = if (next >= 1f - 0.00001f) 1f else next
    }

    override fun canUseJump(): Boolean {
        val currentEnergy = player.exp * maxEnergy
        return dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"chicken:0") || dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"chicken:1") || currentEnergy + 0.0001f >= minEnergyToFlap
    }

    override fun launchVelocity(direction: org.bukkit.util.Vector): org.bukkit.util.Vector =
        dev.betrix.superSmashMobsBrawl.extensions.launchVector(direction,
            power * (if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"chicken:1")) 1.5 else 1.0), true, power, verticalBoost, height)

    override fun activate() {
        player.setVelocity(
            player.location.direction,
            power * (if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"chicken:1")) 1.5 else 1.0),
            true,
            power,
            verticalBoost,
            height,
            true,
        )

        // Deduct energy (convert from percentage to actual value)
        val currentEnergyPercent = player.exp
        val energyPerFlapPercent = energyPerFlap / maxEnergy
        val newEnergyPercent = if(dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"chicken:0") || dev.betrix.superSmashMobsBrawl.smash.SmashService.has(player,"chicken:1")) 1f else max(0f, currentEnergyPercent - energyPerFlapPercent)
        // Don't display an unusable fragment after the last affordable flap.
        player.exp = if (newEnergyPercent * maxEnergy + 0.0001f < minEnergyToFlap) 0f else newEnergyPercent
    }

    override fun playDoubleJumpSound() {
        val volume = (0.3 + player.exp).toFloat()
        val pitch = (Math.random() / 2 + 1).toFloat()
        player.world.playSound(player.location, doubleJumpSound, volume, pitch)
    }
}
