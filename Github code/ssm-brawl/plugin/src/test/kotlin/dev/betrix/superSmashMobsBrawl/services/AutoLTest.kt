package dev.betrix.superSmashMobsBrawl.services

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID

/** Auto L and the other Misc Settings: what is on for whom, and that a choice is kept. */
class AutoLTest : StringSpec({
    val owner = UUID.fromString("784cabf9-a8a2-4d39-a381-e716baaf5cbe")
    val guest = UUID(7, 7)

    "it is off for everybody, the owner included, until they turn it on" {
        AutoL.forget()
        AutoL.on(owner) shouldBe false
        AutoL.on(guest) shouldBe false
    }

    "turning it on is one's own choice, and turning it off again the next time" {
        AutoL.forget()
        AutoL.toggle(owner) shouldBe true
        AutoL.on(owner) shouldBe true
        // Somebody else's choice is their own.
        AutoL.on(guest) shouldBe false
        AutoL.toggle(guest) shouldBe true
        AutoL.toggle(owner) shouldBe false
        AutoL.on(owner) shouldBe false
        AutoL.on(guest) shouldBe true
    }

    "L or l is what the command takes, and the line is the one it always was" {
        listOf("L", "l", " L ").all(AutoL::names) shouldBe true
        listOf("", "W", "ll", "L please").none(AutoL::names) shouldBe true
        AutoL.line("DinoBurger27") shouldBe "L DinoBurger27"
    }

    "a choice is still there after a restart" {
        val folder = Files.createTempDirectory("auto-l-test").toFile()
        try {
            AutoL.setup(folder)
            AutoL.on(owner) shouldBe false
            AutoL.toggle(owner) shouldBe true
            AutoL.toggle(guest) shouldBe true
            AutoL.toggle(guest) shouldBe false
            // The server comes up again and reads what it wrote.
            AutoL.forget()
            AutoL.on(owner) shouldBe false
            AutoL.setup(folder)
            AutoL.on(owner) shouldBe true
            AutoL.on(guest) shouldBe false
            folder.resolve(AutoL.FILE).exists() shouldBe true
        } finally {
            AutoL.forget()
            folder.deleteRecursively()
        }
    }

    "bot rage quits are off and the resource pack is on until somebody says otherwise, and that is kept too" {
        val folder = Files.createTempDirectory("misc-settings-test").toFile()
        try {
            MiscSettings.forget()
            MiscSettings.on(guest, MiscSettings.Option.RAGE_QUITS) shouldBe false
            MiscSettings.on(guest, MiscSettings.Option.RESOURCE_PACK) shouldBe true
            MiscSettings.setup(folder)
            MiscSettings.set(guest, MiscSettings.Option.RAGE_QUITS, true)
            MiscSettings.set(owner, MiscSettings.Option.RESOURCE_PACK, false)
            MiscSettings.forget()
            MiscSettings.on(guest, MiscSettings.Option.RAGE_QUITS) shouldBe false
            MiscSettings.setup(folder)
            MiscSettings.on(guest, MiscSettings.Option.RAGE_QUITS) shouldBe true
            MiscSettings.on(guest, MiscSettings.Option.RESOURCE_PACK) shouldBe true
            MiscSettings.on(owner, MiscSettings.Option.RESOURCE_PACK) shouldBe false
            MiscSettings.on(owner, MiscSettings.Option.RAGE_QUITS) shouldBe false
        } finally {
            MiscSettings.forget()
            folder.deleteRecursively()
        }
    }
})
