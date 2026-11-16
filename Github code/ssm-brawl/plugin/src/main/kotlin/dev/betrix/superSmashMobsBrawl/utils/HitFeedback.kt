package dev.betrix.superSmashMobsBrawl.utils

import org.bukkit.Sound
import org.bukkit.entity.Player

object HitFeedback {
    fun play(player: Player, bow: Boolean = false) {
        if (bow) (player as org.bukkit.craftbukkit.entity.CraftPlayer).handle.connection.send(
            net.minecraft.network.protocol.game.ClientboundGameEventPacket(
                net.minecraft.network.protocol.game.ClientboundGameEventPacket.PLAY_ARROW_HIT_SOUND, 0f))
        else player.playSound(player.eyeLocation, Sound.ENTITY_ARROW_HIT_PLAYER, 1f, 1f)
    }
}
