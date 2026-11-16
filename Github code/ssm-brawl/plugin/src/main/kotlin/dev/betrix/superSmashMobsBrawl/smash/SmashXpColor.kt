package dev.betrix.superSmashMobsBrawl.smash

import org.bukkit.entity.Player
import net.kyori.adventure.text.format.TextColor
import java.util.UUID

/** Only HUD state changes during play; all assets are already in the join-time pack. */
object SmashXpColor {
    private val current=mutableMapOf<UUID,TextColor>()
    fun set(player:Player,tier:SmashTier) {
        current[player.uniqueId]=TextColor.color(tier.rgb.asRGB())
    }
    fun darkGreen(player:Player) { current[player.uniqueId]=TextColor.color(0x258B36) }
    fun clear(player:Player) { current.remove(player.uniqueId) }
    fun color(player:Player): TextColor? = current[player.uniqueId]
}
