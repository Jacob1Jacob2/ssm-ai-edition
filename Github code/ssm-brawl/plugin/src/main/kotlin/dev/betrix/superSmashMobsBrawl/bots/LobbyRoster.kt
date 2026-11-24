package dev.betrix.superSmashMobsBrawl.bots

/** Keep random neighbours stable while selections/counts change. Custom names are manual only. */
object LobbyRoster {
    /**
     * [gone]: bots that have just walked out of this lobby (after a match, with a "Quit>" line).
     * They are the last to be picked to fill it up again: somebody who left a moment ago and is
     * back the next is nobody leaving at all.
     */
    fun choose(count: Int, selected: List<String>, current: List<String>, builtIn: List<String>, custom: List<String>,
               gone: List<String> = emptyList()): List<String> {
        val available = (builtIn + custom).associateBy { it.lowercase() }
        val manual = selected.mapNotNull { available[it.lowercase()] }.distinctBy { it.lowercase() }.take(count)
        val built = builtIn.associateBy { it.lowercase() }
        val left = gone.map { it.lowercase() }.toSet()
        val fillers = (current.mapNotNull { built[it.lowercase()] } + builtIn.sortedBy { it.lowercase() in left })
            .filter { candidate -> manual.none { it.equals(candidate, true) } }.distinctBy { it.lowercase() }
        return (manual + fillers).take(count.coerceAtLeast(0))
    }

    /**
     * Who has come into a lobby and who has gone from it, by name, between who stood there
     * [before] and who does [now]: the "Join>" and "Quit>" lines of it. Somebody taken out and put
     * back under the same name (a bot whose difficulty was changed) has done neither.
     */
    fun comings(before: Collection<String>, now: Collection<String>): Pair<List<String>, List<String>> {
        val was = before.map { it.lowercase() }.toSet()
        val here = now.map { it.lowercase() }.toSet()
        return now.filter { it.lowercase() !in was }.distinctBy { it.lowercase() } to before.filter { it.lowercase() !in here }.distinctBy { it.lowercase() }
    }
}
