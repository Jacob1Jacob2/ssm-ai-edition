package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.bots.BotManager
import dev.betrix.superSmashMobsBrawl.events.BrawlDamageType
import dev.betrix.superSmashMobsBrawl.events.BrawlDeathEvent
import dev.betrix.superSmashMobsBrawl.events.DeathReason
import dev.betrix.superSmashMobsBrawl.events.HazardType
import java.util.UUID
import java.util.Locale
import dev.betrix.superSmashMobsBrawl.utils.CombatHistory
import dev.betrix.superSmashMobsBrawl.utils.CombatHistory.Hit
import java.util.concurrent.ConcurrentHashMap
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * SSM AI Edition: combat feedback in the classic SSM style.
 * - Death> X killed by Y with Z.   (to everyone in the game)
 * - the victim's damage breakdown: #1: Name [19.2] [Damage] [8.2 Seconds Prior]
 * - a recharge bar on the action bar while holding an ability item
 */
object CombatLogService : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val lang: LangService by inject()
    private val minigameService: MinigameService by inject()

    private val hits = ConcurrentHashMap<UUID, MutableList<Hit>>()
    private val shownReadyNotices = ConcurrentHashMap<UUID, Pair<String, Long>>()
    private val recentAbility = ConcurrentHashMap<UUID, Pair<String, Long>>()

    private const val BAR_LENGTH = 24

    private val abilityKey by lazy { NamespacedKey(plugin, "abilityId") }

    fun setup() {
        BrawlDeathEvent.listen { onDeath(player, reason) }
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { rechargeBars() }, 20L, 2L)
    }

    private fun t(s: String, color: TextColor, bold: Boolean = false) =
        Component.text(s, color).decoration(TextDecoration.BOLD, bold)

    private fun prefix(word: String) = t("$word> ", NamedTextColor.BLUE)

    // ------------------------------------------------------------------ tracking

    /** Called when a player uses an ability, so its damage can be credited by name. */
    fun abilityUsed(player: Player, abilityName: String) {
        recentAbility[player.uniqueId] = abilityName to System.currentTimeMillis()
        BotConversationService.abilityUsed(player, abilityName)
    }

    /** Called by the combat manager whenever damage is actually applied. */
    fun recordDamage(victim: Player, attacker: Player?, amount: Double, type: BrawlDamageType?, sourceName: String? = null) {
        if (attacker == null || !amount.isFinite() || amount <= 0) return
        val now = System.currentTimeMillis()
        val label =
            if (sourceName != null) sourceName
            else if (type == null || type == BrawlDamageType.MeleeAttack) "Attack"
            else
                recentAbility[attacker.uniqueId]?.takeIf { now - it.second < 8_000 }?.first
                    ?: when (type) {
                        BrawlDamageType.Projectile -> "Projectile"
                        BrawlDamageType.Explosion -> "Explosion"
                        BrawlDamageType.Slam -> "Slam"
                        BrawlDamageType.MeleeAttack -> "Attack"
                    }
        append(victim, Hit(attacker.uniqueId, BotManager.displayNameOf(attacker), amount, label, now))
        // (For a balance run: every hit, by what it was done with, as a share of the victim's full bar.)
        val stats = dev.betrix.superSmashMobsBrawl.items.ItemStats
        if (stats.on) stats.row(minigameService.getMinigameForPlayer(victim), "damage", stats.itemOf(label), attacker, victim,
            stats.percent(victim, amount), label)
        // A bot remembers who hit it where, and what it did back: how it tells a trap from a fight when it dies.
        BotManager.brainOf(victim)?.struck(attacker)
        BotManager.brainOf(attacker)?.dealt(victim, amount)
        BotConversationService.damage(victim, attacker, amount, label)
    }

    /** Who damaged [victim] within the last [withinMs] milliseconds. */
    fun recentAttackers(victim: Player, withinMs: Long): Set<UUID> {
        val list = hits[victim.uniqueId] ?: return emptySet()
        val now = System.currentTimeMillis()
        return synchronized(list) { list.filter { it.amount > 0 && now - it.time <= withinMs }.map { it.attackerId }.toSet() }
    }

    /** Zero-damage launches can earn a void kill without inventing a damage contribution. */
    fun recordKnockback(victim: Player, attacker: Player, sourceName: String) {
        if(attacker==victim)return
        append(victim, Hit(attacker.uniqueId, BotManager.displayNameOf(attacker), 0.0, sourceName, System.currentTimeMillis(), true))
        val stats = dev.betrix.superSmashMobsBrawl.items.ItemStats
        if (stats.on) stats.row(minigameService.getMinigameForPlayer(victim), "knock", stats.itemOf(sourceName), attacker, victim, extra = sourceName)
        BotManager.brainOf(victim)?.struck(attacker)
    }

    private fun append(victim: Player, hit: Hit) {
        val list = hits.getOrPut(victim.uniqueId) { mutableListOf() }
        synchronized(list) {
            list += hit
            list.removeIf { hit.time - it.time > CombatHistory.LOG_WINDOW_MS }
        }
    }

    // ------------------------------------------------------------------ deaths

    private fun onDeath(victim: Player, reason: DeathReason) {
        val game = minigameService.getMinigameForPlayer(victim) ?: return
        val now = System.currentTimeMillis()
        val log = hits.remove(victim.uniqueId)?.let { synchronized(it) { it.toList() } } ?: emptyList()
        val summary = CombatHistory.summarize(log, victim.uniqueId, now)
        val lastHit = summary.killer
        BotConversationService.death(game, victim, lastHit?.attackerId, lastHit?.label ?:
            if ((reason as? DeathReason.Hazard)?.type == HazardType.VOID) "Void" else "the environment")
        val recipients = game.allPlayers().mapNotNull {it.player}.filter { !BotManager.isBot(it) }
        // (For a balance run: who died, by whom, and with what; "Void" or the cause when by nobody.)
        dev.betrix.superSmashMobsBrawl.items.ItemStats.let { stats ->
            if (stats.on) stats.row(game, "death", stats.itemOf(lastHit?.label), lastHit?.attackerId?.let(Bukkit::getPlayer), victim,
                extra = lastHit?.label ?: if ((reason as? DeathReason.Hazard)?.type == HazardType.VOID) "Void" else "the environment")
        }

        lastHit?.let { hit ->
            Bukkit.getPlayer(hit.attackerId)?.takeIf {
                it != victim && minigameService.getMinigameForPlayer(it) === game
            }?.let { killer ->
                if (game.claimFirstBlood()) {
                    // (Names are in the colour of the fighter's side in a match of two teams, and yellow otherwise.)
                    val firstBlood = prefix("Game").append(t(BotManager.displayNameOf(killer), game.nameColor(killer.uniqueId)))
                        .append(t(" drew ", NamedTextColor.GRAY))
                        .append(t("First Blood!", NamedTextColor.YELLOW))
                    game.allPlayers().mapNotNull { it.player }.filter { !BotManager.isBot(it) }.forEach {
                        it.sendMessage(firstBlood)
                        it.playSound(it.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1f)
                    }
                }
                LobbyLeaderboardService.recordKill(killer)
                dev.betrix.superSmashMobsBrawl.events.BrawlKillEvent(killer, victim).callEvent()
                // "L <name>" for whoever has it on (/auto L turns it off: some clients say it themselves).
                if (AutoL.on(killer.uniqueId)) {
                    val taunt = ClassicChatService.message(killer, Component.text(AutoL.line(BotManager.displayNameOf(victim))))
                    game.allPlayers().mapNotNull { it.player }.forEach { it.sendMessage(taunt) }
                    // (A bot it was said to remembers it: see BotMemory.)
                    BotConversationService.taunted(killer, victim)
                }
            }
        }

        // Wait a tick so the lives counter has been updated for this death.
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable {
                val name = t(BotManager.displayNameOf(victim), game.nameColor(victim.uniqueId))
                val message =
                    prefix("Death").append(name).append(
                        if (lastHit != null) {
                            t(" killed by ", NamedTextColor.GRAY)
                                .append(t(lastHit.attacker + if(summary.assists>0) " + ${summary.assists}" else "", game.nameColor(lastHit.attackerId)))
                                .append(t(" with ", NamedTextColor.GRAY))
                                .append(t(lastHit.label, NamedTextColor.GREEN))
                                .append(t(".", NamedTextColor.GRAY))
                        } else if ((reason as? DeathReason.Hazard)?.type == HazardType.VOID) {
                            t(" killed by ", NamedTextColor.GRAY)
                                .append(t("Void", NamedTextColor.YELLOW)).append(t(".", NamedTextColor.GRAY))
                        } else {
                            t(
                                when ((reason as? DeathReason.Hazard)?.type) {
                                    HazardType.VOID -> " killed by Void."
                                    HazardType.LAVA -> " burned in lava."
                                    HazardType.LIQUID -> " fell into water."
                                    null -> " died."
                                },
                                NamedTextColor.GRAY,
                            )
                        }
                    )
                recipients.filter {it.isOnline}.forEach { it.sendMessage(message) }

                if (BotManager.isBot(victim) || !victim.isOnline) return@Runnable
                var rowOffset=0
                if(lastHit==null && (reason as? DeathReason.Hazard)?.type==HazardType.VOID) {
                    rowOffset=1
                    victim.sendMessage(t("#1: ",NamedTextColor.GREEN).append(t("Void",NamedTextColor.YELLOW))
                        .append(t(" [",NamedTextColor.GRAY)).append(t("Infinite",NamedTextColor.YELLOW))
                        .append(t("] [",NamedTextColor.GRAY)).append(t("-",NamedTextColor.YELLOW))
                        .append(t("] [",NamedTextColor.GRAY)).append(t("0.0 Seconds Prior",NamedTextColor.GREEN))
                        .append(t("]",NamedTextColor.GRAY)))
                }
                // Final killer contribution first, then latest contributors, including self-damage.
                summary.rows.forEachIndexed { i, row ->
                    val last=row.last
                    victim.sendMessage(t("#${i+1+rowOffset}: ", NamedTextColor.GREEN)
                        .append(t(last.attacker, game.nameColor(last.attackerId)))
                        .append(t(" [", NamedTextColor.GRAY))
                        .append(t("%.1f".format(Locale.ROOT,row.total), NamedTextColor.YELLOW))
                        .append(t("] [", NamedTextColor.GRAY))
                        .append(t(last.label, NamedTextColor.GREEN))
                        .append(t("] [", NamedTextColor.GRAY))
                        .append(t("${"%.1f".format(Locale.ROOT,(now-last.time)/1000.0)} Seconds Prior", NamedTextColor.GREEN))
                        .append(t("]", NamedTextColor.GRAY)))
                }
                victim.sendMessage(t("You have died!", NamedTextColor.RED, bold = true))
                val lives = game.teamManager.findTeamOf(victim)?.metadata?.get("stocks") as? Int
                if (lives != null) {
                    victim.sendMessage(
                        t(
                            when {
                                lives <= 0 -> "You are out of lives!"
                                lives == 1 -> "You have 1 life left!"
                                else -> "You have $lives lives left!"
                            },
                            NamedTextColor.RED,
                            bold = true,
                        )
                    )
                }
            },
            1L,
        )
    }

    // ------------------------------------------------------------------ recharge bar

    fun showRecharge(p:Player,name:String,progress:Double,seconds:Double) {
        val filled=(progress*BAR_LENGTH).toInt().coerceIn(0,BAR_LENGTH)
        EnergyHud.message(p,t(name+" ",NamedTextColor.WHITE,bold=true)
            .append(t("▌".repeat(filled),NamedTextColor.GREEN))
            .append(t("▌".repeat(BAR_LENGTH-filled),NamedTextColor.RED))
            .append(t(" ${"%.1f".format(seconds)} Seconds",NamedTextColor.WHITE)))
    }
    private fun rechargeBars() {
        Bukkit.getOnlinePlayers().forEach { p ->
            if (BotManager.isBot(p)) return@forEach
            if (minigameService.getMinigameForPlayer(p)?.getState() != dev.betrix.superSmashMobsBrawl.minigames.MinigameState.IN_PROGRESS) return@forEach
            val item = p.inventory.itemInMainHand
            if (!item.hasItemMeta()) return@forEach
            val smashKey=item.itemMeta.persistentDataContainer.get(dev.betrix.superSmashMobsBrawl.smash.SmashItems.POWER,PersistentDataType.STRING)
            if(smashKey!=null) {
                val session=dev.betrix.superSmashMobsBrawl.smash.SmashService.session(p) ?: return@forEach
                val power=session.powers[smashKey] ?: return@forEach
                val cooldown=session.moves.cooldown(power)
                val remaining=(power.nextUse-session.arena.tick).coerceAtLeast(0)
                if(cooldown>1 && remaining>0)showRecharge(p,power.spec.name,1.0-remaining.toDouble()/cooldown,remaining/20.0)
                return@forEach
            }
            val id = item.itemMeta.persistentDataContainer.get(abilityKey, PersistentDataType.STRING) ?: return@forEach
            val ability = KitService.getKitForPlayer(p)?.getAbility(id) ?: return@forEach

            val progress = ability.rechargeProgress()
            if (ability.usesEnergy || progress >= 1.0) {
                val notice = id to ability.readyNoticeUntil
                if (!ability.usesEnergy && System.currentTimeMillis() < ability.readyNoticeUntil &&
                    shownReadyNotices[p.uniqueId] != notice) {
                    // One packet: the client holds it for 2 seconds, then fades for 1 second.
                    // Re-sending resets the fade; clearing replaces it abruptly.
                    dev.betrix.superSmashMobsBrawl.services.EnergyHud.message(p, t("${ability.displayName} Recharged", NamedTextColor.GREEN, bold = true))
                    shownReadyNotices[p.uniqueId] = notice
                }
                return@forEach
            }
            showRecharge(p,ability.displayName,progress,ability.rechargeSecondsLeft())
        }
    }
}
