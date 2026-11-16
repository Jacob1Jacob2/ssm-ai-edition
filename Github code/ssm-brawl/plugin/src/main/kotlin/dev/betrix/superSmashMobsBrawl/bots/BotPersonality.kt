package dev.betrix.superSmashMobsBrawl.bots

import java.util.UUID
import kotlin.random.Random

enum class BotPersonality(val label: String) {
    /** Says nothing. */
    SILENT("Silent"),
    /** Friendly: compliments, apologies, calming people down. */
    CHILL("Chill"),
    /** Gets mad when things go wrong for it and complains about all of it. */
    SALTY("Salty"),
    /** Tries to make everyone else mad: gloating, accusations, needling. */
    TOXIC("Toxic"),
    /** Not a personality of its own: every bot in the match is dealt one of the four above. */
    RANDOM("Random");

    companion object {
        /**
         * Reads a saved personality. Before Toxic existed as its own personality the gloating one
         * was called Salty, so a [legacy] save that says Salty means what is now Toxic.
         */
        fun restore(value: String?, legacy: Boolean = false): BotPersonality {
            val saved = entries.find { it.name.equals(value, true) } ?: return SILENT
            return if (legacy && saved == SALTY) TOXIC else saved
        }

        /**
         * Deals a personality to each of [newcomers] for a Random match. [known] are the bots that
         * already have one. A lobby where nobody talks is no fun, so never more than half of all
         * the bots end up Silent (a lone bot always talks).
         */
        fun deal(newcomers: List<UUID>, known: Map<UUID, BotPersonality>, random: Random): Map<UUID, BotPersonality> {
            val quietSeats = (known.size + newcomers.size) / 2
            var quiet = known.values.count { it == SILENT }
            // Shuffled, so the seats that may be Silent do not always go to the first bots in the list.
            return newcomers.shuffled(random).associateWith {
                var dealt = listOf(TOXIC, SALTY, CHILL, SILENT).random(random)
                if (dealt == SILENT && quiet >= quietSeats) dealt = listOf(TOXIC, SALTY, CHILL).random(random)
                if (dealt == SILENT) quiet++
                dealt
            }
        }
    }
}
