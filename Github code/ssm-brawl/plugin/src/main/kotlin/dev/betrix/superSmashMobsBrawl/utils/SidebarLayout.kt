package dev.betrix.superSmashMobsBrawl.utils

/**
 * How the match sidebar is laid out for a given number of fighters still in it.
 *
 * Everything is one to a line, in one column: the match's four settings (items, smash,
 * difficulty, mode) each on a line of their own, and under them every fighter on theirs. That
 * keeps the board narrow. The client draws fifteen lines of a sidebar and no more, so a crowded
 * match first gives up its two blank lines, and what is still over fifteen is drawn under the box
 * as lines of the last one (see SidebarOverflow). Nothing and nobody is dropped or put side by side.
 */
object SidebarLayout {
    /** What the client draws of a sidebar. */
    const val LINES = 15

    /** The settings, one to a line. */
    const val SETTINGS = 4

    /** The most lines that are drawn under the box: a match of sixteen. */
    const val MAX_OVERFLOW = 8

    data class Shape(
        /** A blank line under the "Players" heading and one above the footer. */
        val blanks: Boolean,
        /** Lines that do not fit the fifteen and are drawn under the box. */
        val overflow: Int,
    ) {
        /** Every line there is for [fighters] fighters: settings, divider, heading, fighters, footer, and the blanks. */
        fun rows(fighters: Int): Int = SETTINGS + 2 + fighters + 1 + if (blanks) 2 else 0
    }

    fun of(fighters: Int): Shape {
        val roomy = Shape(true, 0)
        if (roomy.rows(fighters) <= LINES) return roomy
        val tight = Shape(false, 0)
        return tight.copy(overflow = (tight.rows(fighters) - LINES).coerceIn(0, MAX_OVERFLOW))
    }
}
