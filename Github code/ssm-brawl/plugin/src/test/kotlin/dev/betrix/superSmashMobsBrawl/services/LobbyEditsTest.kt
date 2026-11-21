package dev.betrix.superSmashMobsBrawl.services

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.time.Instant

/** The note the backup goes by to know that the lobby's world files are worth saving again. */
class LobbyEditsTest : StringSpec({
    val morning = Instant.parse("2026-11-20T09:15:30.250Z")

    "the note says when, and nothing about who" {
        val note = LobbyEdits.text(morning)
        note.lines().first() shouldBe "The lobby was last open for building at 2026-11-20T09:15:30Z."
        // It is in a public backup: a time, and no name or address.
        Regex("[0-9a-f]{8}-[0-9a-f]{4}").containsMatchIn(note) shouldBe false
    }

    "using build mode again changes the note, which is what the backup looks for" {
        LobbyEdits.text(morning) shouldBe LobbyEdits.text(morning.plusMillis(500))
        LobbyEdits.text(morning) shouldNotBe LobbyEdits.text(morning.plusSeconds(1))
    }

    "the note is written into the plugin's folder, and written over there" {
        val folder = Files.createTempDirectory("lobby-edits-test").toFile()
        try {
            LobbyEdits.setup(folder)
            val file = folder.resolve(LobbyEdits.FILE)
            file.exists() shouldBe false
            LobbyEdits.mark(morning)
            file.readText() shouldBe LobbyEdits.text(morning)
            LobbyEdits.mark(morning.plusSeconds(90))
            file.readText() shouldBe LobbyEdits.text(morning.plusSeconds(90))
            folder.listFiles()!!.size shouldBe 1
        } finally {
            folder.deleteRecursively()
        }
    }
})
