package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.disguises.SheepDisguise
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageType
import dev.betrix.superSmashMobsBrawl.events.Damager
import dev.betrix.superSmashMobsBrawl.extensions.disguise
import dev.betrix.superSmashMobsBrawl.extensions.setVelocity
import dev.betrix.superSmashMobsBrawl.passives.DoubleJumpPassive
import dev.betrix.superSmashMobsBrawl.utils.isOnGround
import gg.flyte.twilight.extension.getNearbyEntities
import gg.flyte.twilight.scheduler.TwilightRunnable
import org.bukkit.DyeColor
import org.bukkit.Effect
import org.bukkit.Particle
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector

class WoolyRocketAbility(player: Player) : BrawlAbility("wooly_rocket", player) {

    private val damage = metadata.double("damage") ?: 8.0
    private val damageRadius get() = upgradedValue("damageRadius", 2.0)
    private val knockbackMultiplier get() = upgradedValue("knockbackMultiplier", 2.5)
    private val minimumVelocityTimeMs =
        metadata.long("minimumVelocityTimeMs") ?: 200L
    private val maximumVelocityTimeMs =
        metadata.long("maximumVelocityTimeMs") ?: 1200L

    private var startedAt=0L
    private var rocketTask: TwilightRunnable? = null

    override fun activate() {
        super.activate()
        launchRocket()
    }
    fun launchSmash() { setCooldown(0); launchRocket(1.5) }
    private fun launchRocket(boost:Double=1.0) {
        startedAt=System.currentTimeMillis()
        player.setVelocity(Vector(0.0, 1.0, 0.0), itemBoost * boost, false, 0.0, 0.0, 2.0 * itemBoost * boost, true)

        player.world.playEffect(player.location, Effect.BLAZE_SHOOT, 0)

        val kit = kitService.getKitForPlayer(player)
        if (kit != null) {
            val doubleJump = kit.getPassive("double_jump") as? DoubleJumpPassive
            if (doubleJump != null) {
                (kitService.getKitForPlayer(player)?.getPassive("double_jump") as? dev.betrix.superSmashMobsBrawl.passives.DoubleJumpPassive)?.rechargeNow()
            }
        }

        setSheepColor(DyeColor.RED)

        startRocketTask()
    }

    private fun startRocketTask() {
        rocketTask?.cancel()

        val struck = mutableSetOf<java.util.UUID>()
        rocketTask =
            repeatingTask(0, 0) {
                if (!player.isValid || !player.isOnline) {
                    stopRocket()
                    cancel()
                    return@repeatingTask
                }

                Particle.FLAME.builder()
                    .location(player.location)
                    .count(4)
                    .offset(0.2, 0.2, 0.2)
                    .extra(0.0)
                    .receivers(96, true)
                    .spawn()

                val timeElapsed = System.currentTimeMillis()-startedAt

                if (timeElapsed < minimumVelocityTimeMs) {
                    return@repeatingTask
                }

                player.location
                    .getNearbyEntities(damageRadius, damageRadius, damageRadius)
                    .filterIsInstance<Player>()
                    .filter { it != player }
                    .filter { canDamageEntity(it) && player.velocity.y > 0.0 && it.y >= player.y + 0.3 && it.uniqueId !in struck }
                    .forEach { entity ->
                        val hit = BrawlDamageEvent(
                                entity,
                                Damager.DamagerLivingEntity(player),
                                damage,
                                knockbackMultiplier,
                                BrawlDamageType.Projectile, sourceName = displayName)
                        hit.callEvent()
                        if (hit.appliedDamage <= 0) return@forEach
                        struck.add(entity.uniqueId)

                        Particle.POOF.builder()
                            .location(entity.location)
                            .count(1)
                            .receivers(96, true)
                            .spawn()

                        Particle.LAVA.builder()
                            .location(entity.location)
                            .count(10)
                            .offset(0.2, 0.2, 0.2)
                            .extra(0.0)
                            .receivers(96, true)
                            .spawn()
                    }

                if (isOnGround(player) || timeElapsed >= maximumVelocityTimeMs) {
                    stopRocket()
                    cancel()
                    return@repeatingTask
                }
            }

        rocketTask?.let { runnables.add(it) }
    }

    private fun stopRocket() {
        rocketTask?.cancel()
        rocketTask = null
        setSheepColor(DyeColor.WHITE)
    }

    private fun canDamageEntity(entity: LivingEntity): Boolean {
        if (entity !is Player) {
            return true
        }

        val minigame = minigameService.getMinigameForPlayer(player) ?: return false

        return minigameService.getMinigameForPlayer(entity) === minigame &&
            entity.gameMode == org.bukkit.GameMode.SURVIVAL && !minigame.respawnManager.isRespawning(entity) &&
            !minigame.arePlayersOnSameTeam(player, entity)
    }

    private fun setSheepColor(color: DyeColor) {
        (player.disguise as? SheepDisguise)?.setColor(color)
    }

    override fun teardown() {
        stopRocket()
        super.teardown()
    }
}


