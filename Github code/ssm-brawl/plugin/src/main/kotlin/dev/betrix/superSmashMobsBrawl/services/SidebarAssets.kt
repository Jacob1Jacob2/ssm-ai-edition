package dev.betrix.superSmashMobsBrawl.services

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

object SidebarAssets {
    fun addTo(files: MutableMap<String, ByteArray>) {
        val ascii = SidebarAssets::class.java.getResourceAsStream("/pack/ascii.png").use { ImageIO.read(requireNotNull(it)) }
        // An extra transparent row per cell allows ascent 9 without scaling the letters.
        // Heading was already up 1px; it now moves up exactly one additional pixel.
        val padded = BufferedImage(128,144,BufferedImage.TYPE_INT_ARGB)
        for(row in 0 until 16)for(y in 0 until 8)for(x in 0 until 128)
            padded.setRGB(x,row*9+y,ascii.getRGB(x,row*8+y))
        files["assets/ssm/textures/font/sidebar_ascii.png"] = png(padded)
        for(font in listOf("sidebar_heading","sidebar_player","sidebar_footer"))
            files["assets/ssm/font/$font.json"] = SidebarAssets::class.java.getResourceAsStream("/pack/$font.json")!!.use { it.readBytes() }

        val divider = BufferedImage(9,8,BufferedImage.TYPE_INT_ARGB)
        // The original CP437 double line, widened by a single pixel per segment.
        for(y in 0 until 8)for(x in 0 until 9)divider.setRGB(x,y,ascii.getRGB(13*8+x*8/9,12*8+y))
        files["assets/ssm/textures/font/sidebar_divider.png"] = png(divider)
        // The client reserves ": " plus 2px before score numbers. Negative advance lets
        // the last segment occupy that otherwise blank gutter, without adding a 14th =.
        files["assets/ssm/font/sidebar_divider.json"] = """{"providers":[
            {"type":"space","advances":{"\uefff":-8}},
            {"type":"bitmap","file":"ssm:font/sidebar_divider.png","ascent":7,"height":8,"chars":["═"]},
            {"type":"reference","id":"minecraft:default"}]}""".toByteArray()
        SidebarOverflow.addAssets(files)
    }
    private fun png(image: BufferedImage) = ByteArrayOutputStream().also {ImageIO.write(image,"png",it)}.toByteArray()
}
