package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.events.BrawlDamageEvent
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageType
import dev.betrix.superSmashMobsBrawl.events.BrawlDeathEvent
import dev.betrix.superSmashMobsBrawl.events.Damager
import dev.betrix.superSmashMobsBrawl.extensions.event
import dev.betrix.superSmashMobsBrawl.extensions.setVelocity
import gg.flyte.twilight.event.event
import gg.flyte.twilight.scheduler.TwilightRunnable
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Bat
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.util.Vector

class BatWaveAbility(player: Player) : BrawlAbility("bat_wave", player) {

    private val batCount = metadata.int("batCount") ?: 32
    private val batDurationMs = metadata.long("batDurationMs") ?: 2500
    private val batSpeed get() = upgradedValue("batSpeed", 0.5)
    private val leashedBatSpeedMultiplier = metadata.double("leashedBatSpeedMultiplier") ?: 1.8
    private val batDamage = metadata.double("batDamage") ?: 3.0
    private val hitboxRadius get() = upgradedValue("hitboxRadius", 2.0)
    private val knockbackMultiplier get() = upgradedValue("knockbackMultiplier", 1.75)
    private val disableDamageThreshold = metadata.double("disableDamageThreshold") ?: 20.0
    private val batHitCooldownMs = metadata.long("batHitCooldownMs") ?: 200
    private val leashCooldownMs = metadata.long("leashCooldownMs") ?: 500
    private val pullStrength get() = upgradedValue("pullStrength", 0.35)

    private var batTask: TwilightRunnable? = null
    private var startLocation: Location? = null
    private var startTime: Long = 0
    private var lastLeashTime: Long = 0
    private var damageTaken: Double = 0.0
    private var lastBatHitTime: Long = 0
    private val bats = mutableListOf<Bat>()
    private var smashWave=false
    private var smashLeashed=false

    override fun setup() {
        super.setup()
        listeners.add(event<org.bukkit.event.entity.EntityUnleashEvent> {
            if (entity in bats) setDropLeash(false)
        })
        listeners.add(event<org.bukkit.event.entity.EntityDropItemEvent> {
            if (entity in bats) isCancelled = true
        })

        listeners.add(
            event<BrawlDamageEvent> {
                if (victim !is Player || victim != player) {
                    return@event
                }

                damageTaken += damage

                if (damageTaken >= disableDamageThreshold) {
                    clearBats()
                }
            }
        )

        listeners.add(
            BrawlDeathEvent.listen {
                if (player != this@BatWaveAbility.player) {
                    return@listen
                }

                clearBats()
            }
        )
    }

    val waveActive get()=batTask!=null
    fun launchSmashWave(source:String) = launchWave(3,1.6,1.2,source,8.0)
    fun stopWave()=clearBats()
    fun toggleLeash() {
        if(System.currentTimeMillis()-lastLeashTime<leashCooldownMs)return
        lastLeashTime=System.currentTimeMillis()
        // One virtual tether avoids 96 native leash attach/break sounds and competing physics.
        if(smashWave) {smashLeashed=!smashLeashed;return}
        val first=bats.firstOrNull {it.isValid} ?: return
        if(first.isLeashed)unleashBats() else leashBats()
    }
    override fun onPlayerInteract(event: PlayerInteractEvent) {
        if(event.hand!=null && event.hand!=org.bukkit.inventory.EquipmentSlot.HAND)return
        if(!event.action.isRightClick || !isCorrectItemForAbility(event.item ?: player.inventory.itemInMainHand))return
        if (batTask != null) {
            consumeClick(event);toggleLeash();return
        }

        super.onPlayerInteract(event)
    }

    override fun activate() {
        super.activate()
        launchWave()
    }

