package dev.betrix.superSmashMobsBrawl.bots

/** Keep random neighbours stable while selections/counts change. Custom names are manual only. */
object LobbyRoster {
    fun choose(count: Int, selected: List<String>, current: List<String>, builtIn: List<String>, custom: List<String>): List<String> {
        val available = (builtIn + custom).associateBy { it.lowercase() }
        val manual = selected.mapNotNull { available[it.lowercase()] }.distinctBy { it.lowercase() }.take(count)
        val built = builtIn.associateBy { it.lowercase() }
        val fillers = (current.mapNotNull { built[it.lowercase()] } + builtIn)
            .filter { candidate -> manual.none { it.equals(candidate, true) } }.distinctBy { it.lowercase() }
        return (manual + fillers).take(count.coerceAtLeast(0))
    }
}
