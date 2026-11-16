package dev.betrix.superSmashMobsBrawl.utils

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor.*
import kotlin.math.roundToInt

object CombatFeedback {
    /** Eight lives have no single colour: see [livesText]. */
    fun livesColor(lives:Int)=when {
        lives>=7 -> WHITE
        lives==6 -> LIGHT_PURPLE
        lives==5 -> AQUA
        lives==4 -> GREEN
        lives==3 -> YELLOW
        lives==2 -> GOLD
        else -> RED
    }
    /** [text] in the colour of that many lives. Eight lives are a rainbow, which [shift] moves along. */
    fun livesText(text:String,lives:Int,shift:Int=0):Component=
        if(lives>=8) rainbow(text,shift) else Component.text(text,livesColor(lives))
    fun rainbow(text:String,shift:Int=0):Component {
        var line:Component=Component.empty()
        text.forEachIndexed { i,ch ->
            val hue=Math.floorMod((i-shift)*30,360)/360f
            line=line.append(Component.text(ch.toString(),net.kyori.adventure.text.format.TextColor.color(
                net.kyori.adventure.util.HSVLike.hsvLike(hue,.75f,1f))))
        }
        return line
    }
    fun livesTitle(lives:Int):Component=when {
        lives<=0 -> Component.text("You Died",RED)
        lives==1 -> Component.text("1 life left!",livesColor(lives))
        else -> livesText("$lives lives left!",lives)
    }
    /** Display the accepted damage rounded to the nearest whole point. */
    fun damageText(amount:Double):String {
        require(amount.isFinite() && amount>=0)
        return amount.roundToInt().toString()
    }
    fun condition(hidden:Boolean)=Component.text("Condition> ",BLUE)
        .append(Component.text(if(hidden)"You are now invisible." else "You are no longer invisible.",GRAY))
}
