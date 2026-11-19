package dev.betrix.superSmashMobsBrawl.utils

/**
 * How the match sidebar is laid out for a given number of fighters still in it.
 *
 * A sidebar shows fifteen lines and no more. The match's settings (items, smash, difficulty, mode)
 * and every fighter are always on it: a crowded match first puts the settings two to a line, then
 * the fighters in two columns. Nothing is ever dropped to make room.
 */
object SidebarLayout {
    /** What the client draws of a sidebar. */
    const val LINES = 15

    /** Most fighters that are listed one to a line under the settings at their full four lines. */
    const val ROOMY = 6

    /** Most lines of fighters there is room for once the settings are two to a line. */
    const val FIGHTER_LINES = 8

    data class Shape(
        /** Fighters to a line: one, or two columns side by side. */
        val columns: Int,
        /** The four settings on two lines instead of four. */
        val compactSettings: Boolean,
    ) {
        /** Lines above the fighters: the settings, the divider under them, the "Players" heading and the blank after it. */
        val head: Int get() = (if (compactSettings) 2 else 4) + 1 + 2

        /** Lines under the fighters: a blank and the footer. */
        val foot: Int get() = 2
    }

    fun of(fighters: Int): Shape = Shape(if (fighters > FIGHTER_LINES) 2 else 1, fighters > ROOMY)

    /**
     * The two columns of a crowded match, as indexes into the fighters in the order they are
     * listed: left to right, top to bottom, so the order reads the way a single column would.
     */
    fun columns(fighters: Int): Pair<List<Int>, List<Int>> =
        (0 until fighters).filter { it % 2 == 0 } to (0 until fighters).filter { it % 2 == 1 }
}
