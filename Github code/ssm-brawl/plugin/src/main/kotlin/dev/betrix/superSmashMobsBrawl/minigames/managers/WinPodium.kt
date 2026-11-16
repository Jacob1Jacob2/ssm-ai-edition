package dev.betrix.superSmashMobsBrawl.minigames.managers

import com.mojang.authlib.GameProfile
import dev.betrix.superSmashMobsBrawl.bots.BotManager
import net.minecraft.network.protocol.game.*
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3
import org.bukkit.*
import org.bukkit.block.data.BlockData
import org.bukkit.craftbukkit.CraftServer
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import org.bukkit.entity.Firework
import org.bukkit.util.Vector
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.EnumSet
import java.util.UUID

/** A private, temporary victory stage. The winner's skin is shown from the audience's viewpoint. */
class WinPodium private constructor(
    private val world: World,
    winners: List<Player>,
    private val audience: List<Player>,
    private val plugin: Plugin,
) : AutoCloseable {
    private val blocks = linkedMapOf<org.bukkit.block.Block, BlockData>()
    private val tasks = mutableListOf<BukkitTask>()
    private val avatars = mutableListOf<ServerPlayer>()
    private val rockets = mutableSetOf<Firework>()
    private val launchPoints = mutableMapOf<Firework, Location>()
    private var closed = false
    // Away from the playable arena, so even the tallest scaled maps cannot obscure the stage.
    private val base = Location(world, world.spawnLocation.blockX + 1024.5,
        minOf(200, world.maxHeight - 24).toDouble(), world.spawnLocation.blockZ + 1024.5)

    init {
        // Keep every block of the supplied island and its original viewing cage.
        stage.forEach { (x, y, z, data) ->
            val block = world.getBlockAt(base.blockX+x, base.blockY+y,
                base.blockZ+z)
            blocks.putIfAbsent(block, block.blockData.clone())
            block.setBlockData(data, false)
        }
        winners.take(3).forEachIndexed { i, winner ->
            val source = (winner as CraftPlayer).handle
            val profile = GameProfile(UUID.randomUUID(), winner.name, source.gameProfile.properties())
            val avatar = ServerPlayer((Bukkit.getServer() as CraftServer).server,
                (world as CraftWorld).handle, profile, ClientInformation.createDefault())
            val at = base.clone().add((i - (minOf(3, winners.size) - 1) / 2.0) * 1.1, 0.0, 0.0)
            avatar.snapTo(at.x, at.y, at.z, 0f, 0f)
            avatar.setYHeadRot(0f)
            avatar.setYBodyRot(0f)
            // Carry the winner's skin-layer preferences as well as their texture profile.
            avatar.updateOptionsNoEvents(source.clientInformation())
            avatars += avatar
        }
        val camera = base.clone().add(0.0, 3.0, 10.0)
        camera.direction = base.clone().add(0.0, 1.4, 0.0).toVector()
            .subtract(camera.clone().add(0.0, 1.62, 0.0).toVector())
        audience.forEach {
            // Adventure collides with the supplied barrier cage; spectators would fly through it.
            it.gameMode = GameMode.ADVENTURE
            it.setGravity(true)
            it.isFlying = false; it.allowFlight = false; it.fallDistance = 0f
            it.teleport(camera); it.velocity = org.bukkit.util.Vector()
            it.playSound(it.location,Sound.ENTITY_PLAYER_LEVELUP,1f,1f)
            it.setPlayerTime(12000, false)
        }
        // Teleport/chunk packets must precede the player entity spawn.
        tasks += Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!closed) viewers().forEach(::showAvatars)
        }, 3L)
        var age = 0
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (closed) return@Runnable
            val viewers = viewers()
            if (viewers.isEmpty()) return@Runnable
            // Keep the winner on the platform, turning continuously while punching the air.
            val yaw = (age * 9 % 360).toFloat()
            avatars.forEach { avatar ->
                avatar.snapTo(avatar.x, base.y, avatar.z, yaw, 0f)
                avatar.setYHeadRot(yaw); avatar.setYBodyRot(yaw)
                viewers.forEach { viewer ->
                    val connection = (viewer as CraftPlayer).handle.connection
                    connection.send(ClientboundEntityPositionSyncPacket.of(avatar))
                    connection.send(ClientboundRotateHeadPacket(avatar, (yaw * 256f / 360f).toInt().toByte()))
                    if (age % 6 == 0) connection.send(
                        ClientboundSwingAnimationPacket(avatar, InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT))
                }
            }
            // A rotating pair of upward launches makes a continuous circular fountain.
            // Allow the last volley to burst before the eight-second scene ends.
            if (age <= 110 && age % 10 == 0) {
                repeat(2) { side -> launchRocket((age / 10 % 8) * Math.PI / 4 + side * Math.PI, age / 10 + side * 3) }
            }
            rockets.removeIf { rocket ->
                if (!rocket.isValid) { launchPoints.remove(rocket); true }
                else {
                    // Steer upward with a slight outward lean; the client renders the native trail.
                    val start = launchPoints.getValue(rocket)
                    val radial = start.toVector().subtract(base.toVector()).setY(0.0).normalize()
                    rocket.velocity = radial.multiply(.035).setY(.33)
                    false
                }
            }
            age++
        }, 4L, 1L)
    }

    private fun launchRocket(angle: Double, index: Int) {
        val at = base.clone().add(kotlin.math.cos(angle)*3.0, -1.0, kotlin.math.sin(angle)*3.0)
        val palette = listOf(Color.FUCHSIA, Color.LIME, Color.AQUA, Color.YELLOW, Color.PURPLE, Color.ORANGE, Color.RED)
        val rocket = world.spawn(at, Firework::class.java) {
            it.isPersistent = false
            val meta = it.fireworkMeta
            meta.clearEffects()
            meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                .withColor(palette[index % palette.size]).withFade(Color.WHITE).trail(true).flicker(true).build())
            meta.power = 0
            it.fireworkMeta = meta
            it.velocity = Vector(0.0, .33, 0.0)
            // We send a cosmetic explosion status ourselves, so the celebration cannot hurt anyone.
            it.ticksToDetonate = 600
        }
        rockets += rocket; launchPoints[rocket] = at
        world.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, .65f, 1f)
        tasks += Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!closed && rocket.isValid) {
                val location = rocket.location
                rocket.playEffect(EntityEffect.FIREWORK_EXPLODE)
                world.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, .75f, 1f)
                tasks += Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    if (!closed) world.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, .65f, 1f)
                }, 7L)
                rocket.remove(); rockets.remove(rocket); launchPoints.remove(rocket)
            }
        }, (30 + index % 4 * 3).toLong())
    }

    private fun viewers() = audience.filter { it.isOnline && it.world == world }

    private fun showAvatars(player: Player) {
        val connection = (player as CraftPlayer).handle.connection
        avatars.forEach { avatar ->
            val entry = ClientboundPlayerInfoUpdatePacket.Entry(avatar.uuid, avatar.gameProfile,
                false, 0, GameType.SURVIVAL, null, true, 0, null)
            connection.send(ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED), listOf(entry)))
            connection.send(ClientboundAddEntityPacket(avatar.id, avatar.uuid, avatar.x, avatar.y, avatar.z,
                0f, 0f, avatar.type, 0, Vec3.ZERO, 0.0))
            avatar.entityData.nonDefaultValues?.let {
                connection.send(ClientboundSetEntityDataPacket(avatar.id, it))
            }
            connection.send(ClientboundRotateHeadPacket(avatar, 0.toByte()))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.forEach { it.cancel() }
        rockets.forEach { it.remove() }; rockets.clear(); launchPoints.clear()
        audience.filter { it.isOnline }.forEach { player ->
            player.resetPlayerTime()
            val connection = (player as CraftPlayer).handle.connection
            connection.send(ClientboundRemoveEntitiesPacket(*avatars.map { it.id }.toIntArray()))
            connection.send(ClientboundPlayerInfoRemovePacket(avatars.map { it.uuid }))
        }
        blocks.forEach { (block, data) -> block.setBlockData(data, false) }
        blocks.clear()
    }

    companion object {
        private data class StageBlock(val x:Int,val y:Int,val z:Int,val data:BlockData)
        private val stage by lazy {
            java.util.zip.GZIPInputStream(requireNotNull(WinPodium::class.java.getResourceAsStream("/data/winning-stage.tsv.gz")))
                .bufferedReader().useLines { lines -> lines.filterNot { it.startsWith("#") || it.isBlank() }.map {
                    val fields=it.split('\t',limit=4)
                    StageBlock(fields[0].toInt(),fields[1].toInt(),fields[2].toInt(),Bukkit.createBlockData(fields[3]))
                }.toList() }
        }

        fun show(world: World?, winners: List<OfflinePlayer>, audience: List<Player>, plugin: Plugin): WinPodium? {
            val people = audience.filter { it.isOnline && !BotManager.isBot(it) }
            val bodies = winners.mapNotNull { it.player }
            if (world == null || people.isEmpty() || bodies.isEmpty()) return null
            return WinPodium(world, bodies, people, plugin)
        }
    }
}
