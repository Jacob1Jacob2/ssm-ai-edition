package dev.betrix.superSmashMobsBrawl.utils

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor.*
import kotlin.math.roundToInt

object CombatFeedback {
    fun livesColor(lives:Int)=when {
        lives>=4 -> GREEN
        lives==3 -> YELLOW
        lives==2 -> GOLD
        else -> RED
    }
    fun livesTitle(lives:Int):Component=when {
        lives<=0 -> Component.text("You Died",RED)
        lives==1 -> Component.text("1 life left!",livesColor(lives))
        else -> Component.text("$lives lives left!",livesColor(lives))
    }
    /** Display the accepted damage rounded to the nearest whole point. */
    fun damageText(amount:Double):String {
        require(amount.isFinite() && amount>=0)
        return amount.roundToInt().toString()
    }
    fun condition(hidden:Boolean)=Component.text("Condition> ",BLUE)
        .append(Component.text(if(hidden)"You are now invisible." else "You are no longer invisible.",GRAY))
}
