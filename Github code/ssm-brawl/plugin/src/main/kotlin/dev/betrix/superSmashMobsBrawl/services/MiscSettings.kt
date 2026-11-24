package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.gui.BrawlGui.Companion.openInventory
import dev.betrix.superSmashMobsBrawl.gui.brawlGui
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The odds and ends a player can switch for themselves, from the Misc Settings button of Game
 * Settings: their Auto L (kept by [AutoL], as before), whether a bot may rage quit a match they
 * host, and whether they get the server's resource pack. Each has a default, and only what a
 * player changed is written down.
 */
object MiscSettings {
    enum class Option(val key: String, val default: Boolean) {
        /** In a match this player hosts, a bot having a bad game may walk out of it, now and then (see bots/RageQuit). */
        RAGE_QUITS("rage-quits", false),
        /** This player is sent the server's resource pack (see [ResourcePackService]). */
        RESOURCE_PACK("resource-pack", true),
    }

    private val chosen = ConcurrentHashMap<Pair<UUID, Option>, Boolean>()
    private var file: File? = null

    fun on(player: UUID, option: Option): Boolean = chosen[player to option] ?: option.default

    fun set(player: UUID, option: Option, value: Boolean) {
        chosen[player to option] = value
        save()
    }

    /** Reads what everybody chose out of [folder], and keeps it there from now on. */
    fun setup(folder: File) {
        val saved = File(folder, FILE)
        file = saved
        chosen.clear()
        if (!saved.exists()) return
        val props = Properties()
        saved.inputStream().use { props.load(it) }
        for (name in props.stringPropertyNames()) {
            val id = runCatching { UUID.fromString(name.substringBefore('.')) }.getOrNull() ?: continue
            val option = Option.entries.find { it.key == name.substringAfter('.') } ?: continue
            chosen[id to option] = props.getProperty(name).toBoolean()
        }
    }

    /** Tests: nobody has chosen anything, and nothing is written anywhere. */
    internal fun forget() { chosen.clear(); file = null }

    private fun save() {
        val target = file ?: return
        val props = Properties()
        chosen.forEach { (who, on) -> props.setProperty("${who.first}.${who.second.key}", on.toString()) }
        target.parentFile?.mkdirs()
        target.outputStream().use { props.store(it, "SSM AI Edition - what each player switched in Misc Settings") }
    }

    /** A player's choices: kept out of the public backup (see .gitignore). */
    const val FILE = "misc-settings.properties"

    // ------------------------------------------------------------------ the menu

    private fun button(material: Material, name: String, on: Boolean, about: String) = ItemStack.of(material).apply {
        editMeta {
            it.displayName(Component.text("$name: ", NamedTextColor.YELLOW)
                .append(Component.text(if (on) "On" else "Off", if (on) NamedTextColor.GREEN else NamedTextColor.RED))
                .decoration(TextDecoration.ITALIC, false))
            it.lore(listOf(about, "Click to turn ${if (on) "off" else "on"}").map { line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false) })
        }
        setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, on)
    }

    fun openMenu(player: Player) {
        val id = player.uniqueId
        fun click(on: Boolean) = player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, if (on) 1.4f else 0.9f)
        player.openInventory(brawlGui(Component.text("Misc Settings"), 27, selectionEffects = true) {
            onClick { isCancelled = true }
            val autoL = AutoL.on(id)
            set(10, button(Material.NAME_TAG, "Auto L", autoL, "Says L in chat after each of your kills")) {
                click(AutoL.toggle(id)); openMenu(player)
            }
            val quits = on(id, Option.RAGE_QUITS)
            set(12, button(Material.IRON_DOOR, "Bot Rage Quits", quits, "Once in a while a bot leaves mid-match")) {
                set(id, Option.RAGE_QUITS, !quits); click(!quits); openMenu(player)
            }
            val pack = on(id, Option.RESOURCE_PACK)
            set(14, button(Material.PAINTING, "Resource Pack", pack, "Map pictures and menu icons")) {
                set(id, Option.RESOURCE_PACK, !pack); click(!pack)
                // (The pack is taken off or asked for now, not at the next join.)
                ResourcePackService.apply(player)
                openMenu(player)
            }
            // (The server's, not this player's: who writes the bots' chat, and its word filter. See [BotChatMenu].)
            set(16, BotChatMenu.button(player)) { BotChatMenu.open(player) }
            set(18, ItemStack.of(Material.ARROW).apply {
                editMeta { it.displayName(Component.text("Back", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)) }
            }) { GameSetupService.openMenu(player) }
        })
    }
}
