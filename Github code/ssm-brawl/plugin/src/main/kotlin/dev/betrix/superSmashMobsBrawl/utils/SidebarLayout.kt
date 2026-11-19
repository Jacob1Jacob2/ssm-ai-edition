package dev.betrix.superSmashMobsBrawl.utils

/**
 * How the match sidebar is laid out for a given number of fighters still in it.
 *
 * The fighters are always one to a line, in one column, under the match's settings (items,
 * smash, difficulty, mode). The client draws fifteen lines of a sidebar and no more, so a crowded
 * match makes room step by step: the settings go two to a line, the blank lines go, and what is
 * still over fifteen is drawn under the box as lines of the last one (see SidebarOverflow).
 * Nothing and nobody is dropped.
 */
object SidebarLayout {
    /** What the client draws of a sidebar. */
    const val LINES = 15

    /** The most lines that are drawn under the box: a match of sixteen. */
    const val MAX_OVERFLOW = 6

    data class Shape(
        /** The four settings on four lines, or two to a line on two. */
        val settingsLines: Int,
        /** A blank line under the "Players" heading and one above the footer. */
        val blanks: Boolean,
        /** Lines that do not fit the fifteen and are drawn under the box. */
        val overflow: Int,
    ) {
        /** Every line there is for [fighters] fighters: settings, divider, heading, fighters, footer, and the blanks. */
        fun rows(fighters: Int): Int = settingsLines + 2 + fighters + 1 + if (blanks) 2 else 0
    }

    fun of(fighters: Int): Shape {
        val roomy = Shape(4, true, 0)
        if (roomy.rows(fighters) <= LINES) return roomy
        val compact = Shape(2, true, 0)
        if (compact.rows(fighters) <= LINES) return compact
        val tight = Shape(2, false, 0)
        return tight.copy(overflow = (tight.rows(fighters) - LINES).coerceIn(0, MAX_OVERFLOW))
    }
}
