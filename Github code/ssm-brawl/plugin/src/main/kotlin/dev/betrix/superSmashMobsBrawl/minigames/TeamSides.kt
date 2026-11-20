package dev.betrix.superSmashMobsBrawl.minigames

import java.util.UUID
import kotlin.random.Random

/** The two sides of a team match (2v2, 4v4, 8v8). The player who sets the match up picks which is theirs ([TeamColor]). */
enum class TeamSide(val label: String) {
    BLUE("Blue Team"), RED("Red Team");

    /** The colour this side has wherever its fighters are listed: the scoreboard, the intro, the result. */
    val color: net.kyori.adventure.text.format.NamedTextColor
        get() = if (this == BLUE) net.kyori.adventure.text.format.NamedTextColor.BLUE else net.kyori.adventure.text.format.NamedTextColor.RED

    val other: TeamSide get() = if (this == BLUE) RED else BLUE
}

/** The colour the player who sets a team match up wants their own side to be: one of the two, or whichever it turns out. */
enum class TeamColor(val label: String) {
    BLUE("Blue"), RED("Red"), RANDOM("Random");

    /** The next one, as the button in Your Team goes through them. */
    fun next(): TeamColor = entries[(ordinal + 1) % entries.size]

    /** The side this is in one match: Random is settled here, once, when the match is made. */
    fun side(random: Random = Random.Default): TeamSide = when (this) {
        BLUE -> TeamSide.BLUE
        RED -> TeamSide.RED
        RANDOM -> if (random.nextBoolean()) TeamSide.BLUE else TeamSide.RED
    }

    companion object {
        /** What a save holds, or Blue: what every team was before there was a choice. */
        fun restore(saved: String?): TeamColor = entries.find { it.name == saved } ?: BLUE
    }
}

/**
 * Who is on which side right now, across every match of two teams that is on: what a fighter's
 * name is coloured by wherever it is shown (over their head, in chat, in the kill feed). Kept apart
 * from the matches themselves so that chat, which is put together off the main thread, can ask.
 */
object TeamRoster {
    private val sides = java.util.concurrent.ConcurrentHashMap<UUID, TeamSide>()

    /** A match of two teams has its fighters: from here on their names are in their side's colour. */
    fun enter(match: Map<UUID, TeamSide>) { sides.putAll(match) }

    /** These fighters are out of their match (it ended, or they left it): their names are their own again. */
    fun leave(fighters: Collection<UUID>) { fighters.forEach(sides::remove) }

    fun sideOf(fighter: UUID): TeamSide? = sides[fighter]
}

object TeamSides {
    data class Fighter(val id: UUID, val name: String)

    /**
     * Splits [fighters] into two even sides.
     *
     * [hostSide] (Blue unless the host picked otherwise) is [host] with the fighters named in
     * [teammates], in that order, for as long as there is room. Seats on it that are still free go
     * to fighters nobody named, and only when those run out to fighters named in [opponents]:
     * whoever was picked to be fought stays an opponent while that is possible. Everybody else is
     * the other side (with an odd number, that one has the extra fighter).
     */
    fun assign(
        host: UUID?,
        fighters: List<Fighter>,
        teammates: List<String>,
        opponents: List<String>,
        random: Random = Random.Default,
        hostSide: TeamSide = TeamSide.BLUE,
    ): Map<UUID, TeamSide> {
        if (fighters.size < 2) return emptyMap()
        val seats = fighters.size / 2
        val blue = LinkedHashSet<UUID>()
        fighters.firstOrNull { it.id == host }?.let { blue += it.id }
        fun named(names: List<String>) = names.mapNotNull { name -> fighters.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        for (fighter in named(teammates)) if (blue.size < seats) blue += fighter.id
        val wanted = named(opponents).map { it.id }.toSet()
        val free = fighters.filter { it.id !in blue }
        val fill = free.filter { it.id !in wanted }.shuffled(random) + free.filter { it.id in wanted }.shuffled(random)
        for (fighter in fill) if (blue.size < seats) blue += fighter.id
        // ("blue" is the host's side, whatever colour that is this time.)
        return fighters.associate { it.id to if (it.id in blue) hostSide else hostSide.other }
    }
}
