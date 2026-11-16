package dev.betrix.superSmashMobsBrawl.services

/** Zero leaves the original local-server behavior unlimited. Includes loading matches. */
data class MatchCapacity(val maxMatches: Int, val maxFighters: Int) {
    fun rejection(activeSizes: List<Int>, requested: Int): String? = when {
        maxFighters > 0 && requested > maxFighters ->
            "Choose a game with at most $maxFighters players on this server."
        maxMatches > 0 && activeSizes.size >= maxMatches ->
            "All arenas are busy. Please try Play again when a match finishes."
        maxFighters > 0 && activeSizes.sum().toLong() + requested > maxFighters ->
            "There isn't room for that many fighters right now. Choose a smaller game or try again shortly."
        else -> null
    }
}
