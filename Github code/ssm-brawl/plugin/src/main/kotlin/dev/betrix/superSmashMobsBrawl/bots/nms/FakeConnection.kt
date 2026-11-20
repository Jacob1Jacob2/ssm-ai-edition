package dev.betrix.superSmashMobsBrawl.bots.nms

import io.netty.channel.ChannelFutureListener
import io.netty.channel.embedded.EmbeddedChannel
import java.net.InetSocketAddress
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow

/**
 * A network connection with no client behind it. Packets the server sends to a bot are dropped.
 * The connection is never registered with the server's network listener, so it is never ticked
 * (no keep-alive timeouts).
 */
class FakeConnection : Connection(PacketFlow.SERVERBOUND) {
    init {
        channel = EmbeddedChannel()
        address = InetSocketAddress("127.0.0.1", 0)
    }

    override fun isConnected(): Boolean = true

    override fun send(packet: Packet<*>) { sniff?.invoke(this, packet) }

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?) { sniff?.invoke(this, packet) }

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) { sniff?.invoke(this, packet) }

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
