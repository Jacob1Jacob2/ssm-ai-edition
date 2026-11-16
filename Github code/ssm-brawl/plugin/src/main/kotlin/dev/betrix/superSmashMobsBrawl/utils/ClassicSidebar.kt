package dev.betrix.superSmashMobsBrawl.utils

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object ClassicSidebar {
    const val TITLE = "SUPER SMASH MOBS"
    const val COUNTDOWN_TITLE = "AI Edition"
    const val DIVIDER = "════════════"

    /** The reference wipes gold to yellow, then yellow to gold, with one white leading letter. */
    @JvmOverloads fun frameAtTick(tick: Long, heading: String = TITLE): Int =
        ((tick / 3) % ((heading.count {it != ' '} + 1) * 2)).toInt()

    fun introTitle(tick: Int): Component {
        val swapped = (tick * 3 / 20) % 2 == 1
        return Component.text("SSM AI Edition", if(swapped) NamedTextColor.WHITE else NamedTextColor.GOLD, TextDecoration.BOLD)
            .append(Component.text(" - ", NamedTextColor.WHITE, TextDecoration.BOLD))
            .append(Component.text(TITLE, if(swapped) NamedTextColor.GOLD else NamedTextColor.WHITE, TextDecoration.BOLD))
    }

    @JvmOverloads fun title(frame: Int, heading: String = TITLE): Component {
        val sweepFrames = heading.count {it != ' '} + 1
        val cycleFrames = sweepFrames * 2
        val phase = Math.floorMod(frame, cycleFrames)
        val edge = phase % sweepFrames
        val before = if (phase < sweepFrames) NamedTextColor.YELLOW else NamedTextColor.GOLD
        val after = if (phase < sweepFrames) NamedTextColor.GOLD else NamedTextColor.YELLOW
        var letter = 0
        var result = Component.empty()
        heading.forEach { ch ->
            val color = when {
                ch != ' ' && letter == edge -> NamedTextColor.WHITE
                letter < edge -> before
                else -> after
            }
            result = result.append(Component.text(ch.toString(), color)
                .decoration(TextDecoration.BOLD, true))
            if (ch != ' ') letter++
        }
        return result
    }

    fun intervalColor(seconds: Int, choices: List<Int>): NamedTextColor =
        if (seconds < 0) NamedTextColor.LIGHT_PURPLE
        else listOf(NamedTextColor.GREEN, NamedTextColor.YELLOW, NamedTextColor.GOLD, NamedTextColor.RED)
            .getOrElse(choices.indexOf(seconds)) { NamedTextColor.WHITE }

    fun spawnSetting(label: String, enabled: Boolean, seconds: Int, choices: List<Int>, minutes: Boolean): Component {
        val value = when {
            !enabled -> "Off"
            seconds < 0 -> "Random"
            minutes -> "${seconds / 60}m"
            else -> "${seconds}s"
        }
        return Component.text("$label: ", NamedTextColor.WHITE).append(Component.text(value,
            if (enabled) intervalColor(seconds, choices) else NamedTextColor.RED))
    }
}
