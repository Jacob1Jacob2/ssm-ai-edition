package dev.betrix.superSmashMobsBrawl.services

/** Fixed lobby policy, independent of machine capacity and player counts. */
object LobbyRouting {
    val ids = 1..5
    fun arrival(busy: Set<Int>, chooseOccupied: (List<Int>) -> Int = { it.random() }): Int =
        ids.firstOrNull { it !in busy } ?: chooseOccupied(ids.toList())
}
