package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.bots.BotManager
import dev.betrix.superSmashMobsBrawl.smash.SmashXpColor
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO

/** A single join-time pack contains every XP fill. Changing fuel/color never reloads resources. */
object EnergyHud {
    val FONT = Key.key("ssm", "energy")
    val DAMAGE_FONT = Key.key("ssm", "damage")
    private data class Notice(val text: Component, val until: Int)
    private val notices = mutableMapOf<UUID, Notice>()
    private val lastDamage = mutableMapOf<UUID, Double>()
    fun lastDamage(player:Player)=lastDamage[player.uniqueId]
    fun recordDamage(player:Player,amount:Double) {
        if(amount.isFinite() && amount>0)lastDamage[player.uniqueId]=amount
    }
    fun clearDamage(player:Player) {lastDamage.remove(player.uniqueId);player.level=0}
    private val ascii by lazy { ImageIO.read(requireNotNull(javaClass.getResourceAsStream("/pack/ascii.png"))) }
    fun message(player: Player, text: Component) { notices[player.uniqueId] = Notice(text, Bukkit.getCurrentTick() + 40) }
    fun setup(plugin: JavaPlugin) {
        gg.flyte.twilight.event.event<org.bukkit.event.player.PlayerQuitEvent> {lastDamage.remove(player.uniqueId);notices.remove(player.uniqueId)}
        gg.flyte.twilight.event.event<org.bukkit.event.player.PlayerChangedWorldEvent> {clearDamage(player)}
        gg.flyte.twilight.event.event<dev.betrix.superSmashMobsBrawl.events.BrawlDeathEvent> {clearDamage(player);notices.remove(player.uniqueId);player.sendActionBar(Component.empty())}
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            val now = Bukkit.getCurrentTick()
            notices.entries.removeIf { it.value.until < now || Bukkit.getPlayer(it.key) == null }
            Bukkit.getOnlinePlayers().filterNot(BotManager::isBot).forEach { player ->
                var text = notices[player.uniqueId]?.text ?: Component.empty()
                val damage=lastDamage[player.uniqueId]
                val number=damage?.let(dev.betrix.superSmashMobsBrawl.utils.CombatFeedback::damageText)
                if (!ResourcePackService.hasPack(player) || player.gameMode !in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE)) {
                    player.level=number?.toIntOrNull() ?: 0
                    player.sendActionBar(text)
                } else {
                    // Action-bar glyphs render after the native XP level and would cover its bottom row.
                    // Draw the rounded number with our fill, using the damage font's clearance above it.
                    player.level=0
                    player.sendActionBar(compose(player.exp, SmashXpColor.color(player), text, damage))
                }
            }
        }, 1L, 2L)
    }
    private fun space(pixels: Int) = Component.text((0xF000 + pixels.coerceIn(-512, 512) + 512).toChar().toString()).font(FONT)
    fun compose(progress: Float, color: net.kyori.adventure.text.format.TextColor?, text: Component, damage:Double?=null): Component {
        val fill = (progress.coerceIn(0f, 1f) * 182).toInt()
        var bar = Component.empty()
        if(color!=null) bar=bar.append(Component.text("\uE000",color).font(FONT)
            .decoration(TextDecoration.BOLD,false).shadowColor(net.kyori.adventure.text.format.ShadowColor.shadowColor(0))).append(space(-183))
        if (fill > 0) bar = bar.append(Component.text(((if(color==null)0xE200 else 0xE100) + fill).toChar().toString(), color ?: net.kyori.adventure.text.format.NamedTextColor.WHITE).font(FONT)
            .decoration(TextDecoration.BOLD, false).shadowColor(net.kyori.adventure.text.format.ShadowColor.shadowColor(0))).append(space(-(fill + 1)))
        if(damage!=null) {
            val number=Component.text(dev.betrix.superSmashMobsBrawl.utils.CombatFeedback.damageText(damage),
                net.kyori.adventure.text.format.TextColor.color(0x80FF20)).font(DAMAGE_FONT)
                .shadowColor(net.kyori.adventure.text.format.ShadowColor.shadowColor(0xFF000000.toInt()))
            val w=width(number);val left=91-w/2
            bar=bar.append(space(left)).append(number).append(space(-left-w))
        }
        val width = width(text)
        return bar.append(space(91 - width / 2)).append(text).append(space(91 - (width - width / 2)))
    }
    private fun width(c: Component, inheritedBold: Boolean = false): Int {
        val bold = when(c.decoration(TextDecoration.BOLD)) {
            TextDecoration.State.TRUE -> true; TextDecoration.State.FALSE -> false; else -> inheritedBold
        }
        val own = (c as? TextComponent)?.content().orEmpty().sumOf { char ->
            val w = when {
                char == ' ' -> 4
                char == '▌' -> 5
                char.code in 0..255 -> {
                    val size = ascii.width / 16; val ox = char.code % 16 * size; val oy = char.code / 16 * size
                    val last = (size - 1 downTo 0).firstOrNull { x -> (0 until size).any { y -> ascii.getRGB(ox+x,oy+y).ushr(24) != 0 } } ?: -1
                    (last + 1) * 8 / size + 1
                }
                else -> 6
            }
            // The client adds the bold advance to spaces too. Missing the two spaces in
            // "Salmon Stream " widened the composed action bar and shifted XP one pixel left.
            w + if (bold) 1 else 0
        }
        return own + c.children().sumOf { width(it, bold) }
    }
    fun addAssets(files: MutableMap<String, ByteArray>) {
        val source = ImageIO.read(requireNotNull(javaClass.getResourceAsStream("/pack/xp_progress.png")))
        fun png(img: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        files["assets/minecraft/textures/gui/sprites/hud/experience_bar_progress.png"] = png(BufferedImage(182,5,BufferedImage.TYPE_INT_ARGB))
        val providers = mutableListOf<String>()
        // Cover the native green empty track as well as the fill during a colored Smash.
        val background=BufferedImage(182,5,BufferedImage.TYPE_INT_ARGB)
        for(x in 0..181)for(y in 0..4) {
            val rgb=source.getRGB(x,y)
            val light=(maxOf(rgb shr 16 and 255,rgb shr 8 and 255,rgb and 255)*.22).toInt()
            background.setRGB(x,y,(rgb and 0xFF000000.toInt()) or (light shl 16) or (light shl 8) or light)
        }
        files["assets/ssm/textures/font/energy/background.png"]=png(background)
        providers += """{"type":"bitmap","file":"ssm:font/energy/background.png","height":5,"ascent":-36,"chars":["\uE000"]}"""
        for (fill in 1..182) {
            val image = BufferedImage(fill, 5, BufferedImage.TYPE_INT_ARGB)
            for (x in 0 until fill) for (y in 0..4) {
                val rgb = source.getRGB(x,y); val light = maxOf(rgb shr 16 and 255, rgb shr 8 and 255, rgb and 255)
                image.setRGB(x,y,(rgb and 0xFF000000.toInt()) or (light shl 16) or (light shl 8) or light)
            }
            // Untinted original pixels preserve vanilla's green highlights and dark outline.
            files["assets/ssm/textures/font/energy/vanilla_$fill.png"] = png(source.getSubimage(0,0,fill,5))
            providers += """{"type":"bitmap","file":"ssm:font/energy/vanilla_$fill.png","height":5,"ascent":-36,"chars":["${(0xE200+fill).toChar()}"]}"""
            files["assets/ssm/textures/font/energy/$fill.png"] = png(image)
            providers += """{"type":"bitmap","file":"ssm:font/energy/$fill.png","height":5,"ascent":-36,"chars":["${(0xE100+fill).toChar()}"]}"""
        }
        val advances = (-512..512).joinToString(",") { "\"${(0xF000+it+512).toChar()}\":$it" }
        providers += """{"type":"space","advances":{$advances}}"""
        files["assets/ssm/font/energy.json"] = """{"providers":[${providers.joinToString(",")}]}""".toByteArray()
        val characters="0123456789."
        val numbers=BufferedImage(characters.length*8,8,BufferedImage.TYPE_INT_ARGB)
        characters.forEachIndexed {i,char->
            val x=char.code%16*8;val y=char.code/16*8
            for(dx in 0..7)for(dy in 0..7)numbers.setRGB(i*8+dx,dy,ascii.getRGB(x+dx,y+dy))
        }
        files["assets/ssm/textures/font/damage.png"]=png(numbers)
        files["assets/ssm/font/damage.json"]="""{"providers":[{"type":"bitmap","file":"ssm:font/damage.png","height":8,"ascent":-28,"chars":["$characters"]}]}""".toByteArray()
    }
}
