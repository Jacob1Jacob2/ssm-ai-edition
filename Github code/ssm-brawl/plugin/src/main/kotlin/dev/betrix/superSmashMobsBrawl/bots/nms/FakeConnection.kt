package dev.betrix.superSmashMobsBrawl.bots.nms

import dev.betrix.superSmashMobsBrawl.bots.ArenaChanges
import io.netty.channel.ChannelFutureListener
import io.netty.channel.embedded.EmbeddedChannel
import java.net.InetSocketAddress
import net.minecraft.network.Connection
import net.minecraft.network.protocol.BundlePacket
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket

/**
 * A network connection with no client behind it. Packets the server sends to a bot are dropped,
 * all but one kind: word that a block has changed, which is how a player's game comes to show a
 * hole where the floor was. A bot has no game to show it anything, so that word goes to the bots'
 * picture of the arena instead ([ArenaChanges]).
 * The connection is never registered with the server's network listener, so it is never ticked
 * (no keep-alive timeouts).
 */
class FakeConnection : Connection(PacketFlow.SERVERBOUND) {
    init {
        channel = EmbeddedChannel()
        address = InetSocketAddress("127.0.0.1", 0)
    }

    override fun isConnected(): Boolean = true

    override fun send(packet: Packet<*>) { got(packet) }

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?) { got(packet) }

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) { got(packet) }

    private fun got(packet: Packet<*>) {
        sniff?.invoke(this, packet)
        try { blocks(packet) } catch (_: Exception) { /* A bot that misses a block change finds out the old way, by walking into it. */ }
    }

    /** Word of blocks changing, on its own or among other things sent together. May be on any thread: only handed on. */
    private fun blocks(packet: Packet<*>) {
        when (packet) {
            is ClientboundBlockUpdatePacket -> {
                val world = player?.level()?.world?.uid ?: return
                ArenaChanges.sighted(world, packet.pos.x, packet.pos.y, packet.pos.z)
            }
            is ClientboundSectionBlocksUpdatePacket -> {
                val world = player?.level()?.world?.uid ?: return
                packet.runUpdates { pos, _ -> ArenaChanges.sighted(world, pos.x, pos.y, pos.z) }
            }
            is BundlePacket<*> -> packet.subPackets().forEach { blocks(it) }
            else -> {}
        }
    }

    companion object {
        /**
         * Debug: shown every packet the server sends to a bot, and which bot's connection it was
         * for. A bot has no game to draw anything, but what the server tells it is exactly what it
         * would tell a player standing there: the one way to check, without a player, what a
         * player's game is being told.
         */
        @Volatile var sniff: ((FakeConnection, Packet<*>) -> Unit)? = null
    }
}
