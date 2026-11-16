package dev.betrix.superSmashMobsBrawl.services

import com.github.shynixn.mccoroutine.bukkit.minecraftDispatcher
import com.sun.net.httpserver.HttpServer
import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.bots.BotManager
import dev.betrix.superSmashMobsBrawl.models.brawlData.GameMapDef
import gg.flyte.twilight.event.event
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kyori.adventure.key.Key
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * SSM AI Edition server resource pack, generated at startup and served by the plugin itself.
 *
 * Contents:
 * - every map's picture as a font glyph, so the vote menu can show it in the item tooltip
 * - yellow and purple eyes of ender (random old / random new map buttons)
 * - a lava block item model (Volcano's icon; lava has no item form in vanilla)
 *
 * Settings: plugins/<plugin>/resource-pack.properties (port, bind address, public URL).
 */
object ResourcePackService : KoinComponent {
    private val plugin: SuperSmashMobsBrawl by inject()

    /** Font that holds the map pictures. */
    val MAP_FONT: Key = Key.key("ssm", "maps")
    val SIDEBAR_HEADING_FONT: Key = Key.key("ssm", "sidebar_heading")
    val SIDEBAR_FOOTER_FONT: Key = Key.key("ssm", "sidebar_footer")
    val SIDEBAR_PLAYER_FONT: Key = Key.key("ssm", "sidebar_player")
    val SIDEBAR_DIVIDER_FONT: Key = Key.key("ssm", "sidebar_divider")
    val GREEN_EYE_MODEL: Key = Key.key("ssm", "eye_green")
    val YELLOW_EYE_MODEL: Key = Key.key("ssm", "eye_yellow")
    val PURPLE_EYE_MODEL: Key = Key.key("ssm", "eye_purple")
    val REVERSE_EYE_MODEL: Key = Key.key("ssm", "eye_reverse")
    val LAVA_BLOCK_MODEL: Key = Key.key("ssm", "lava_block")

    /** Display height of a map picture in the tooltip (GUI pixels). Lore lines are 10 px tall. */
    const val PICTURE_HEIGHT = 80
    const val PICTURE_BLANK_LINES = 8

    private const val PACK_FORMAT_MAJOR = 97 // Minecraft 26.3 (resource_major 97, minor 1)
    private const val PACK_FORMAT_MINOR = 1
    /** Picture texture height in pixels. The tooltip shows it ~600px tall on a 4K screen. */
    private const val TEXTURE_HEIGHT = 480

    private val packId: UUID = UUID.nameUUIDFromBytes("ssm-ai-edition-pack".toByteArray())

    private var http: HttpServer? = null
    private var publicUrl = ""
    @Volatile private var packBytes: ByteArray? = null
    @Volatile private var packSha1: String? = null
    private val glyphs = ConcurrentHashMap<String, Char>()
    private val loadedPlayers = ConcurrentHashMap.newKeySet<UUID>()

    private val dataFolder: File
        get() = File(plugin.dataFolder, "resourcepack")

    fun glyphFor(mapId: String): Char? = glyphs[mapId]

    /** True once this player's client has actually loaded the pack. */
    fun hasPack(player: Player): Boolean = player.uniqueId in loadedPlayers

    fun setup() {
        val settings = loadSettings()
        if (settings.getProperty("enabled", "true").toBoolean()) {
            val port = settings.getProperty("port", "25566").toInt()
            val bind = settings.getProperty("bind", "0.0.0.0")
            publicUrl =
                settings.getProperty("public-url", "").ifBlank { "http://localhost:$port/ssm-pack.zip" }
            runCatching {
                    http =
                        HttpServer.create(InetSocketAddress(bind, port), 0).apply {
                            createContext("/") { ex ->
                                val bytes = packBytes
                                if (bytes == null || ex.requestURI.path != "/ssm-pack.zip") {
                                    ex.sendResponseHeaders(404, -1)
                                } else {
                                    ex.responseHeaders.add("Content-Type", "application/zip")
                                    ex.sendResponseHeaders(200, if (ex.requestMethod == "HEAD") -1 else bytes.size.toLong())
                                    if (ex.requestMethod != "HEAD") ex.responseBody.use { it.write(bytes) }
                                }
                                ex.close()
                            }
                            start()
                        }
                    plugin.logger.info("Resource pack server listening on $bind:$port (players get $publicUrl)")
                }
                .onFailure { plugin.logger.severe("Could not start resource pack server on port $port: ${it.message}") }
        }

        event<PlayerJoinEvent> {
            if (BotManager.isBot(player)) return@event
            Bukkit.getScheduler().runTaskLater(plugin, Runnable { send(player) }, 20L)
        }
        event<PlayerQuitEvent> { loadedPlayers.remove(player.uniqueId) }
        event<PlayerResourcePackStatusEvent> {
            if (id != packId) return@event
            when (status) {
                PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED -> loadedPlayers.add(player.uniqueId)
                PlayerResourcePackStatusEvent.Status.DECLINED,
                PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD,
                PlayerResourcePackStatusEvent.Status.FAILED_RELOAD,
                PlayerResourcePackStatusEvent.Status.INVALID_URL,
                PlayerResourcePackStatusEvent.Status.DISCARDED -> loadedPlayers.remove(player.uniqueId)
                else -> {}
            }
        }
    }

    fun teardown() {
        http?.stop(0)
        http = null
    }

    private fun loadSettings(): Properties {
        val file = File(plugin.dataFolder, "resource-pack.properties")
        val props = Properties()
        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeText(
                """
                # SSM AI Edition resource pack (map pictures, custom icons).
                # The plugin serves the pack itself over HTTP on this port.
                enabled=true
                port=25566
                bind=0.0.0.0
                # URL players download the pack from. Blank = http://localhost:<port>/ssm-pack.zip
                # (only works when playing on the same PC). On a host, use http://<server ip>:<port>/ssm-pack.zip
                public-url=
                """.trimIndent() + "\n"
            )
        }
        file.inputStream().use { props.load(it) }
        return props
    }

    fun send(player: Player) {
        val sha1 = packSha1 ?: return
        if (http == null || !player.isOnline) return
        val info = ResourcePackInfo.resourcePackInfo(packId, URI.create("$publicUrl?v=${sha1.take(12)}"), sha1)
        player.sendResourcePacks(
            ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .required(false)
                .replace(true)
                .prompt(Component.text("Shows map pictures, menu icons, and classic scoreboard spacing.", NamedTextColor.GOLD))
                .build()
        )
    }

    // ------------------------------------------------------------------ building

    /** Rebuilds the pack for these maps and re-sends it to everyone online if it changed. */
    suspend fun rebuild(maps: List<Pair<GameMapDef, File?>>) {
        val (bytes, newGlyphs) = withContext(Dispatchers.IO) { build(maps) }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        glyphs.clear()
        glyphs.putAll(newGlyphs)
        val changed = sha1 != packSha1
        packBytes = bytes
        packSha1 = sha1
        plugin.logger.info(
            "Resource pack built: ${newGlyphs.size} map pictures, ${bytes.size / 1024} KB, sha1 ${sha1.take(12)}"
        )
        if (changed) {
            withContext(plugin.minecraftDispatcher) {
                Bukkit.getOnlinePlayers().filter { !BotManager.isBot(it) }.forEach { send(it) }
            }
        }
    }

    private fun build(maps: List<Pair<GameMapDef, File?>>): Pair<ByteArray, Map<String, Char>> {
        val files = sortedMapOf<String, ByteArray>()
        val newGlyphs = mutableMapOf<String, Char>()
        dev.betrix.superSmashMobsBrawl.smash.SmashAssets.addTo(files)
        MenuAssets.addTo(files)
        ScaledTreePosts.addAssets(files)
        ScaledMapDetails.addAssets(files)
        EnergyHud.addAssets(files)
        dev.betrix.superSmashMobsBrawl.projectiles.ProjectileUpgradeVisual.addAssets(files)
        dev.betrix.superSmashMobsBrawl.items.ItemPresentation.addAssets(files)

        files["pack.mcmeta"] =
            """{"pack":{"description":"SSM AI Edition","min_format":[$PACK_FORMAT_MAJOR,0],"max_format":[$PACK_FORMAT_MAJOR,$PACK_FORMAT_MINOR]}}"""
                .toByteArray()

        // Map pictures -> one bitmap glyph each in the ssm:maps font.
        val providers = mutableListOf<String>()
        var next = 0xE000
        maps.sortedBy { it.first.id }.forEach { (map, picture) ->
            val png = picture?.let { scaledPicture(map.id, it) } ?: return@forEach
            val ch = next.toChar()
            next++
            newGlyphs[map.id] = ch
            files["assets/ssm/textures/font/maps/${map.id}.png"] = png
            val escaped = "\\u" + "%04x".format(ch.code) // JSON escape for the private-use character
            providers +=
                "{\"type\":\"bitmap\",\"file\":\"ssm:font/maps/${map.id}.png\",\"height\":$PICTURE_HEIGHT," +
                    "\"ascent\":7,\"chars\":[\"$escaped\"]}"
        }
        files["assets/ssm/font/maps.json"] = """{"providers":[${providers.joinToString(",")}]}""".toByteArray()
        SidebarAssets.addTo(files)

        // Coloured eyes of ender for the random-old / random-new buttons: the vanilla eye of ender
        // recoloured (bundled in the jar), with a drawn fallback if they're missing.
        files["assets/ssm/textures/item/eye_green.png"] = requireNotNull(plugin.getResource("pack/ender_eye.png")).readBytes()
        files["assets/ssm/textures/item/eye_yellow.png"] =
            plugin.getResource("pack/eye_yellow.png")?.readBytes()
                ?: png(eyeTexture(Color(64, 44, 0), Color(150, 110, 0), Color(222, 176, 20), Color(255, 226, 80), Color(110, 78, 0), Color(30, 20, 0), Color(255, 250, 210)))
        files["assets/ssm/textures/item/eye_purple.png"] =
            plugin.getResource("pack/eye_purple.png")?.readBytes()
                ?: png(eyeTexture(Color(40, 10, 60), Color(95, 35, 140), Color(150, 70, 210), Color(205, 145, 255), Color(70, 25, 105), Color(18, 0, 30), Color(245, 230, 255)))
        files["assets/ssm/textures/item/eye_reverse.png"] =
            MenuAssets.transformEye("eye_reverse", 0x82E0BC)
        for (eye in listOf("eye_green", "eye_yellow", "eye_purple", "eye_reverse")) {
            files["assets/ssm/models/item/$eye.json"] =
                """{"parent":"minecraft:item/generated","textures":{"layer0":"ssm:item/$eye"},${MenuAssets.CENTERED_EYE_GUI}}""".toByteArray()
            files["assets/ssm/items/$eye.json"] =
                """{"model":{"type":"minecraft:model","model":"ssm:item/$eye"}}""".toByteArray()
        }

        // A lava block you can hold (Volcano's icon).
        files["assets/ssm/models/item/lava_block.json"] =
            """{"parent":"minecraft:block/cube_all","textures":{"all":"minecraft:block/lava_still"}}""".toByteArray()
        files["assets/ssm/items/lava_block.json"] =
            """{"model":{"type":"minecraft:model","model":"ssm:item/lava_block"}}""".toByteArray()

        SelectionIcons.assets(files)

        // Deterministic zip (fixed timestamps, sorted entries) so the hash only changes with content.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (path, data) ->
                val entry = ZipEntry(path)
                entry.time = 0L
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray() to newGlyphs
    }

    /** Downscaled copy of a map picture, cached on disk (the originals are 4K screenshots). */
    private fun scaledPicture(mapId: String, source: File): ByteArray? {
        val cache = File(dataFolder, "pictures-$TEXTURE_HEIGHT/$mapId.png")
        if (cache.exists() && cache.lastModified() >= source.lastModified()) return cache.readBytes()
        val original = runCatching { ImageIO.read(source) }.getOrNull() ?: return null
        val height = TEXTURE_HEIGHT
        val width = (original.width.toDouble() * height / original.height).toInt().coerceAtLeast(1)
        // Halve step by step first (one big bicubic jump from 4K aliases badly), then finish.
        var current: BufferedImage = original
        while (current.height / 2 >= height) {
            current = resize(current, current.width / 2, current.height / 2)
        }
        val bytes = png(resize(current, width, height))
        cache.parentFile.mkdirs()
        cache.writeBytes(bytes)
        return bytes
    }

    private fun resize(src: BufferedImage, width: Int, height: Int): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(src, 0, 0, width, height, null)
        g.dispose()
        return out
    }

    private fun png(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    /** A 16x16 eye-of-ender-style orb in the given colours (original pixel art, drawn in code). */
    private fun eyeTexture(
        outline: Color,
        dark: Color,
        mid: Color,
        light: Color,
        rim: Color,
        pupil: Color,
        highlight: Color,
    ): BufferedImage {
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 16) for (x in 0 until 16) {
            val dx = x + 0.5 - 8.0
            val dy = y + 0.5 - 8.0
            val d = sqrt(dx * dx + dy * dy)
            val color =
                when {
                    d > 6.6 -> null
                    d > 5.6 -> outline
                    (x == 5 && y == 4) || (x == 6 && y == 4) || (x == 5 && y == 5) -> highlight
                    (dx / 1.25) * (dx / 1.25) + (dy / 3.2) * (dy / 3.2) <= 1.0 -> pupil
                    (dx / 2.2) * (dx / 2.2) + (dy / 4.1) * (dy / 4.1) <= 1.0 -> rim
                    else -> {
                        val shade = (-dx * 0.55 - dy * 0.85) / 5.6
                        when {
                            shade > 0.4 -> light
                            shade > -0.2 -> mid
                            else -> dark
                        }
                    }
                }
            if (color != null) img.setRGB(x, y, color.rgb)
        }
        return img
    }
}
