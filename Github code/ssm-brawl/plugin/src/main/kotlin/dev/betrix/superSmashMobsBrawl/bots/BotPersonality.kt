package dev.betrix.superSmashMobsBrawl.bots

enum class BotPersonality(val label: String) {
    /** Says nothing. */
    SILENT("Silent"),
    /** Friendly: compliments, apologies, calming people down. */
    CHILL("Chill"),
    /** Gets mad when things go wrong for it and complains about all of it. */
    SALTY("Salty"),
    /** Tries to make everyone else mad: gloating, accusations, needling. */
    TOXIC("Toxic"),
    /**
     * Not a personality of its own: every bot is itself, which is one of Chill, Salty and Toxic and
     * the same one in every match ([BotCharacter]). (It used to be dealt afresh for each match.)
     */
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
    }
}
