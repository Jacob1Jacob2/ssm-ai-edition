package dev.betrix.superSmashMobsBrawl.utils

import dev.betrix.superSmashMobsBrawl.services.EnergyHud
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.key.Key

class EnergyHudAlignmentTest : StringSpec({
    "colored XP stays 182 pixels wide while a bold Salmon Stream notice is displayed" {
        // Client BitmapProvider and SpaceProvider both add a one-pixel bold advance,
        // including blank spaces. These are vanilla advances for this exact label.
        val advance=mapOf('S' to 6,'a' to 6,'l' to 3,'m' to 6,'o' to 6,'n' to 6,
            ' ' to 4,'t' to 4,'r' to 6,'e' to 6)
        fun clientWidth(c:Component, font:Key?=null, bold:Boolean=false):Int {
            val f=c.font() ?: font
            val b=when(c.decoration(TextDecoration.BOLD)) {
                TextDecoration.State.TRUE -> true
                TextDecoration.State.FALSE -> false
                else -> bold
            }
            val own=(c as? TextComponent)?.content().orEmpty().sumOf {char ->
                val base=if(f==EnergyHud.FONT)when(char.code) {
                    0xE000 -> 183
                    in 0xE101..0xE1B6 -> char.code-0xE100+1
                    in 0xE201..0xE2B6 -> char.code-0xE200+1
                    in 0xF000..0xF400 -> char.code-0xF200
                    else -> error("Unexpected glyph")
                } else advance.getValue(char)
                base+if(b)1 else 0
            }
            return own+c.children().sumOf {clientWidth(it,f,b)}
        }
        val label=Component.text("Salmon Stream ").decorate(TextDecoration.BOLD)
        for(color in listOf(null,NamedTextColor.RED,NamedTextColor.BLUE))for(fill in 0..182) {
            clientWidth(EnergyHud.compose(fill/182f,color,label)) shouldBe 182
            clientWidth(EnergyHud.compose(fill/182f,color,Component.empty())) shouldBe 182
        }
    }
})
