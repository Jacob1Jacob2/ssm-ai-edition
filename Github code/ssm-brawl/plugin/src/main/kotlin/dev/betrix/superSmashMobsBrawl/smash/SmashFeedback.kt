package dev.betrix.superSmashMobsBrawl.smash

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor.*
import net.kyori.adventure.text.format.TextDecoration.BOLD
import net.kyori.adventure.title.Title
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.time.Duration

/** Match-wide announcements follow the supplied classic Mineplex screenshot. */
object SmashFeedback {
    fun collected(player: Player, tier: SmashTier, viewers: Collection<Player>) {
        val line = Component.text("${player.name} collected ", WHITE, BOLD)
            .append(Component.text("${tier.title}!", tier.color, BOLD))
        viewers.forEach { it.sendMessage(line) }
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
    }
    fun activated(player: Player, spec: SmashSpec, viewers: Collection<Player>) {
        val line = Component.text("${player.name} activated ", WHITE, BOLD)
            .append(Component.text("${spec.name}!", spec.tier.color, BOLD))
        val title = Title.title(Component.text(spec.tier.title, spec.tier.color),
            Component.text("${player.name} used ", WHITE).append(Component.text(spec.name, spec.tier.color)),
            Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500)))
        viewers.forEach { it.sendMessage(line); it.showTitle(title) }
        player.world.playSound(player.location, if(spec.tier.ordinal<2) Sound.BLOCK_NOTE_BLOCK_PLING else Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1f)
    }
}
