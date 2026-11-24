package dev.betrix.superSmashMobsBrawl.services

import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * "L <name>", said in chat for a player after each of their kills.
 *
 * Off for everybody until they turn it on: in Misc Settings (Game Settings), or with `/auto L`.
 * (It was first on for the server's owner. Some clients say the same line by themselves, and with
 * both on every kill was called twice.)
 */
object AutoL {
    private val chosen = ConcurrentHashMap<UUID, Boolean>()
    private var file: File? = null

    /** Is it on for [player]? Only if they turned it on. */
    fun on(player: UUID): Boolean = chosen[player] ?: false

    /** Switches it for [player] and returns what it is now. */
    fun toggle(player: UUID): Boolean {
        val now = !on(player)
        chosen[player] = now
        save()
        return now
    }

    /** The line itself, about the fighter called [victim]. */
    fun line(victim: String) = "L $victim"

    /** Is [typed] what comes after `/auto` to mean this? `L`, in either case. */
    fun names(typed: String) = typed.trim().equals("l", ignoreCase = true)

    /** Reads what everybody chose out of [folder], and keeps it there from now on. */
    fun setup(folder: File) {
        val saved = File(folder, FILE)
        file = saved
        chosen.clear()
        if (!saved.exists()) return
        val props = Properties()
        saved.inputStream().use { props.load(it) }
        props.stringPropertyNames().forEach { key ->
            runCatching { UUID.fromString(key) }.getOrNull()?.let { chosen[it] = props.getProperty(key).toBoolean() }
        }
    }

    /** Debug and tests: nobody has chosen anything, and nothing is written anywhere. */
    internal fun forget() { chosen.clear(); file = null }

    private fun save() {
        val target = file ?: return
        val props = Properties()
        chosen.forEach { (id, on) -> props.setProperty(id.toString(), on.toString()) }
        target.parentFile?.mkdirs()
        target.outputStream().use { props.store(it, "SSM AI Edition - who says L after a kill (/auto L)") }
    }

    /** A player's choice: kept out of the public backup (see .gitignore). */
    const val FILE = "auto-l.properties"
}
