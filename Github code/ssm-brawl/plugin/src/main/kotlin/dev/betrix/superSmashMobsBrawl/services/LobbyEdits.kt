package dev.betrix.superSmashMobsBrawl.services

import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A note, in the plugin's folder, of when the lobby was last open for building.
 *
 * The lobby's world files are written again every time the server runs, with nothing in them
 * changed but times: saved to the backup on every push, they were half of what it grew by. So the
 * backup (backup-to-github.ps1) leaves them out, and takes them only when this note has changed
 * since it last did. The lobby is changed in one way only, an operator's build mode
 * ([LobbyBuildService]), and that writes the note: when it is switched on, when it is switched off,
 * and whenever the lobby is saved by hand.
 *
 * The note holds a time and nothing else: it is in the public backup.
 */
object LobbyEdits {
    const val FILE = "lobby-edited.txt"
    @Volatile private var folder: File? = null

    /** Where the note is kept: the plugin's data folder. */
    fun setup(dataFolder: File) { folder = dataFolder }

    /** The lobby may differ from the backup's copy from now on (or has just been saved with what was built). */
    fun mark(now: Instant = Instant.now()) {
        val target = folder ?: return
        runCatching { target.mkdirs(); File(target, FILE).writeText(text(now)) }
    }

    /** What the note says. Two marks in the same second are the same note; a later one is a different one. */
    fun text(now: Instant): String =
        "The lobby was last open for building at ${now.truncatedTo(ChronoUnit.SECONDS)}.\n" +
            "backup-to-github.ps1 saves the lobby's world files when this file has changed.\n"
}
