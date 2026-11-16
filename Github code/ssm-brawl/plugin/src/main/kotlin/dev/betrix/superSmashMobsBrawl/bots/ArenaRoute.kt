package dev.betrix.superSmashMobsBrawl.bots

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot

/** Feet height is in sixteenths, so slabs, carpets and stairs are not rounded into walls. */
data class RouteNode(val x: Int, val y16: Int, val z: Int) {
    val y get() = y16 / 16.0
    fun distance(other: RouteNode) = hypot((x - other.x).toDouble(), (z - other.z).toDouble()) + abs(y - other.y) * 0.8
}
data class RouteEdge(val node: RouteNode, val jump: Boolean, val cost: Double)
data class RouteStep(val node: RouteNode, val jump: Boolean)

/** Incremental A*: a fixed expansion budget per tick, and a useful partial route if out of budget. */
class ArenaRoute(
    val start: RouteNode,
    val goal: RouteNode,
    private val neighbours: (RouteNode) -> List<RouteEdge>,
    private val limit: Int = 1400,
) {
    private data class Candidate(val node: RouteNode, val cost: Double, val estimate: Double)
    private val open = PriorityQueue(compareBy<Candidate> { it.estimate }.thenBy { it.cost })
    private val costs = hashMapOf(start to 0.0)
    private val parent = hashMapOf<RouteNode, Pair<RouteNode, Boolean>>()
    private var best = start
    var expanded = 0; private set
    var finished = false; private set
    var reached = false; private set
    init { open.add(Candidate(start, 0.0, start.distance(goal))) }

    fun advance(budget: Int = 32, deadlineNanos: Long = Long.MAX_VALUE): List<RouteStep>? {
        if (finished) return route()
        repeat(budget) {
            if (System.nanoTime() >= deadlineNanos) return null
            val next = open.poll()
            if (next == null || expanded >= limit) { finished = true; return route() }
            if (next.cost > (costs[next.node] ?: Double.MAX_VALUE)) return@repeat
            expanded++
            if (next.node.distance(goal) < best.distance(goal)) best = next.node
            if (next.node == goal) { best = goal; reached = true; finished = true; return route() }
            for (edge in neighbours(next.node)) {
                val cost = next.cost + edge.cost
                if (cost >= (costs[edge.node] ?: Double.MAX_VALUE)) continue
                costs[edge.node] = cost
                parent[edge.node] = next.node to edge.jump
                open.add(Candidate(edge.node, cost, cost + edge.node.distance(goal)))
            }
        }
        return null
    }
    /** A bot may move while a search is running. Never send it back over completed steps. */
    fun remainingFrom(x: Double, y: Double, z: Double, result: List<RouteStep>): List<RouteStep> {
        val passed = result.indexOfLast { abs(it.node.y-y)<.7 && hypot(it.node.x+.5-x,it.node.z+.5-z)<1.0 }
        if(passed>=0)return result.drop(passed+1)
        // Knockback can put the bot off this path altogether. Replan from its new floor.
        return if(abs(start.y-y)<1.3 && hypot(start.x+.5-x,start.z+.5-z)<1.8) result else emptyList()
    }
    private fun route(): List<RouteStep> {
        val result = mutableListOf<RouteStep>()
        var at = best
        while (at != start) {
            val prev = parent[at] ?: break
            result.add(RouteStep(at, prev.second)); at = prev.first
        }
        return result.asReversed()
    }
}
