package dev.betrix.superSmashMobsBrawl.bots

import org.bukkit.Bukkit

/** Shared main-thread allowance prevents 16 bots each consuming a full server tick on A*. */
object RouteWorkBudget {
    private const val PER_TICK = 6_000_000L
    private const val PER_SEARCH = 2_500_000L
    private var serverTick = -1
    private var nanos = 0L

    private fun left(): Long {
        val now = Bukkit.getCurrentTick()
        if(now != serverTick) { serverTick=now; nanos=0 }
        return PER_TICK - nanos
    }
    fun available(): Boolean = left() > 0

    /** Nanoseconds one search may use right now: a few bots can finish a route this tick, the rest wait a tick. */
    fun slice(): Long = left().coerceIn(0, PER_SEARCH)
    fun used(elapsed: Long) { nanos += elapsed }
}
