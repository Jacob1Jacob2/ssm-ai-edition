package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.utils.SidebarLayout
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

/**
 * Lines of the match sidebar beyond the fifteen the client draws.
 *
 * The client stops at fifteen lines whatever it is sent, and a match of sixteen has more than
 * that to say with everybody one to a line. So the lines that are over are drawn as part of the
 * fifteenth: the pack has the game's letters again in fonts that sit one line lower, two lines
 * lower and so on, and a strip of the box's own see-through black to put behind each of them.
 * With gaps of exact widths to move back and forth, the fifteenth line draws itself, then a strip
 * and a line of text under the box, and another under that. To the eye the box is simply longer.
 *
 * Everything here is measured from where the fifteenth line starts: the box begins [EDGE] pixels
 * before that and ends [EDGE] pixels after the widest line.
 */
object SidebarOverflow {
    /** The box reaches this many pixels past the text on either side. */
    const val EDGE = 2
    /** What the client leaves between a line and its number: the width of ": ". */
    const val NUMBER_GAP = 6
    /** The widths the strip behind a line is put together from. */
    val PIECES = listOf(128, 64, 32, 16, 8, 4, 2, 1)
    val BACKGROUND: Key = Key.key("ssm", "sidebar_bg")
    /** Where the letters of the sidebar's lines stand: a fighter's line a pixel higher than plain text, the footer a pixel lower. */
    const val FIGHTER_ASCENT = 8
    const val PLAIN_ASCENT = 7
    const val FOOTER_ASCENT = 6
    private val ASCENTS = listOf(FOOTER_ASCENT, PLAIN_ASCENT, FIGHTER_ASCENT)

    /** The game's letters [below] lines lower than a font of this [ascent] has them. */
    fun font(ascent: Int, below: Int): Key = Key.key("ssm", "sidebar_a${ascent}_$below")
    /** The strip piece [piece] pixels wide for the line [below] lines under the last. */
    fun stripChar(below: Int, piece: Int): Char = (0xE800 + below * 16 + PIECES.indexOf(piece)).toChar()

    /** One line under the box: its text (in no font of its own), its width, the number at its end and where its letters stand. */
    data class Row(val text: Component, val width: Int, val number: Int, val ascent: Int = FIGHTER_ASCENT)

    fun numberWidth(number: Int): Int = number.toString().length * 6

    /**
     * The fifteenth line with [extra] drawn under it. [own] is the line's own text and [ownWidth]
     * its width; [boxWidth] is the width of the widest line of the whole sidebar with its number
     * (what the client makes the box as wide as). The line ends up [advance] wide to the client,
     * however far the drawing went in between.
     */
    fun host(own: Component, ownWidth: Int, advance: Int, boxWidth: Int, extra: List<Row>): Component {
        var line = Component.empty().append(own)
        var pen = ownWidth
        fun move(to: Int) { if (to != pen) { line = line.append(EnergyHud.gap(to - pen)); pen = to } }
        extra.take(SidebarLayout.MAX_OVERFLOW).forEachIndexed { index, row ->
            val below = index + 1
            // The strip: from the box's left edge to its right one, in pieces that touch.
            move(-EDGE)
            var left = boxWidth + 2 * EDGE
            for (piece in PIECES) while (left >= piece) {
                line = line.append(Component.text(stripChar(below, piece).toString(), NamedTextColor.WHITE)
                    .font(BACKGROUND).decoration(TextDecoration.BOLD, false))
                // A glyph moves the pen one pixel further than it is wide.
                pen += piece + 1
                move(pen - 1)
                left -= piece
            }
            move(0)
            line = line.append(row.text.font(font(row.ascent, below)))
            pen += row.width
            // The number stands against the right edge of the box, as the client puts the others.
            val digits = numberWidth(row.number)
            move(boxWidth + EDGE - digits)
            line = line.append(Component.text(row.number.toString(), NamedTextColor.RED).font(font(PLAIN_ASCENT, below))
                .decoration(TextDecoration.BOLD, false))
            pen += digits
        }
        move(advance)
        return line
    }

    /** Something a line draws: a run of text, a piece of strip or a number, [below] lines under the line, from pixel [from] to [to]. */
    data class Drawn(val what: String, val below: Int, val from: Int, val to: Int, val text: String)

    /**
     * Follows the pen through [line] the way the client does and says what is drawn where, and
     * how wide the client takes the whole line to be. For the tests and the debug print.
     */
    fun trace(line: Component): Pair<List<Drawn>, Int> {
        var pen = 0
        val out = ArrayList<Drawn>()
        fun walk(part: Component, inherited: Key?) {
            val font = part.font() ?: inherited
            val content = (part as? TextComponent)?.content().orEmpty()
            if (content.isNotEmpty()) when (font) {
                EnergyHud.FONT -> pen += content.sumOf { it.code - 0xF000 - 512 }
                BACKGROUND -> content.forEach { glyph ->
                    val piece = PIECES[(glyph.code - 0xE800) % 16]
                    out += Drawn("strip", (glyph.code - 0xE800) / 16, pen, pen + piece, "")
                    pen += piece + 1
                }
                else -> {
                    val below = font?.value()?.takeIf { it.startsWith("sidebar_a") }?.substringAfterLast('_')?.toIntOrNull() ?: 0
                    val width = EnergyHud.widthOf(part.children(emptyList()))
                    val number = font?.value()?.startsWith("sidebar_a${PLAIN_ASCENT}_") == true && content.all(Char::isDigit)
                    out += Drawn(if (number) "number" else "text", below, pen, pen + width, content)
                    pen += width
                }
            }
            part.children().forEach { walk(it, font) }
        }
        walk(line, null)
        return out to pen
    }

    fun addAssets(files: MutableMap<String, ByteArray>) {
        // The box is black at three tenths, the game's own shade for a sidebar.
        for (piece in PIECES) {
            val strip = BufferedImage(piece, 9, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 9) for (x in 0 until piece) strip.setRGB(x, y, 76 shl 24)
            files["assets/ssm/textures/font/sidebar_bg_$piece.png"] = ByteArrayOutputStream().also { ImageIO.write(strip, "png", it) }.toByteArray()
        }
        val letters = SidebarOverflow::class.java.getResourceAsStream("/pack/sidebar_player.json")!!.use { it.readBytes().decodeToString() }
        val ascent = Regex("\"ascent\":\\s*8")
        require(ascent.containsMatchIn(letters)) { "sidebar_player.json no longer has the ascent this is built on" }
        val strips = ArrayList<String>()
        for (below in 1..SidebarLayout.MAX_OVERFLOW) {
            // A glyph with ascent a has its top 7 - a under the top of its line; a line is 9 high.
            for (base in ASCENTS) files["assets/ssm/font/sidebar_a${base}_$below.json"] =
                letters.replace(ascent, "\"ascent\": ${base - 9 * below}").toByteArray()
            for (piece in PIECES) strips += "{\"type\":\"bitmap\",\"file\":\"ssm:font/sidebar_bg_$piece.png\",\"height\":9," +
                "\"ascent\":${7 - 9 * below},\"chars\":[\"\\u${"%04x".format(stripChar(below, piece).code)}\"]}"
        }
        files["assets/ssm/font/sidebar_bg.json"] = """{"providers":[${strips.joinToString(",")}]}""".toByteArray()
    }
}
