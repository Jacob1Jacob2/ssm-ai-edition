package dev.betrix.superSmashMobsBrawl.bots

import java.io.File
import java.util.Properties
import java.util.UUID

/**
 * What one bot remembers of one player: how many matches they have been in together, and how the
 * last of them went. Everything in it happened; nothing a bot says about "last game" is made up.
 */
data class Acquaintance(
    /** Matches the two have been in together. */
    val matches: Int,
    /** When the last of them ended (milliseconds since 1970). */
    val lastAt: Long,
    /** Times the player killed this bot in that match, and the bot the player. */
    val killedMe: Int,
    val killedThem: Int,
    /** Who of the two won it, if either did. */
    val theyWon: Boolean,
    val iWon: Boolean,
    /** Matches in a row, with this bot in them, that the player has won (that last one included). */
    val theirStreak: Int,
    /** The player talked trash in chat during that match; or was friendly in it. */
    val mouthed: Boolean,
    val nice: Boolean,
    /** The map it was on, as the game shows its name. */
    val map: String,
) {
    /** The player kept killing this bot that match; and the other way round. */
    val farmedMe get() = killedMe >= 3 || (killedMe >= 2 && killedThem == 0)
    val farmedThem get() = killedThem >= 3 || (killedThem >= 2 && killedMe == 0)

    /** Is "last game" still the way to speak of it at [now]? After that it was "earlier", or another day. */
    fun fresh(now: Long) = now - lastAt in 0..BotMemory.FRESH_MS
}

/**
 * What every bot remembers of every player it has played, kept from one match to the next and
 * across restarts. People forget: what is older than a few days is gone.
 */
class BotMemory(private val clock: () -> Long = System::currentTimeMillis) {
    private val known = HashMap<String, Acquaintance>()
    private var file: File? = null

    private fun key(bot: String, player: UUID) = "${bot.lowercase()}|$player"

    /** What the bot called [bot] remembers of [player], or null: never met, or too long ago. */
    @Synchronized fun recall(bot: String, player: UUID): Acquaintance? =
        known[key(bot, player)]?.takeIf { clock() - it.lastAt in 0..REMEMBERED_MS }

    /** A match the two were in has ended: this is how it went between them. Returns what the bot now remembers. */
    @Synchronized fun record(bot: String, player: UUID, killedMe: Int, killedThem: Int, theyWon: Boolean, iWon: Boolean,
                             mouthed: Boolean, nice: Boolean, map: String): Acquaintance {
        val before = recall(bot, player)
        val now = Acquaintance((before?.matches ?: 0) + 1, clock(), killedMe, killedThem, theyWon, iWon,
            if (theyWon) (before?.theirStreak ?: 0) + 1 else 0, mouthed, nice, map.replace(';', ' ').take(40))
        known[key(bot, player)] = now
        return now
    }

    /** Reads what was remembered out of [folder], and keeps it there from now on. */
    @Synchronized fun setup(folder: File) {
        val saved = File(folder, FILE)
        file = saved
        known.clear()
        if (!saved.exists()) return
        val props = Properties()
        runCatching { saved.inputStream().use { props.load(it) } }
        for (name in props.stringPropertyNames()) parse(props.getProperty(name))?.let { if (clock() - it.lastAt in 0..REMEMBERED_MS) known[name] = it }
    }

    @Synchronized fun save() {
        val target = file ?: return
        val props = Properties()
        val now = clock()
        known.filterValues { now - it.lastAt in 0..REMEMBERED_MS }.forEach { (name, a) -> props.setProperty(name, write(a)) }
        target.parentFile?.mkdirs()
        runCatching { target.outputStream().use { props.store(it, "SSM AI Edition - what each bot remembers of the players it has played") } }
    }

    /** Tests: nothing is remembered and nothing is written anywhere. */
    @Synchronized internal fun forget() { known.clear(); file = null }

    companion object {
        /** "Last game" is said of a match for this long. */
        const val FRESH_MS = 45 * 60_000L
        /** And it is remembered at all for this long. */
        const val REMEMBERED_MS = 3 * 24 * 3_600_000L
        /** What players did in their matches: kept out of the public backup (see .gitignore). */
        const val FILE = "bot-memory.properties"
        val shared = BotMemory()

        internal fun write(a: Acquaintance) = listOf(a.matches, a.lastAt, a.killedMe, a.killedThem, a.theyWon, a.iWon,
            a.theirStreak, a.mouthed, a.nice, a.map).joinToString(";")

        internal fun parse(line: String): Acquaintance? = runCatching {
            val p = line.split(';')
            Acquaintance(p[0].toInt(), p[1].toLong(), p[2].toInt(), p[3].toInt(), p[4].toBoolean(), p[5].toBoolean(),
                p[6].toInt(), p[7].toBoolean(), p[8].toBoolean(), p.getOrElse(9) { "" })
        }.getOrNull()
    }
}
