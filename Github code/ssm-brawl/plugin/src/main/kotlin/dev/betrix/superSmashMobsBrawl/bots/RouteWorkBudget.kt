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

    // Working out an arena's long jumps (ArenaJumps) has an allowance of its own: it is heavy,
    // it is only needed the first time a map is played in a given shape, and it must not eat
    // the time route searches need. It takes what the server has to spare: most of what is left
    // of a tick when the server is idling along, next to nothing when ticks are already long.
    private const val JUMPS_LEAST = 2_000_000L
    private const val JUMPS_MOST = 18_000_000L
    /** Milliseconds of a 50ms tick the long-jump work leaves alone whatever happens. */
    private const val TICK_HEADROOM = 12.0
    private var jumpTick = -1
    private var jumpNanos = 0L
    private var jumpBudget = JUMPS_LEAST

    /**
     * Nanoseconds the long-jump work may still use this tick, when it is shared between [sets]
     * sets of jumps still being worked out (a match with a slime, a wolf and a skeleton in it has
     * three): each asks in turn and none is given the lot.
     */
    fun jumpSlice(sets: Int): Long {
        val left = jumpSlice()
        return if (sets <= 1) left else minOf(left, jumpBudget / sets)
    }

    /** Nanoseconds the long-jump work may still use this tick. */
    fun jumpSlice(): Long {
        val now = Bukkit.getCurrentTick()
        if (now != jumpTick) {
            jumpTick = now
            jumpNanos = 0
            // (The average already has this work in it, so the allowance settles at about half of what is spare.)
            val spare = 50.0 - TICK_HEADROOM - Bukkit.getAverageTickTime()
            jumpBudget = (spare * 1_000_000).toLong().coerceIn(JUMPS_LEAST, JUMPS_MOST)
        }
        return (jumpBudget - jumpNanos).coerceAtLeast(0)
    }
    fun jumpUsed(elapsed: Long) { jumpNanos += elapsed }
}
