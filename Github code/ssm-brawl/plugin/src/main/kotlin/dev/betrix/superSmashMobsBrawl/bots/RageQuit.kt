package dev.betrix.superSmashMobsBrawl.bots

import kotlin.random.Random

/**
 * Whether a bot walks out of a match it is having a bad time in, the way people do: one match's
 * worth of deciding, with Bukkit kept outside it. It is a setting a host turns on (Misc Settings),
 * and rare on purpose: something that happened every match would be a feature of the mode, and
 * a bore. So most matches are ones nobody will leave whatever happens ([MATCH_CHANCE]), and in the
 * others it takes a Salty or a Toxic bot that is really losing, and then some luck, once.
 *
 * @param enabled the host has it turned on
 */
class RageQuit(private val random: Random, enabled: Boolean) {
    /** Is this a match somebody might walk out of at all? Settled when it starts. */
    private val armed = enabled && random.nextDouble() < MATCH_CHANCE
    private var done = false

    /**
     * A bot has just died. Does it leave? [nature] is how it is (a Chill one never does),
     * [deaths] and [kills] its own so far, [livesLeft] what it still has (somebody who is out is
     * out, not quitting), [byKiller] how often whoever killed it just now has killed it.
     */
    fun snaps(nature: BotPersonality, deaths: Int, kills: Int, livesLeft: Int, byKiller: Int): Boolean {
        if (!armed || done) return false
        if (nature != BotPersonality.SALTY && nature != BotPersonality.TOXIC) return false
        // Having a bad game: dead twice at least, behind on kills, and with something left to throw away.
        if (livesLeft < 1 || deaths < 2 || kills >= deaths) return false
        val chance = (if (nature == BotPersonality.SALTY) 0.5 else 0.25) * (if (byKiller >= 3) 1.5 else 1.0)
        if (random.nextDouble() >= chance) return false
        done = true
        return true
    }

    companion object {
        /** The share of matches in which a bot may leave at all: about one in five, and not all of those come to it. */
        const val MATCH_CHANCE = 0.2
    }
}
