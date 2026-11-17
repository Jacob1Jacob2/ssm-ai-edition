package dev.betrix.superSmashMobsBrawl.minigames.managers

import dev.betrix.superSmashMobsBrawl.Manageable
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.minigames.MinigameState
import dev.betrix.superSmashMobsBrawl.models.brawlData.KitSwitchingMode
import dev.betrix.superSmashMobsBrawl.models.brawlData.TeamBasedStocksMinigameDef
import dev.betrix.superSmashMobsBrawl.services.KitService
import gg.flyte.twilight.event.event
import gg.flyte.twilight.scheduler.repeatingTask
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.NamedTextColor.*
import net.kyori.adventure.text.format.TextDecoration.BOLD
import net.kyori.adventure.title.Title
import org.bukkit.entity.Player
import org.bukkit.Sound
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.util.Vector
import java.time.Duration

/** Owns the opening presentation and the short protected kit-selection window. */
class MatchIntroManager(private val game:BrawlMinigame):Manageable() {
    private var started=false
    private val gravity=mutableMapOf<Player,Boolean>()
    private val viewers=mutableSetOf<Player>()
    fun remove(player: Player) {
        viewers.remove(player)
        gravity.remove(player)?.let { player.setGravity(it) }
        player.hideBossBar(bar)
        player.clearTitle()
    }
    // Old chat stays visible, but its action belongs only to this countdown.
    // Never point it at /kit, which could open a later lobby or match's menu.
    private val kitLink by lazy {
        ClickEvent.callback({ audience ->
            val player=audience as? Player
            if(player!=null && started && player in viewers && game.getState()==MinigameState.STARTING) {
                KitService.openKitSelectionGui(player)
            }
        }, ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(1)).build())
    }
    private val bar=BossBar.bossBar(
        Component.text("SSM AI Edition",GOLD,BOLD).append(Component.text(" - SUPER SMASH MOBS",WHITE,BOLD)),
        1f,BossBar.Color.PINK,BossBar.Overlay.PROGRESS)

    private fun kitPrompt() = Component.text("  CLICK HERE",YELLOW,BOLD)
        .append(Component.text(" if you want to change your kit!",AQUA).decoration(BOLD,false))
        .clickEvent(kitLink)
    private fun promptFooter()=Component.text("═".repeat(30),GRAY)

    fun messages(includeKitPrompt:Boolean=true):List<Component> {
        val world=game.worldManager.getWorld()
        val map=world?.data
        // Super Smash Mobs starts with the lives picked in Game Settings, not the minigame's default.
        val respawns=(if(game.minigameDef.id=="super_smash_mobs")game.settings.lives.coerceIn(1,8)
            else (game.minigameDef as? TeamBasedStocksMinigameDef)?.stocks ?: 4)-1
        val separator=Component.text("═".repeat(30),GREEN)
        val credit=Component.text("Map - ",GREEN)
            .append(Component.text(map?.displayName ?: map?.id ?: "Unknown",WHITE,BOLD))
            .append(if(world?.transform?.active==true)Component.text(" (${world.transform.description})",GOLD) else Component.empty())
        val credited=if(map?.creators?.isNotEmpty()==true)credit
            .append(Component.text(" created by ",GRAY))
            .append(Component.text(map.creators.joinToString(", "),WHITE,BOLD)) else credit
        return buildList {
            add(separator)
            add(Component.text("Game - ",GREEN).append(Component.text("Super Smash Mobs",YELLOW,BOLD)))
            add(Component.empty())
            add(Component.text("  Each player has $respawns ${if(respawns==1)"respawn" else "respawns"}",WHITE))
            if(game.isPassiveValid("hunger"))add(Component.text("  Attack to restore hunger!",WHITE))
            add(Component.text(if(game.settings.allBotsVsPlayer)"  Defeat the opposing team!" else "  Last player alive wins!",WHITE))
            add(Component.empty());add(credited);add(separator)
            if(includeKitPrompt && game.getKitSwitchingMode()!=KitSwitchingMode.MYSTERY) {
                add(Component.empty());add(kitPrompt());add(promptFooter())
            }
        }
    }

    fun start(onComplete:()->Unit) {
        if(started)return
        started=true
        val title=Title.title(Component.text("Super Smash Mobs",YELLOW,BOLD),
            Component.text(if(game.settings.allBotsVsPlayer)"Defeat the opposing team!" else "Last player alive wins!",WHITE),
            Title.Times.times(Duration.ofMillis(300),Duration.ofMillis(3800),Duration.ofMillis(600)))
        val lines=messages(includeKitPrompt=false)
        game.allPlayers().filter { !game.connectionManager.isDisconnected(it) }.mapNotNull {it.player}.forEach {p ->
            gravity[p]=p.hasGravity();p.setGravity(false);p.velocity=Vector()
            viewers+=p;p.showBossBar(bar);p.showTitle(title);lines.forEach(p::sendMessage)
            p.playSound(p.location,Sound.ENTITY_PLAYER_LEVELUP,1f,1f)
        }
        listeners.add(event<PlayerMoveEvent>(priority=EventPriority.HIGHEST) {
            if(game.getState()==MinigameState.STARTING && player in viewers && hasChangedPosition()) {
                val destination=from.clone();destination.yaw=to.yaw;destination.pitch=to.pitch;to=destination
                player.velocity=Vector()
            }
        })
        listeners.add(event<EntityDamageEvent>(priority=EventPriority.HIGHEST) {
            if(game.getState()==MinigameState.STARTING && entity in viewers)isCancelled=true
        })
        listeners.add(event<PlayerInteractEvent>(priority=EventPriority.HIGHEST) {
            if(game.getState()==MinigameState.STARTING && player in viewers)isCancelled=true
        })
        listeners.add(event<EntityShootBowEvent>(priority=EventPriority.HIGHEST) {
            if(game.getState()==MinigameState.STARTING && entity in viewers)isCancelled=true
        })
        bar.name(dev.betrix.superSmashMobsBrawl.utils.ClassicSidebar.introTitle(0))
        countdownFrame(0)
        var ticks=0
        runnables.add(repeatingTask(1) {
            if(game.getState()!=MinigameState.STARTING) {teardown();cancel();return@repeatingTask}
            ticks++
            if(ticks<100)bar.name(dev.betrix.superSmashMobsBrawl.utils.ClassicSidebar.introTitle(ticks))
            if(ticks==20 && game.getKitSwitchingMode()!=KitSwitchingMode.MYSTERY) {
                viewers.filter {it.isOnline}.forEach {it.sendMessage(Component.empty());it.sendMessage(kitPrompt());it.sendMessage(promptFooter())}
            }
            if(ticks>=100) {
                restoreGravity()
                val recipients = viewers.filter {it.isOnline}.toList()
                recipients.forEach { it.closeInventory() }
                teardown()
                onComplete()
                recipients.forEach {
                    dev.betrix.superSmashMobsBrawl.services.EnergyHud.message(it, Component.text("Start", GREEN, BOLD))
                    it.playSound(it.location,Sound.BLOCK_NOTE_BLOCK_PLING,1f,2f)
                }
                cancel()
            } else if(ticks%2==0) {
                countdownFrame(ticks)
            }
        })
    }

    /** Same 24-segment meter as the ability bar; it owns the action bar until combat starts. */
    private fun countdownFrame(ticks:Int) {
        val filled=ticks*24/100
        bar.progress(1f)
        val meter=Component.text("Game Start ",WHITE).decorate(BOLD)
            .append(Component.text("▌".repeat(filled),GREEN).decoration(BOLD,false))
            .append(Component.text("▌".repeat(24-filled),RED).decoration(BOLD,false))
            .append(Component.text(" ${"%.1f".format(java.util.Locale.ROOT,(100-ticks)/20.0)} Seconds",WHITE).decoration(BOLD,false))
        viewers.filter {it.isOnline}.forEach {
            dev.betrix.superSmashMobsBrawl.services.EnergyHud.message(it, meter)
            if(ticks%20==0)it.playSound(it.location,Sound.BLOCK_NOTE_BLOCK_HAT,0.5f,1f)
        }
    }

    private fun restoreGravity() {gravity.forEach {(p,original)->p.setGravity(original);p.velocity=Vector()};gravity.clear()}
    override fun teardown() {
        super.teardown();restoreGravity()
        viewers.forEach {it.hideBossBar(bar);it.clearTitle()};viewers.clear()
    }
}
