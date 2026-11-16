package dev.betrix.superSmashMobsBrawl.passives

import gg.flyte.twilight.scheduler.repeatingTask
import org.bukkit.Sound
import org.bukkit.entity.Player

class GoatedLeapPassive(player:Player) : DoubleJumpPassive("goated_leap",player) {
    private var charge=0f
    fun refill() { charge=1f }
    private var airborne = false
    override fun setup() {
        super.setup()
        runnables.add(repeatingTask(1) {
            if (!player.isOnGround) airborne = true
            else if (airborne) {
                airborne = false
                player.world.playSound(player.location, Sound.ENTITY_GOAT_STEP, 1f, 0.8f)
            }
            if(player.isOnGround && player.isSneaking) charge=(charge+1f/60f).coerceAtMost(1f)
            if(!dev.betrix.superSmashMobsBrawl.smash.SmashService.ownsXp(player))player.exp=charge
        })
    }
    override fun launchVelocity(direction: org.bukkit.util.Vector): org.bukkit.util.Vector =
        direction.clone().setY(0.0).multiply(0.9).setY(0.9+charge*1.1)
    override fun activate() {
        player.velocity=launchVelocity(player.location.direction)
        charge=0f; player.exp=0f
    }
}
