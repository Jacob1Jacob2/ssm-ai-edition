package dev.betrix.superSmashMobsBrawl.utils

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class ClassicSidebarTest : StringSpec({
    "intro colors swap exactly three times per second" {
        for(second in 0..3) {
            val ticks=(second*20+1)..((second+1)*20)
            ticks.count {ClassicSidebar.introTitle(it)!=ClassicSidebar.introTitle(it-1)} shouldBe 3
        }
        ClassicSidebar.introTitle(0).color() shouldBe NamedTextColor.GOLD
        ClassicSidebar.introTitle(6).color() shouldBe NamedTextColor.GOLD
        ClassicSidebar.introTitle(7).color() shouldBe NamedTextColor.WHITE
        ClassicSidebar.introTitle(14).color() shouldBe NamedTextColor.GOLD
        ClassicSidebar.introTitle(20).color() shouldBe NamedTextColor.WHITE
    }
    "countdown heading has its own complete animation cycle" {
        val heading=ClassicSidebar.COUNTDOWN_TITLE
        val length=2*(heading.count {it!=' '}+1)
        for(tick in 0L..200L)
            PlainTextComponentSerializer.plainText().serialize(ClassicSidebar.title(ClassicSidebar.frameAtTick(tick,heading),heading)) shouldBe "AI Edition"
        ClassicSidebar.frameAtTick(length*3L,heading) shouldBe 0
        PlainTextComponentSerializer.plainText().serialize(ClassicSidebar.title(0)) shouldBe "SUPER SMASH MOBS"
    }
    fun colored(frame: Int, color: NamedTextColor) = ClassicSidebar.title(frame).children().filter { it.color() == color }
        .joinToString("") { (it as TextComponent).content() }
    "one white letter leads alternating yellow and gold wipes" {
        val letters = "SUPERSMASHMOBS"
        val pass = letters.length + 1
        for (frame in letters.indices) {
            colored(frame, NamedTextColor.WHITE) shouldBe letters[frame].toString()
            colored(frame, NamedTextColor.YELLOW).replace(" ", "") shouldBe letters.take(frame)
            colored(frame, NamedTextColor.GOLD).replace(" ", "") shouldBe letters.drop(frame + 1)
            colored(frame + pass, NamedTextColor.WHITE) shouldBe letters[frame].toString()
            colored(frame + pass, NamedTextColor.GOLD).replace(" ", "") shouldBe letters.take(frame)
            colored(frame + pass, NamedTextColor.YELLOW).replace(" ", "") shouldBe letters.drop(frame + 1)
        }
        colored(pass - 1, NamedTextColor.YELLOW) shouldBe "SUPER SMASH MOBS"
        colored(2 * pass - 1, NamedTextColor.GOLD) shouldBe "SUPER SMASH MOBS"
        for (frame in 0 until 2 * pass)
            PlainTextComponentSerializer.plainText().serialize(ClassicSidebar.title(frame)) shouldBe "SUPER SMASH MOBS"
    }
    "the video sweep advances every three ticks with a full-color frame at each end" {
        ClassicSidebar.frameAtTick(0) shouldBe 0
        ClassicSidebar.frameAtTick(2) shouldBe 0
        ClassicSidebar.frameAtTick(3) shouldBe 1
        ClassicSidebar.frameAtTick(39) shouldBe 13
        ClassicSidebar.frameAtTick(42) shouldBe 14
        ClassicSidebar.frameAtTick(45) shouldBe 15
        ClassicSidebar.frameAtTick(90) shouldBe 0
    }
    "divider has twelve characters after the requested one-segment reduction" {
        ClassicSidebar.DIVIDER.length shouldBe 12
    }
    "enabled spawn settings show compact intervals in their menu colors" {
        val itemTimes = listOf(3, 9, 30, 90)
        val crystalTimes = listOf(60, 180, 540, 1200)
        val colors = listOf(NamedTextColor.GREEN, NamedTextColor.YELLOW, NamedTextColor.GOLD, NamedTextColor.RED)
        listOf(false, true).forEach { minutes ->
            val times = if (minutes) crystalTimes else itemTimes
            val label = if (minutes) "Smash" else "Items"
            times.forEachIndexed { i, seconds ->
                val row = ClassicSidebar.spawnSetting(label, true, seconds, times, minutes)
                PlainTextComponentSerializer.plainText().serialize(row) shouldBe
                    "$label: ${if (minutes) "${seconds / 60}m" else "${seconds}s"}"
                row.children().single().color() shouldBe colors[i]
            }
        }
    }
    "disabled and random spawn settings stay distinct" {
        val times = listOf(60, 180, 540, 1200)
        val random = ClassicSidebar.spawnSetting("Smash", true, -1, times, true)
        PlainTextComponentSerializer.plainText().serialize(random) shouldBe "Smash: Random"
        random.children().single().color() shouldBe NamedTextColor.LIGHT_PURPLE
        val off = ClassicSidebar.spawnSetting("Smash", false, -1, times, true)
        PlainTextComponentSerializer.plainText().serialize(off) shouldBe "Smash: Off"
        off.children().single().color() shouldBe NamedTextColor.RED
    }
})
