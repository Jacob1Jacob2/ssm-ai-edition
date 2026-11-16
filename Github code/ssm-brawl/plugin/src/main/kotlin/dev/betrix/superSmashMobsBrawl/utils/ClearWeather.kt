package dev.betrix.superSmashMobsBrawl.utils

import net.minecraft.network.protocol.game.ClientboundGameEventPacket
import org.bukkit.WeatherType
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player

object ClearWeather {
    fun immediately(player:Player) {
        player.setPlayerWeather(WeatherType.CLEAR)
        val handle=(player as CraftPlayer).handle
        // Paper retains a per-player rain fade, even across a world change. Drain both
        // interpolation endpoints now rather than letting the old storm fade for 5 seconds.
        repeat(101) {handle.tickWeather()}
        handle.connection.send(ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE,0f))
        handle.connection.send(ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE,0f))
    }
}
