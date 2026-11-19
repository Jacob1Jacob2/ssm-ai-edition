package dev.betrix.superSmashMobsBrawl.minigames.managers

import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor.*
import net.kyori.adventure.text.format.TextDecoration.BOLD
import net.kyori.adventure.title.Title
import org.bukkit.OfflinePlayer
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.time.Duration

/** Actual elimination order only; leaving/cancelling has no victory presentation. */
object MatchEnding {
    data class Presentation(val lines:List<Component>,val title:Title, val game:BrawlMinigame, val winners:Set<java.util.UUID>) {
        fun show(player:Player) {
            lines.forEach(player::sendMessage)
            // In a match of two teams everybody is told which side won; otherwise the losers are told they died.
            player.showTitle(if(player.uniqueId in winners || game.sides.isNotEmpty()) title else deathTitle())
            dev.betrix.superSmashMobsBrawl.services.ScoreboardService.showEnding(game, player)
        }
    }
    private fun deathTitle() = Title.title(dev.betrix.superSmashMobsBrawl.utils.CombatFeedback.livesTitle(0), Component.empty(),
        Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofSeconds(1)))
    private fun action(label:String, color:net.kyori.adventure.text.format.TextColor, command:String) =
        Component.text("- ", GRAY).append(Component.text(label, color, BOLD))
            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(command))
    fun showEliminated(player:Player) {
        player.showTitle(deathTitle())
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
        player.sendMessage(action("RETURN TO LOBBY", WHITE, "/hub"))
    }
    fun presentation(game:BrawlMinigame,winners:List<OfflinePlayer>):Presentation? {
        if(winners.isEmpty())return null
        val map=game.worldManager.getWorld()?.data
        val separator=Component.text("═".repeat(30),GREEN)
        val names=winners.joinToString(", ") {dev.betrix.superSmashMobsBrawl.bots.BotManager.displayNameOf(it)}
        // Two teams: a side has won, not a list of names (eight of them would not fit a title).
        val side=winners.firstOrNull()?.let(game::sideOf)
        val winnerName=side?.label ?: names
        val lines=buildList {
            add(separator);add(Component.text("Game - ",GREEN).append(Component.text("Super Smash Mobs",YELLOW,BOLD)))
            add(Component.empty())
            add(Component.text("1st Place ",RED,BOLD).append(Component.text(winnerName,side?.color ?: WHITE).decoration(BOLD,false)))
            if(side!=null)add(Component.text("  $names",side.color))
            if(!game.settings.allBotsVsPlayer && side==null)game.eliminationOrder.asReversed()
                .filter {it !in winners}.distinctBy {it.uniqueId}.take(2).forEachIndexed {i,player ->
                    add(Component.text(if(i==0)"2nd Place " else "3rd Place ",if(i==0)GOLD else YELLOW,BOLD)
                        .append(Component.text(dev.betrix.superSmashMobsBrawl.bots.BotManager.displayNameOf(player),WHITE).decoration(BOLD,false)))
                }
            add(Component.empty())
            val credit=Component.text("Map - ",GREEN).append(Component.text(map?.displayName ?: "Arena",WHITE,BOLD))
            add(if(map?.creators?.isNotEmpty()==true)credit.append(Component.text(" created by ",GRAY))
                .append(Component.text(map.creators.joinToString(", "),WHITE,BOLD)) else credit)
            add(separator)
            add(Component.empty())
            add(action("PLAY AGAIN", GREEN, "/playagain"))
            add(action("CHOOSE A DIFFERENT MAP", YELLOW, "/playagain map"))
            add(action("RETURN TO LOBBY", WHITE, "/hub"))
        }
        return Presentation(lines,Title.title(Component.text(winnerName,side?.color ?: YELLOW),Component.text("won the game",YELLOW),
            Title.Times.times(Duration.ofMillis(300),Duration.ofMillis(1500),Duration.ofMillis(700))), game, winners.map { it.uniqueId }.toSet())
    }
}