    private fun launchWave(countMultiplier:Int=1,speedMultiplier:Double=1.0,damageMultiplier:Double=1.0,source:String=displayName,width:Double=0.0) {
        clearBats()
        smashWave=width>0;smashLeashed=false
        startLocation = player.eyeLocation.clone()
        startTime = System.currentTimeMillis()
        damageTaken = 0.0

        for (i in 0 until batCount*countMultiplier) {
            val side=Vector(-player.eyeLocation.direction.z,0.0,player.eyeLocation.direction.x).let {if(it.lengthSquared()<.001)Vector(1,0,0) else it.normalize()}
            val at=player.eyeLocation.clone()
            if(width>0)at.add(side.multiply(((i%12)/11.0-.5)*width)).add(0.0,(i/12%8)*.3-.8,0.0)
            val bat = player.world.spawn(at, Bat::class.java)
            bat.isAwake=true;bat.isSilent=true;bat.isPersistent=false;bat.setGravity(false)
            org.bukkit.Bukkit.getMobGoals().removeAllGoals(bat)
            bats.add(bat)
        }

        player.world.playSound(player.location,Sound.ENTITY_BAT_TAKEOFF,1f,1f)
        var ticks=0
        batTask =
            repeatingTask(0) {
                if (System.currentTimeMillis() - startTime >= batDurationMs) {
                    clearBats()
                    cancel()
                    return@repeatingTask
                }

                val batVector = Vector(0.0, 0.0, 0.0)
                var batCount = 0.0
                if(smashWave && ticks++%10==0)bats.firstOrNull {it.isValid}?.let {
                    it.world.playSound(it.location,Sound.ENTITY_BAT_AMBIENT,.65f,1.2f)
                }

                // A copy: a bat's hit can be the one that ends the match, which clears the list
                // while it is being gone through here.
                for (bat in bats.toList()) {
                    if (!bat.isValid) {
                        continue
                    }

                    batVector.add(bat.location.toVector())
                    batCount++

                    val random =
                        Vector(
                            (Math.random() - 0.5) / 2,
                            (Math.random() - 0.5) / 2,
                            (Math.random() - 0.5) / 2,
                        )

                    val currentBatSpeed =
                        if (bat.isLeashed || smashLeashed) batSpeed * leashedBatSpeedMultiplier else batSpeed
                    val batVelocity =
                        startLocation!!.direction.clone().multiply(currentBatSpeed*speedMultiplier).add(random)
                    bat.velocity = batVelocity

                    for (otherPlayer in bat.world.players) {
                        if (System.currentTimeMillis() - lastBatHitTime < batHitCooldownMs) {
                            break
                        }

                        if (otherPlayer == player) {
                            continue
                        }

                        val minigame = minigameService.getMinigameForPlayer(player)
                        if (
                            minigame != null && minigame.arePlayersOnSameTeam(player, otherPlayer)
                        ) {
                            continue
                        }

                        if (otherPlayer.location.distance(bat.location) > hitboxRadius) {
                            continue
                        }

                        BrawlDamageEvent(
                                otherPlayer,
                                Damager.DamagerLivingEntity(player),
                                batDamage*damageMultiplier,
                                knockbackMultiplier = knockbackMultiplier * if(smashWave)1.2 else 1.0,
                                damageType = BrawlDamageType.Projectile, sourceName = source, automaticKnockback = true)
                            .callEvent()

                        bat.world.playSound(bat.location, Sound.ENTITY_BAT_HURT, 1f, 1f)

                        Particle.LARGE_SMOKE.builder()
                            .location(bat.location)
                            .offset(0.0, 0.0, 0.0)
                            .count(3)
                            .extra(0.0)
                            .receivers(96, true)
                            .spawn()

                        bat.remove()
                        lastBatHitTime = System.currentTimeMillis()
                    }
                }

                if (batCount>0 && (smashLeashed || bats.any {it.isValid && it.isLeashed})) {
                    batVector.multiply(1.0 / batCount)
                    val batLocation = batVector.toLocation(player.world)
                    val difference =
                        batLocation.toVector().subtract(player.location.toVector()).normalize()
                    player.setVelocity(difference, pullStrength, false, 0.0, 0.0, 10.0, false)
                }
            }

        batTask?.let { runnables.add(it) }
    }

    override fun teardown() {
        clearBats()
        super.teardown()
    }

    private fun leashBats() {
        for (bat in bats) {
            if(bat.isValid)bat.setLeashHolder(player)
        }
    }

    private fun unleashBats() {
        for (bat in bats) {
            if(bat.isValid)bat.setLeashHolder(null)
        }
    }

    private fun clearBats() {
        batTask?.cancel()
        batTask = null

        unleashBats()

        for (bat in bats) {
            if (bat.isValid) {
                Particle.LARGE_SMOKE.builder()
                    .location(bat.location)
                    .offset(0.0, 0.0, 0.0)
                    .count(3)
                    .extra(0.0)
                    .receivers(96, true)
                    .spawn()
            }
            bat.remove()
        }

        bats.clear()
        smashWave=false;smashLeashed=false
    }
}
