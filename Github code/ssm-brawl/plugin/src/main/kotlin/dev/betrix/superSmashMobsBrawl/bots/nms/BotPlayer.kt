package dev.betrix.superSmashMobsBrawl.bots.nms

import com.mojang.authlib.GameProfile
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * Server-side player controlled by [dev.betrix.superSmashMobsBrawl.bots.BotBrain].
 *
 * Normal players are moved by their client; the server skips their physics. This class tells the
 * server it owns the movement, so gravity, knockback (setVelocity), walking and jumping are all
 * simulated here every tick, exactly like they would be for a mob.
 */
class BotPlayer(private val mcServer: MinecraftServer, level: ServerLevel, profile: GameProfile) :
    ServerPlayer(mcServer, level, profile, ClientInformation.createDefault()) {

    /** Movement input for the next tick, -1..1 (like holding W/S and A/D). */
    @Volatile var forwardInput: Float = 0f
    @Volatile var strafeInput: Float = 0f
    @Volatile var jumpInput: Boolean = false

    override fun isClientAuthoritative(): Boolean = false

    override fun tick() {
        // Keep chunk tracking in sync, as a real client's movement packets would.
        if (mcServer.tickCount % 10 == 0) {
            connection.resetPosition()
            level().chunkSource.move(this)
        }

        // A real client sends "I've loaded the world" after joining and after every world change;
        // until then the server makes the player invulnerable to attacks. Bots have no client, so
        // they'd be immune to every real player's hits forever. Do what the client would do.
        if (isChangingDimension) hasChangedDimension()
        if (!connection.hasClientLoaded()) connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())

        zza = forwardInput
        xxa = strafeInput
        setJumping(jumpInput)

        super.tick()
        // For real players this runs when their movement packet arrives; bots run it themselves.
        doTick()
    }
}
