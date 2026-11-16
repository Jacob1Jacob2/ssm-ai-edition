package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.bots.*
import dev.betrix.superSmashMobsBrawl.bots.chat.CombatChatBridge
import dev.betrix.superSmashMobsBrawl.bots.combat.*
import dev.betrix.superSmashMobsBrawl.events.MinigameEndAnalyticsEvent
import dev.betrix.superSmashMobsBrawl.gui.BrawlGui.Companion.openInventory
import dev.betrix.superSmashMobsBrawl.gui.brawlGui
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import dev.betrix.superSmashMobsBrawl.minigames.MinigameState
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

/**
 * Bukkit bridge. Match facts and all sends are sampled on the server thread.
 *
 * Each live match owns one [CombatEventBus]. The game and the bots' brains publish what happens
 * to it; [CombatChatBridge] listens and feeds the match's [BotDialogue]. Nothing here talks to
 * the dialogue about combat directly.
 */
object BotConversationService : KoinComponent, Listener {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val games: MinigameService by inject()
    private val lang: LangService by inject()
    private val plain = PlainTextComponentSerializer.plainText()
    private class Session {
        val dialogue = BotDialogue()
        private var errorLogged = false
        val bus = CombatEventBus { event, error ->
            if (!errorLogged) {
                errorLogged = true
                plugin.logger.warning("Bot chat listener failed on ${event::class.simpleName}: ${error.stackTraceToString()}")
            }
        }
        init { CombatChatBridge(bus, dialogue) { BotManager.isBot(it) } }
        // A chat reply must not reveal a message to people outside its original audience.
        var chatAudience: Set<UUID>? = null
        /** Server tick at which the line being typed now will have been sent; the next one starts after it. */
        var chatFreeAt = 0
        var castLogged = false
    }
    /** The pause between one chat line landing and the next bot starting to type. */
    private const val READING_TICKS = 14
    private val sessions = mutableMapOf<BrawlMinigame, Session>()
    private var task: BukkitTask? = null
    private var running = false
    private fun now() = System.nanoTime() / 1_000_000

