package dev.betrix.superSmashMobsBrawl.services

import java.util.UUID
import kotlin.math.floor
import kotlin.math.round
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.craftbukkit.entity.CraftLivingEntity
import org.bukkit.entity.*
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask

/** Lobby statues keep a fixed pose; NoAI alone does not stop client idle animations. */
object LobbyNpcPresentation {
    private val statues = mutableMapOf<UUID, Float>()
    private val facingKey = NamespacedKey("ssm", "statue_facing")
    private var task: BukkitTask? = null
    private val squidSpeed = net.minecraft.world.entity.animal.squid.Squid::class.java
        .getDeclaredField("tentacleSpeed").apply { isAccessible = true }

    fun initialize(plugin: JavaPlugin) {
        task?.cancel()
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            statues.keys.toList().forEach { id ->
                val mob = Bukkit.getEntity(id) as? LivingEntity
                if (mob == null || !mob.isValid) statues.remove(id) else holdPose(mob)
            }
        }, 1L, 1L)
    }

    fun teardown() { task?.cancel(); task = null; statues.clear() }

    fun face(mob: LivingEntity, yaw: Float) {
        mob.persistentDataContainer.set(facingKey, PersistentDataType.FLOAT, yaw)
        statues[mob.uniqueId] = yaw
        val handle = (mob as CraftLivingEntity).handle
        // Goats clamp head rotation to 15 degrees from their body. Set the body first,
        // or Bukkit's rotation/teleport call turns the head only and loses the target yaw.
        handle.yBodyRot = yaw
        handle.yBodyRotO = yaw
        mob.setRotation(yaw, 0f)
        handle.yHeadRot = yaw
        handle.yHeadRotO = yaw
    }

    fun configure(mob: LivingEntity) {
        val at = mob.location.apply {
            x = floor(x) + .5
            z = floor(z) + .5
            yaw = mob.persistentDataContainer.get(facingKey, PersistentDataType.FLOAT) ?: yaw
            yaw = ((round(yaw / 45f) * 45f) % 360f + 360f) % 360f
            pitch = 0f
        }
        val grounded = LobbyBuildService.surfaceUnder(at) ?: at
        if (mob.location != grounded) mob.teleport(grounded)
        face(mob, grounded.yaw)
        mob.isPersistent = true
        mob.removeWhenFarAway = false
        mob.setAI(false)
        mob.setGravity(false)
        mob.isInvulnerable = true
        mob.isCollidable = false
        mob.isSilent = true
        mob.canPickupItems = false
        if (mob is Ageable) { mob.setAdult(); mob.ageLock = true }
        when (mob) {
            is Chicken -> { mob.variant = Chicken.Variant.TEMPERATE; mob.eggLayTime = Int.MAX_VALUE }
            is Pig -> mob.variant = Pig.Variant.TEMPERATE
            is Snowman -> mob.isDerp = false
            is Villager -> {
                mob.villagerType = Villager.Type.PLAINS
                mob.profession = Villager.Profession.NONE
                mob.villagerLevel = 1
                mob.villagerExperience = 0
                mob.recipes = emptyList()
            }
        }
        holdPose(mob)
    }

    private fun holdPose(mob: LivingEntity) {
        val handle = (mob as CraftLivingEntity).handle
        // Keep the chosen facing, rather than adopting a rotation changed by entity ticking.
        // Persist it independently so unloading and reloading a statue cannot bake in drift.
        val yaw = statues[mob.uniqueId] ?: return
        handle.yBodyRot = yaw
        handle.yBodyRotO = yaw
        handle.yHeadRot = yaw
        handle.yHeadRotO = yaw
        handle.yRot = yaw
        handle.yRotO = yaw
        handle.xRot = 0f
        handle.xRotO = 0f
        handle.setOnGround(true)
        if (handle is net.minecraft.world.entity.animal.chicken.Chicken) {
            handle.flap = 0f
            handle.oFlap = 0f
            handle.flapSpeed = 0f
            handle.oFlapSpeed = 0f
            handle.flapping = 0f
            handle.eggTime = Int.MAX_VALUE
        }
        if (handle is net.minecraft.world.entity.animal.squid.Squid) {
            // Never restart the client's tentacle cycle with entity event 19.
            squidSpeed.setFloat(handle, 0f)
            handle.tentacleMovement = 0f
            handle.oldTentacleMovement = 0f
            handle.tentacleAngle = 0f
            handle.oldTentacleAngle = 0f
            handle.xBodyRot = 0f
            handle.xBodyRotO = 0f
            handle.zBodyRot = 0f
            handle.zBodyRotO = 0f
        }
    }
}
