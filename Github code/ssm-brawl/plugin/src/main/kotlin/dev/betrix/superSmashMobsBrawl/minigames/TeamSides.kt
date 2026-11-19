package dev.betrix.superSmashMobsBrawl.minigames

import java.util.UUID
import kotlin.random.Random

/** The two sides of a team match (2v2, 4v4, 8v8). The side of the player who set the match up is Blue. */
enum class TeamSide(val label: String) {
    BLUE("Blue Team"), RED("Red Team");

    /** The colour this side has wherever its fighters are listed: the scoreboard, the intro, the result. */
    val color: net.kyori.adventure.text.format.NamedTextColor
        get() = if (this == BLUE) net.kyori.adventure.text.format.NamedTextColor.BLUE else net.kyori.adventure.text.format.NamedTextColor.RED
}

object TeamSides {
    data class Fighter(val id: UUID, val name: String)

    /**
     * Splits [fighters] into two even sides.
     *
     * Blue is [host] with the fighters named in [teammates], in that order, for as long as there
     * is room. Seats on Blue that are still free go to fighters nobody named, and only when those
     * run out to fighters named in [opponents]: whoever was picked to be fought stays an opponent
     * while that is possible. Everybody else is Red (with an odd number, Red has the extra one).
     */
    fun assign(
        host: UUID?,
        fighters: List<Fighter>,
        teammates: List<String>,
        opponents: List<String>,
        random: Random = Random.Default,
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
        return fighters.associate { it.id to if (it.id in blue) TeamSide.BLUE else TeamSide.RED }
    }
}
