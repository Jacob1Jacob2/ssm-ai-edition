package dev.betrix.superSmashMobsBrawl.utils

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.util.Vector

class SpinWebLaunchTest : StringSpec({
    "looking up launches upward along the facing direction" {
        val aim = Vector(0.0, 1.0, 1.0).normalize()
        val velocity = SpinWebLaunch.velocity(aim, -45f)
        velocity.y shouldBe (aim.y * 1.8 plusOrMinus .0001)
        velocity.z shouldBe (aim.z * 1.8 plusOrMinus .0001)
        aim.length() shouldBe (1.0 plusOrMinus .0001)
    }
    "looking down vaults up while retaining horizontal heading" {
        val velocity = SpinWebLaunch.velocity(Vector(-.6, -.8, 0.0), 53f)
        velocity.x shouldBe (-.65 plusOrMinus .0001)
        velocity.y shouldBe 1.0
        velocity.z shouldBe 0.0
    }
    "straight down remains finite and straight ahead keeps its forward boost" {
        SpinWebLaunch.velocity(Vector(0, -1, 0), 90f) shouldBe Vector(0, 1, 0)
        SpinWebLaunch.velocity(Vector(0, 0, -1), 0f) shouldBe Vector(0.0, .2, -1.8)
    }
})
