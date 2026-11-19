package dev.betrix.superSmashMobsBrawl.services

/** How the fighters of a match stand to each other, in the order of the buttons in Game Settings. */
enum class MatchMode {
    /** Everybody for themselves. */
    FFA,
    /** A free-for-all in which the bots may team up on a player who gets on their nerves. */
    TEAM_UPS,
    /** Every bot on one team against the player. */
    BOTS_VS_YOU,
    /** Two even teams: 2v2, 4v4 or 8v8. */
    TEAMS;

    /**
     * What a match of [players] fighters is called in this mode. One fighter alone is the sandbox,
     * two are a duel whatever the mode, and two teams take four or more.
     */
    fun label(players: Int): String = when {
        players <= 1 -> "Sandbox"
        players == 2 -> "1v1"
        this == BOTS_VS_YOU -> "${players - 1}v1"
        this == TEAMS && players >= 4 -> "${players / 2}v${players - players / 2}"
        this == TEAM_UPS -> "Team-Ups"
        else -> "FFA"
    }
}