    fun setup() {
        running = true
        Bukkit.getPluginManager().registerEvents(this, plugin)
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 10L, 5L)
    }
    fun shutdown() { running = false; task?.cancel(); task = null; sessions.keys.toList().forEach(::close) }
    fun close(game: BrawlMinigame) { sessions.remove(game)?.let { it.bus.clear(); it.dialogue.clear() } }
    private fun session(game: BrawlMinigame): Session = sessions.getOrPut(game) { Session().also { it.dialogue.mode = game.settings.botPersonality } }
    private fun game(player: Player): BrawlMinigame? = if (!running) null else games.getMinigameForPlayer(player)
        ?.takeIf { it.getState() == MinigameState.IN_PROGRESS }

    // ------------------------------------------------------------------ combat events in

    /** Publishes to the bus of [player]'s live match. [event] receives the match clock in ms. */
    fun publish(player: Player, event: (Long) -> CombatEvent) { game(player)?.let { session(it).bus.publish(event(now())) } }
    fun started(game: BrawlMinigame) {
        if (running) session(game).bus.publish(MatchStarted(now()))
    }
    fun abilityUsed(player: Player, ability: String) = publish(player) { AbilityCast(player.uniqueId, ability, it) }
    fun damage(victim: Player, attacker: Player, amount: Double, source: String) {
        if (games.getMinigameForPlayer(attacker) !== games.getMinigameForPlayer(victim)) return
        val max = victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
        publish(victim) { DamageDealt(attacker.uniqueId, victim.uniqueId, amount, max, source, it) }
    }
    fun death(game: BrawlMinigame, victim: Player, killer: UUID?, ability: String) {
        if (running && game.getState() == MinigameState.IN_PROGRESS) session(game).bus.publish(FighterDied(victim.uniqueId, killer, ability, now()))
    }
    fun crystal(player: Player, tier: String) = publish(player) { CrystalActivated(player.uniqueId, tier, it) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun chat(event: AsyncChatEvent) {
        if (!running) return
        val id = event.player.uniqueId
        val body = plain.serialize(event.message())
        val audience = event.viewers().filterIsInstance<Player>().map { it.uniqueId }.toSet()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!running) return@Runnable
            val player = Bukkit.getPlayer(id) ?: return@Runnable
            if (BotManager.isBot(player)) return@Runnable
            val game = game(player) ?: return@Runnable
            if (game.allPlayers().none { it.uniqueId in audience && BotManager.isBot(it.uniqueId) }) return@Runnable
            val s = session(game)
            s.dialogue.mode = game.settings.botPersonality
            if (s.dialogue.chat(id, body, now())) s.chatAudience = audience
        })
    }
    @EventHandler fun ended(event: MinigameEndAnalyticsEvent) {
        val game = event.minigame
        // Administrative aborts don't get a victory taunt.
        if (event.winners.isNotEmpty()) {
            val s = session(game); s.dialogue.mode = game.settings.botPersonality
            // The match is over and the bots are standing still anyway: no typing pause.
            s.dialogue.finish(event.winners.map { it.uniqueId }.toSet(), fighters(game), now())?.let { send(game, it, null, typed = false) }
        }
        close(game)
    }

    private fun fighters(game: BrawlMinigame): List<BotDialogue.Fighter> = game.allPlayers().mapNotNull { it.player }
        .filter { it.isOnline && !game.connectionManager.isDisconnected(it) }.map { p ->
            val kit = KitService.getKitForPlayer(p)?.id ?: KitService.currentSelectedKitForPlayer(p).id
            BotDialogue.Fighter(p.uniqueId, BotManager.displayNameOf(p), plain.serialize(lang.t("kits.$kit.name")),
                (game.teamManager.findTeamOf(p)?.metadata?.get("stocks") as? Int ?: 0).coerceAtLeast(0),
                BotManager.isBot(p), p.gameMode == GameMode.SURVIVAL && !game.respawnManager.isRespawning(p))
        }
    private fun tick() {
        if (!running) return
        sessions.keys.toList().forEach { game ->
            if (game.getState() != MinigameState.IN_PROGRESS || game !in games.activeMinigames()) { close(game); return@forEach }
            val s = sessions[game] ?: return@forEach
            s.dialogue.mode = game.settings.botPersonality
            val facts = fighters(game)
            // Nobody to read it. (The bots-only debug matches keep talking so the log shows the lobby.)
            if (facts.none { !it.bot } && !BotManager.debug) { close(game); return@forEach }
            val time = now()
            // Health and position of every living fighter, humans included.
            facts.forEach { f ->
                if (!f.fighting || f.lives <= 0) return@forEach
                val p = Bukkit.getPlayer(f.id) ?: return@forEach
                val at = p.location
                s.bus.publish(FighterSample(f.id, at.x, at.y, at.z, p.health,
                    p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0, time))
            }
            s.dialogue.poll(time, facts) { a,b ->
                val first = Bukkit.getPlayer(a); val second = Bukkit.getPlayer(b)
                first != null && second != null && !game.arePlayersOnSameTeam(first, second)
            }?.let { send(game, it, s.chatAudience) }
            if (!s.dialogue.hasPending) s.chatAudience = null
            if (BotManager.debug && !s.castLogged && s.dialogue.personalities().isNotEmpty()) {
                s.castLogged = true
                plugin.logger.info("[chatdbg] personalities: " + s.dialogue.personalities().entries.joinToString {
                    "${Bukkit.getPlayer(it.key)?.name ?: it.key}=${it.value.label}" })
            }
        }
    }

    /** Who [bot] told the lobby it is going after (see [BotDialogue.grudge]), for the bot's target choice. */
    fun grudge(game: BrawlMinigame, bot: UUID): UUID? = sessions[game]?.dialogue?.grudge(bot, now())

    /**
     * Nobody types while running and jumping. A bot stops where it stands for as long as the line
     * would take to type, and only then does the message appear. Lines are typed one after another,
     * so an answer never arrives before the line it answers.
     */
    private fun send(game: BrawlMinigame, reply: BotDialogue.Reply, audience: Set<UUID>?, typed: Boolean = true) {
        if (game.settings.botPersonality == BotPersonality.SILENT) return
        val speaker = Bukkit.getPlayer(reply.speaker)?.takeIf { BotManager.isBot(it) && it.isOnline } ?: return
        fun deliver() {
            if (!speaker.isOnline) return
            if (BotManager.debug) plugin.logger.info("[chatdbg] ${speaker.name}: ${reply.text}")
            val message = ClassicChatService.message(speaker, Component.text(reply.text))
            game.allPlayers().mapNotNull { it.player }.filter {
                it.isOnline && !BotManager.isBot(it) && games.getMinigameForPlayer(it) === game && (audience == null || it.uniqueId in audience)
            }.forEach { it.sendMessage(message) }
        }
        val s = sessions[game]
        if (!typed || s == null) return deliver()
        val tick = Bukkit.getCurrentTick()
        val startIn = (s.chatFreeAt - tick).coerceIn(0, 100)
        val typing = BotSlang.typingTicks(reply.text)
        // Whoever answers has to read this line first, so the next one starts a beat after it lands.
        s.chatFreeAt = tick + startIn + typing + READING_TICKS
        BotManager.brainOf(speaker)?.typeFor(startIn, typing)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { if (running) deliver() }, (startIn + typing).toLong())
    }

    /** Explicit debugging command still respects Silent and the match audience/rate limits. */
    fun prompt(player: Player): Boolean {
        val game = game(player) ?: return false
        val s = session(game); s.dialogue.mode = game.settings.botPersonality
        s.dialogue.prompt(now()); return game.settings.botPersonality != BotPersonality.SILENT
    }
    fun select(player: Player, mode: BotPersonality): Boolean {
        val game = games.getMinigameForPlayer(player)
        if (game != null && game.host != player.uniqueId) {
            player.sendMessage(Component.text("The match host chooses the bots' personality.", NamedTextColor.RED)); return false
        }
        GameSetupService.setupOf(player).botPersonality = mode
        GameSetupService.save()
        if (game != null) { game.settings.botPersonality = mode; session(game).dialogue.mode = mode; session(game).chatAudience = null }
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, .5f, 1f)
        player.sendMessage(Component.text(
            if (mode == BotPersonality.RANDOM) "Bots: a random personality each" else "All bots: ${mode.label}", NamedTextColor.YELLOW))
        return true
    }
    fun openMenu(player: Player, opponentsPage: Int = 0) {
        val selected = games.getMinigameForPlayer(player)?.settings?.botPersonality ?: GameSetupService.setupOf(player).botPersonality
        val gui = brawlGui(Component.text("AI Personalities"), 27, selectionEffects=true) {
            onClick { isCancelled = true }
            BotPersonality.entries.forEachIndexed { i, mode ->
                val material = when(mode) {
                    BotPersonality.SILENT -> Material.MINECART; BotPersonality.CHILL -> Material.FURNACE_MINECART
                    BotPersonality.SALTY -> Material.CHEST_MINECART; BotPersonality.TOXIC -> Material.TNT_MINECART
                    BotPersonality.RANDOM -> Material.COMMAND_BLOCK_MINECART
                }
                val color = when(mode) {
                    BotPersonality.SALTY -> NamedTextColor.GOLD; BotPersonality.CHILL -> NamedTextColor.GREEN; BotPersonality.TOXIC -> NamedTextColor.RED
                    BotPersonality.SILENT -> NamedTextColor.GRAY; BotPersonality.RANDOM -> NamedTextColor.LIGHT_PURPLE
                }
                val description = when(mode) {
                    BotPersonality.TOXIC -> listOf("Trash talk about camping, spam and skill", "Tries to make everyone else mad")
                    BotPersonality.SALTY -> listOf("Complains when things go wrong", "Gets mad at whoever rubs it in")
                    BotPersonality.CHILL -> listOf("Friendly reactions and conversation", "Reacts to fights and your chat")
                    BotPersonality.SILENT -> listOf("Bots do not send chat messages")
                    BotPersonality.RANDOM -> listOf("Each bot gets its own personality", "They argue, take sides and go after each other", "No more than half are Silent")
                }
                val scope = if (mode == BotPersonality.RANDOM) "Dealt fresh every match" else "Applies to every bot in your match"
                set(9 + i * 2, ItemStack.of(material).apply { editMeta {
                    it.displayName(Component.text(mode.label, color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false))
                    it.lore((description + scope + if(mode == selected) listOf("Selected") else listOf("Click to select")).map { line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false) })
                    it.setEnchantmentGlintOverride(mode == selected)
                } }) { if(select(player, mode)) openMenu(player, opponentsPage) }
            }
            set(22, GuideMenus.item(Material.ARROW, "Back to Choose Opponents")) { SetupSelectionMenus.opponents(player, opponentsPage) }
        }
        player.openInventory(gui)
    }
}
