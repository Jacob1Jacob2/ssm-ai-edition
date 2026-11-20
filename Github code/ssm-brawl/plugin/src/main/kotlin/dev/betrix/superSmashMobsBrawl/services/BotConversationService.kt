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
    private class Session(teamUps: Boolean) {
        val dialogue = BotDialogue()
        /** Who the bots have teamed up on, in a Team-Ups match (never anybody in the other modes). */
        val teamUps = TeamUps().also { it.enabled = teamUps }
        private var errorLogged = false
        val bus = CombatEventBus { event, error ->
            if (!errorLogged) {
                errorLogged = true
                plugin.logger.warning("Bot chat listener failed on ${event::class.simpleName}: ${error.stackTraceToString()}")
            }
        }
        init {
            dialogue.teamUps = this.teamUps
            CombatChatBridge(bus, dialogue, this.teamUps.takeIf { teamUps }) { BotManager.isBot(it) && it !in BotManager.standIns }
        }
        // A chat reply must not reveal a message to people outside its original audience.
        var chatAudience: Set<UUID>? = null
        /** Server tick at which the line being typed now will have been sent; the next one starts after it. */
        var chatFreeAt = 0
        var castLogged = false
        /** Two teams: what each bot was asked to do by a player on its own side, while that still stands ([TeamTalk]). */
        val orders = HashMap<UUID, TeamTalk.Standing>()
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
    private fun session(game: BrawlMinigame): Session = sessions.getOrPut(game) {
        Session(game.settings.teamUps).also { it.dialogue.mode = game.settings.botPersonality }
    }
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
            heard(player, body, audience)
        })
    }

    /** A line [player] typed reaches the bots of their match. On the main thread. */
    private fun heard(player: Player, body: String, audience: Set<UUID>) {
        val id = player.uniqueId
        val game = game(player) ?: return
        if (game.allPlayers().none { it.uniqueId in audience && BotManager.isBot(it.uniqueId) }) return
        val s = session(game)
        s.dialogue.mode = game.settings.botPersonality
        // Two teams: what a player says to the bots on their own side is first of all something they want done.
        if (teamOrder(game, s, player, body, audience)) return
        // What a player types counts with the lobby too: trash talk heats it up, an apology cools it.
        s.teamUps.said(id, body.take(256).lowercase(), now())
        if (s.dialogue.chat(id, body, now())) s.chatAudience = audience
    }

    /** Debug: [player] (a bot standing in for a player) says [body] to its match, as if typed. */
    fun say(player: Player, body: String) {
        val game = game(player) ?: return
        heard(player, body, game.allPlayers().map { it.uniqueId }.toSet())
    }

    // ------------------------------------------------------------------ two teams: doing what a teammate asks

    private fun kitName(player: Player) = plain.serialize(lang.t("kits.${KitService.getKitForPlayer(player)?.id ?: KitService.currentSelectedKitForPlayer(player).id}.name"))

    /**
     * Reads [body] as something [leader] wants of the bots on their side ([TeamTalk]) and, if it is,
     * has them do it and one of them say so. False when the line asks nothing of the team, and it
     * is ordinary chat.
     */
    private fun teamOrder(game: BrawlMinigame, s: Session, leader: Player, body: String, audience: Set<UUID>): Boolean {
        val side = game.sideOf(leader) ?: return false
        val present = game.allPlayers().mapNotNull { it.player }.filter { it.isOnline && !game.connectionManager.isDisconnected(it) }
        fun named(p: Player) = TeamTalk.Named(p.uniqueId, BotManager.displayNameOf(p), kitName(p))
        val mates = present.filter { it != leader && game.sideOf(it) == side && BotManager.isBot(it) && it.uniqueId !in BotManager.standIns }
        val foes = present.filter { other -> game.sideOf(other).let { it != null && it != side } }
        val order = TeamTalk.parse(body, mates.map(::named), foes.map(::named)) ?: return false
        val time = now()
        // "Him", "that one": whoever the player hit last, or failing that whoever is nearest to them.
        val standing = foes.filter { it.gameMode == GameMode.SURVIVAL && it.world == leader.world && !game.respawnManager.isRespawning(it) }
        val target = order.target ?: if (order.ask != TeamTalk.Ask.FOCUS) null else
            standing.filter { leader.uniqueId in CombatLogService.recentAttackers(it, 8000) }.ifEmpty { standing }
                .minByOrNull { it.location.distanceSquared(leader.location) }?.uniqueId
        val told = mates.filter { it.uniqueId in order.to }
        for (bot in told) when (order.ask) {
            TeamTalk.Ask.FREE, TeamTalk.Ask.PUSH -> s.orders.remove(bot.uniqueId)
            TeamTalk.Ask.UNCLEAR -> {}
            TeamTalk.Ask.FOCUS -> if (target != null) s.orders[bot.uniqueId] =
                TeamTalk.Standing(order.ask, leader.uniqueId, target, time + TeamTalk.lasts(order.ask))
            else -> {
                // "Stay here" is where the player is when the bot is with them, and else where the bot stands.
                val spot = if (order.ask != TeamTalk.Ask.HOLD) null
                    else (if (bot.world == leader.world && bot.location.distanceSquared(leader.location) < 144) leader else bot).location
                s.orders[bot.uniqueId] = TeamTalk.Standing(order.ask, leader.uniqueId, null, time + TeamTalk.lasts(order.ask),
                    spot?.let { Triple(it.x, it.y, it.z) })
            }
        }
        // One of them says so: the one nearest to the player among those that talk at all.
        val facts = fighters(game)
        val answered = told.sortedBy { if (it.world == leader.world) it.location.distanceSquared(leader.location) else Double.MAX_VALUE }
            .any { s.dialogue.teamAnswer(it.uniqueId, leader.uniqueId, order.ask, target, facts, time) }
        if (answered) s.chatAudience = audience
        // Bots that do not talk do it all the same, and the player is told that they heard. Not when
        // it is who to go after, though: that is not announced (the player asked for it out of chat),
        // the team just does it.
        else if (told.isNotEmpty() && !BotManager.isBot(leader)) TeamTalk.heard(order.ask)?.let { does ->
            val who = if (told.size <= 2) told.joinToString(" and ") { BotManager.displayNameOf(it) } else "Your team"
            leader.sendMessage(Component.text("Team> ", NamedTextColor.BLUE).append(Component.text("$who ${if (told.size == 1) "is" else "are"} $does.", NamedTextColor.GRAY)))
        }
        if (BotManager.debug) plugin.logger.info("[team] ${BotManager.displayNameOf(leader)} said \"${body.take(80)}\": ${order.ask}" +
            (present.find { it.uniqueId == target }?.let { " on ${BotManager.displayNameOf(it)}" } ?: "") +
            " for ${told.joinToString { BotManager.displayNameOf(it) }.ifEmpty { "nobody" }}" + if (answered) "" else " (no spoken answer)")
        return true
    }

    /** Two teams: what [bot] was asked to do by a player on its side and is still doing, if anything. For the bot's own decisions. */
    fun orderFor(game: BrawlMinigame, bot: UUID): TeamTalk.Standing? {
        val orders = sessions[game]?.orders ?: return null
        val order = orders[bot] ?: return null
        if (now() >= order.until) { orders.remove(bot); return null }
        return order
    }

    /** Two teams: [bot] was asked to come to a teammate and has found no way there. It tells them. */
    fun cannotReach(game: BrawlMinigame, bot: UUID) {
        val s = sessions[game] ?: return
        if (s.dialogue.teamCant(bot, fighters(game), now()) && BotManager.debug)
            plugin.logger.info("[team] ${Bukkit.getPlayer(bot)?.let { BotManager.displayNameOf(it) }} has no way to where it was asked to be")
    }

    /** [bot] has done what it was asked (the opponent it was sent after is dead, say). */
    fun orderDone(game: BrawlMinigame, bot: UUID) { sessions[game]?.orders?.remove(bot) }
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
                BotManager.isBot(p) && p.uniqueId !in BotManager.standIns, p.gameMode == GameMode.SURVIVAL && !game.respawnManager.isRespawning(p))
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
            if (s.teamUps.enabled) {
                s.teamUps.poll(time, facts.map { TeamUps.Fighter(it.id, it.lives, it.bot, it.fighting, s.dialogue.personalityOf(it.id)) })
                    .forEach { teamEvent(game, s, it, facts, time) }
                // (Debug: what the lobby has against each player, every five seconds while it has anything.)
                if (BotManager.debug && Bukkit.getCurrentTick() % 100 < 5) facts.filter { !it.bot && s.teamUps.heatOf(it.id) >= 1.0 }.takeIf { it.isNotEmpty() }?.let { hot ->
                    plugin.logger.info("[teamups] heat: " + hot.joinToString { "${it.name} ${"%.0f".format(s.teamUps.heatOf(it.id))}" } +
                        (s.teamUps.pact?.let { p -> "; pact of ${p.members.size} on ${facts.find { it.id == p.target }?.name}" }
                            ?: " (${s.teamUps.standing(hot.maxBy { s.teamUps.heatOf(it.id) }.id, time) { id -> facts.find { it.id == id }?.name ?: "?" }})"))
                }
            }
            if (BotManager.debug && !s.castLogged && s.dialogue.personalities().isNotEmpty()) {
                s.castLogged = true
                plugin.logger.info("[chatdbg] personalities: " + s.dialogue.personalities().entries.joinToString {
                    "${Bukkit.getPlayer(it.key)?.name ?: it.key}=${it.value.label}" })
            }
        }
    }

    /**
     * Who [bot] is going after before anybody else: the player its pact is on (see [TeamUps]), or
     * whoever it told the lobby it would get (see [BotDialogue.grudge]). For the bot's target choice.
     */
    fun grudge(game: BrawlMinigame, bot: UUID): UUID? = sessions[game]?.let { it.teamUps.targetOf(bot) ?: it.dialogue.grudge(bot, now()) }

    /** Have [a] and [b] teamed up on somebody for the moment (a Team-Ups match)? They leave each other alone while they have. */
    fun allied(game: BrawlMinigame, a: UUID, b: UUID): Boolean = sessions[game]?.teamUps?.allied(a, b) == true

    /** Debug: how much the lobby of [game] has against [player], and who is teaming on them. */
    fun teamUpsOf(game: BrawlMinigame): TeamUps? = sessions[game]?.teamUps

    /** Debug: counts [amount] against [player] with the lobby of [game], for [reason]. */
    fun heat(game: BrawlMinigame, player: UUID, amount: Double, reason: TeamUps.Reason) = session(game).teamUps.provoke(player, amount, reason)

    private fun teamEvent(game: BrawlMinigame, s: Session, event: TeamUps.Event, facts: List<BotDialogue.Fighter>, time: Long) {
        fun name(id: UUID?) = facts.find { it.id == id }?.name ?: "?"
        // The game itself says nothing about any of this (it used to: "Game> The bots have called a
        // truce and are teaming up on you!", with a bell). A lobby of people does not come with a
        // notice that it has turned on somebody: the player finds out from what the others type,
        // if they type anything, and from everybody coming for them at once.
        when (event) {
            is TeamUps.Warned -> {
                s.dialogue.teamWarned(event.bot, event.target, event.reason, facts, time)
                if (BotManager.debug) plugin.logger.info("[teamups] ${name(event.bot)} warns ${name(event.target)} (${event.reason.name.lowercase()})")
            }
            is TeamUps.Formed -> {
                s.dialogue.teamedUp(event.pact, event.declined, facts, time)
                if (BotManager.debug) plugin.logger.info("[teamups] pact on ${name(event.pact.target)} for ${event.pact.reason.name.lowercase()}: " +
                    "${name(event.pact.leader)} leads ${event.pact.members.filter { it != event.pact.leader }.joinToString { name(it) }}" +
                    (if (event.declined.isEmpty()) "" else "; staying out: ${event.declined.joinToString { name(it) }}"))
            }
            is TeamUps.Left -> {
                s.dialogue.teamLeft(event.pact, event.quitters, facts, time)
                if (BotManager.debug) plugin.logger.info("[teamups] ${event.quitters.joinToString { name(it) }} took the apology and left; " +
                    "still on ${name(event.pact.target)}: ${event.pact.members.joinToString { name(it) }}")
            }
            is TeamUps.Ended -> {
                s.dialogue.teamOver(event.pact, event.how, event.turncoat, event.betrayed, facts, time)
                if (BotManager.debug) plugin.logger.info("[teamups] pact on ${name(event.pact.target)} over (${event.how.name.lowercase()})" +
                    (if (event.turncoat != null) "; ${name(event.turncoat)} turns on ${name(event.betrayed)}" else ""))
            }
        }
    }

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
        // (An answer a teammate is waiting for is typed now, not after whatever else is being said.)
        val startIn = if (reply.urgent) 0 else (s.chatFreeAt - tick).coerceIn(0, 100)
        val typing = BotSlang.typingTicks(reply.text)
        // Whoever answers has to read this line first, so the next one starts a beat after it lands.
        s.chatFreeAt = maxOf(s.chatFreeAt, tick + startIn + typing + READING_TICKS)
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
