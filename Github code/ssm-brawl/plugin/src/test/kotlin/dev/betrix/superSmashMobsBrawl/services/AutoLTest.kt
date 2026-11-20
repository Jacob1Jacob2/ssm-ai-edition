package dev.betrix.superSmashMobsBrawl.services

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID

/** `/auto L`: who has "L <name>" said for them after a kill. */
class AutoLTest : StringSpec({
    val owner = UUID.fromString("784cabf9-a8a2-4d39-a381-e716baaf5cbe")
    val guest = UUID(7, 7)

    "it is on for the owner and off for everybody else until they say otherwise" {
        AutoL.forget()
        AutoL.on(owner) shouldBe true
        AutoL.on(guest) shouldBe false
    }

    "the command turns it off for the owner, and on again the next time" {
        AutoL.forget()
        AutoL.toggle(owner) shouldBe false
        AutoL.on(owner) shouldBe false
        // Somebody else's choice is their own.
        AutoL.on(guest) shouldBe false
        AutoL.toggle(guest) shouldBe true
        AutoL.on(owner) shouldBe false
        AutoL.toggle(owner) shouldBe true
        AutoL.on(owner) shouldBe true
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
            AutoL.on(owner) shouldBe true
            AutoL.toggle(owner) shouldBe false
            AutoL.toggle(guest) shouldBe true
            // The server comes up again and reads what it wrote.
            AutoL.forget()
            AutoL.on(owner) shouldBe true
            AutoL.setup(folder)
            AutoL.on(owner) shouldBe false
            AutoL.on(guest) shouldBe true
            folder.resolve(AutoL.FILE).exists() shouldBe true
        } finally {
            AutoL.forget()
            folder.deleteRecursively()
        }
    }
})
