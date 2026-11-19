package dev.betrix.superSmashMobsBrawl.bots

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class RouteEdgesTest : StringSpec({
    val walk = RouteEdge(RouteNode(1, 1024, 0), false, 1.0)
    val hop = RouteEdge(RouteNode(4, 1024, 0), true, 7.0)
    val drop = RouteEdge(RouteNode(2, 576, 5), true, 9.0)
    val base = listOf(walk, hop, drop)
    val far = RouteEdge(RouteNode(9, 1024, 0), true, 18.5, long = true)
    val struckDrop = RouteEdge(drop.node, true, -1.0, long = true)

    "with no long jumps worked out a block has just what the rules give" {
        withLongJumps(base, emptyList(), true) shouldBeSameInstanceAs base
    }

    "a long jump is one more way to go" {
        withLongJumps(base, listOf(far), true) shouldContainExactly listOf(walk, hop, drop, far)
    }

    "a jump of the rules that did not come off when flown is struck out, and is itself no way to go" {
        withLongJumps(base, listOf(far, struckDrop), true) shouldContainExactly listOf(walk, hop, far)
    }

    "a kit that does not jump the ordinary way keeps its own jumps" {
        withLongJumps(base, listOf(far, struckDrop), false) shouldContainExactly listOf(walk, hop, drop, far)
    }

    "walking to a block is never struck out" {
        val struckWalk = RouteEdge(walk.node, true, -1.0, long = true)
        withLongJumps(base, listOf(struckWalk), true).contains(walk) shouldBe true
    }
})