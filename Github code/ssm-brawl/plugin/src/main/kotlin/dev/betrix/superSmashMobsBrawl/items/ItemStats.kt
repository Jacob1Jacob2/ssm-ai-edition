package dev.betrix.superSmashMobsBrawl.items

import dev.betrix.superSmashMobsBrawl.bots.BotManager
import dev.betrix.superSmashMobsBrawl.minigames.BrawlMinigame
import java.io.BufferedWriter
import java.io.File
import java.util.Locale
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * A record of what the items did in a run of bot matches, one line an event: what dropped, who
 * picked it up, every use, every hit and what it took off whom, every death and what it was by,
 * and who won. [ItemReport] adds it up.
 *
 * Nothing is recorded unless a run has opened a file ([dev.betrix.superSmashMobsBrawl.bots.BalanceRunner]):
 * every place that reports here asks [on] first, so a server that is not being measured does no
 * more than read one field.
 */
object ItemStats {
    const val HEADER = "match,tick,event,item,actor,target,value,extra"

    @Volatile private var out: BufferedWriter? = null

    /** Is a run being recorded? */
    val on: Boolean get() = out != null

    fun open(file: File) {
        close()
        file.parentFile?.mkdirs()
        out = file.bufferedWriter(Charsets.UTF_8).also { it.write(HEADER); it.newLine() }
    }

    fun close() { out?.let { runCatching { it.close() } }; out = null }
    fun flush() { runCatching { out?.flush() } }

    private val byLabel = ArenaItem.entries.associateBy { it.label }

    /** The item a hit or a death is labelled with, when the label is an item's name ("Frag Grenade") and not an ability's. */
    fun itemOf(label: String?): ArenaItem? = byLabel[label]

    private fun clean(text: String) = text.replace(',', ';').replace('\n', ' ').replace('\r', ' ')
    private fun number(value: Double) =
        if (value == Math.rint(value) && kotlin.math.abs(value) < 1e12) value.toLong().toString() else "%.3f".format(Locale.ROOT, value)

    /** One event as a line of the file. */
    fun line(match: String, tick: Long, event: String, item: String = "", actor: String = "", target: String = "",
             value: Double = 0.0, extra: String = ""): String =
        listOf(clean(match), tick.toString(), event, item, clean(actor), clean(target), number(value), clean(extra)).joinToString(",")

    /** Writes one event of [game] (nothing, when nothing is being recorded or there is no game). */
    fun row(game: BrawlMinigame?, event: String, item: ArenaItem? = null, actor: Player? = null, target: Player? = null,
            value: Double = 0.0, extra: String = "") {
        val writer = out ?: return
        game ?: return
        runCatching {
            writer.write(line(game.displayCode, Bukkit.getCurrentTick().toLong(), event, item?.id ?: "",
                actor?.let(BotManager::displayNameOf) ?: "", target?.let(BotManager::displayNameOf) ?: "", value, extra))
            writer.newLine()
        }
    }

    /** [amount] of health as a percentage of [player]'s full bar: the unit the items are described in. */
    fun percent(player: Player, amount: Double): Double =
        amount / (player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0) * 100.0
}
