package dev.betrix.superSmashMobsBrawl.utils

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PolarFishProgressTest : StringSpec({
    "normal fish upgrades require pickups and cap fish and XP independently" {
        val p = PolarFishProgress()
        p.fishCount shouldBe 1; p.cooldown shouldBe 4.0
        p.resolveVolley(true, false); p.stacks shouldBe 0
        p.collect(false); p.progress shouldBe .125f; p.fishCount shouldBe 2; p.cooldown shouldBe 3.8
        repeat(20) { p.collect(false) }
        p.stacks shouldBe 8; p.fishCount shouldBe 8; p.cooldown shouldBe 2.4; p.progress shouldBe 1f
        p.resolveVolley(false, false); p.stacks shouldBe 6
        repeat(20) { p.resolveVolley(false, false) }; p.stacks shouldBe 0
    }
    "red fish have forty five cooldown upgrades without multiplying fish" {
        val p = PolarFishProgress(); p.startUltra()
        p.cooldown shouldBe .5
        repeat(45) { p.collect(true) }
        p.salmonStacks shouldBe 45; p.cooldown shouldBe .05; p.fishCount shouldBe 1; p.progress shouldBe 1f
        p.collect(true); p.cooldown shouldBe .05
        repeat(9) { p.resolveVolley(false, true) }; p.salmonStacks shouldBe 45
        p.resolveVolley(false, true); p.salmonStacks shouldBe 44
    }
    "miss protection uses strict ten percent boundaries and successful volleys reset it" {
        val p = PolarFishProgress(); p.startUltra()
        repeat(4) { p.collect(true) }; p.missesRequired shouldBe 1
        p.collect(true); p.missesRequired shouldBe 2
        repeat(4) { p.collect(true) }; p.progress shouldBe .2f; p.missesRequired shouldBe 2
        p.collect(true); p.missesRequired shouldBe 3
        repeat(2) { p.resolveVolley(false, true) }; p.salmonStacks shouldBe 10
        p.resolveVolley(true, true); p.misses shouldBe 0
        repeat(2) { p.resolveVolley(false, true) }; p.salmonStacks shouldBe 10
        p.resolveVolley(false, true); p.salmonStacks shouldBe 9
    }
    "fish drops and volleys from a previous mode cannot modify the current mode" {
        val p = PolarFishProgress(); repeat(3) { p.collect(false) }
        p.startUltra(); p.collect(false) shouldBe false; p.resolveVolley(false, false)
        p.salmonStacks shouldBe 0; p.stacks shouldBe 3
        p.collect(true); p.endUltra(); p.collect(true) shouldBe false
        p.resolveVolley(false, true); p.stacks shouldBe 3; p.fishCount shouldBe 4
    }
})
