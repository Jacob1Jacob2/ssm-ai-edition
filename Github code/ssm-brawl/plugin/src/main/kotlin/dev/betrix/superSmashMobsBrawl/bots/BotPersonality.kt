package dev.betrix.superSmashMobsBrawl.bots

import java.util.UUID
import kotlin.random.Random

enum class BotPersonality(val label: String) {
    SALTY("Salty"), CHILL("Chill"), SILENT("Silent"),

    /** Not a personality of its own: every bot in the match is dealt one of the three above. */
    RANDOM("Random");

    companion object {
        /** Salty was saved as "TOXIC" before it was renamed. */
        fun restore(value: String?) =
            if (value.equals("TOXIC", true)) SALTY else entries.find { it.name.equals(value, true) } ?: SILENT

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
                var dealt = listOf(SALTY, CHILL, SILENT).random(random)
                if (dealt == SILENT && quiet >= quietSeats) dealt = if (random.nextBoolean()) SALTY else CHILL
                if (dealt == SILENT) quiet++
                dealt
            }
        }
    }
}
