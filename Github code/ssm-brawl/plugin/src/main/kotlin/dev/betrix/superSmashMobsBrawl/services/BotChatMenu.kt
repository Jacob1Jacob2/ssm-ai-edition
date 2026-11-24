package dev.betrix.superSmashMobsBrawl.services

import dev.betrix.superSmashMobsBrawl.SuperSmashMobsBrawl
import dev.betrix.superSmashMobsBrawl.gui.BrawlGui.Companion.openInventory
import dev.betrix.superSmashMobsBrawl.gui.brawlGui
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bot Chat, out of Misc Settings: who writes what the bots say (the script, the small model, the
 * big one, or whichever of the two the video card has room for), whether the word filter is on,
 * and the words the owner has added to it ([ChatModelService] does what is picked here).
 *
 * It is the server's and not a player's: there is one model for everybody, on the owner's
 * computer, and whoever could switch the filter off would be switching it off for everybody. So
 * it opens for operators only.
 */
object BotChatMenu : KoinComponent, Listener {
    private val plugin: SuperSmashMobsBrawl by inject()
    private val plain = PlainTextComponentSerializer.plainText()
    /** Who has been asked to type a word for the filter, and until when the next thing they type is that word. */
    private val typing = ConcurrentHashMap<UUID, Long>()
    const val TITLE = "Bot Chat"
    const val WORDS_TITLE = "Your Words"

    fun setup() { Bukkit.getPluginManager().registerEvents(this, plugin) }

    /** A test's stand-in for an operator (`bots misctest`), so that no test touches the server's real list of them. */
    val standIns = HashSet<UUID>()

    fun allowed(player: Player) = player.isOp || player.uniqueId in standIns

    private fun text(line: String, color: NamedTextColor) = Component.text(line, color).decoration(TextDecoration.ITALIC, false)
    private fun item(material: Material, name: Component, lore: List<String>, glint: Boolean = false) = ItemStack.of(material).apply {
        editMeta { it.displayName(name); it.lore(lore.map { line -> text(line, NamedTextColor.GRAY) }) }
        setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, glint)
    }
    private fun say(player: Player, line: String) =
        player.sendMessage(Component.text("Bot Chat> ", NamedTextColor.BLUE).append(Component.text(line, NamedTextColor.GRAY)))
    private fun click(player: Player, good: Boolean = true) = player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, if (good) 1.4f else 0.7f)

    /** The button that leads here, as Misc Settings shows it to [player]. */
    fun button(player: Player) = item(Material.OAK_SIGN, text("Bot Chat", NamedTextColor.YELLOW),
        listOf("Who writes what the bots say", if (allowed(player)) "Click to open" else "Server operators only"))

    fun open(player: Player) {
        if (!allowed(player)) { click(player, false); say(player, "Only the server's operators can change the bot chat."); return }
        val model = ChatModelService
        player.openInventory(brawlGui(Component.text(TITLE), 27, selectionEffects = true) {
            onClick { isCancelled = true }
            // What is writing right now: a pick takes a few seconds to load.
            // (On a server with no model there is nothing loading: the script is all there is.)
            val now = model.loaded() ?: if (model.writer == ChatModelService.Writer.SCRIPT || !model.has(model.writer)) "the script" else "the script (model loading)"
            set(4, item(Material.NAME_TAG, text("Writing now: $now", NamedTextColor.AQUA), emptyList()))
            fun writer(slot: Int, material: Material, choice: ChatModelService.Writer, name: String, about: List<String>) {
                val there = model.has(choice); val picked = there && model.writer == choice
                val title = text(name, if (!there) NamedTextColor.DARK_GRAY else if (picked) NamedTextColor.GREEN else NamedTextColor.YELLOW)
                set(slot, item(material, title, if (there) about else listOf("Not installed on this server"), picked)) {
                    if (!there) { click(player, false); return@set }
                    model.pick(choice); click(player)
                    open(player)
                    // (And again once it has had time to load, so that "writing now" is true.)
                    for (delay in listOf(60L, 160L)) Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        if (player.isOnline && plain.serialize(player.openInventory.title()) == TITLE) open(player)
                    }, delay)
                }
            }
            writer(10, Material.BOOK, ChatModelService.Writer.SCRIPT, "Script", listOf("Hand-written lines only", "Works on any computer"))
            writer(11, Material.IRON_INGOT, ChatModelService.Writer.SMALL, "Small Model", listOf("Answers what you type to bots", "About 3 GB of video memory"))
            writer(12, Material.DIAMOND, ChatModelService.Writer.BIG, "Big Model", listOf("Writes everything the bots say", "About 7 GB of video memory"))
            writer(13, Material.REPEATER, ChatModelService.Writer.AUTO, "Auto", listOf("Big model while there is room", "Small one when a game needs it"))

            val on = model.filtering
            set(15, item(Material.PAPER, text("Word Filter: ", NamedTextColor.YELLOW).append(text(if (on) "On" else "Off", if (on) NamedTextColor.GREEN else NamedTextColor.RED)),
                listOf(if (on) "Bots do not swear" else "Bots may swear; slurs stay out", "Click to turn ${if (on) "off" else "on"}"), on)) {
                model.filtering(!on); click(player, !on); open(player)
            }
            set(16, item(Material.WRITABLE_BOOK, text("Add Word", NamedTextColor.YELLOW), listOf("Block a word of your own", "Click, then type it in chat"))) {
                click(player)
                typing[player.uniqueId] = System.currentTimeMillis() + TYPING_MS
                player.closeInventory()
                say(player, "Type the word to block in chat (only you will see it), or type cancel.")
            }
            val words = model.ownWords()
            set(17, item(Material.BOOKSHELF, text("Your Words", NamedTextColor.YELLOW), listOf("${words.size} added", "Click to see or remove"))) {
                click(player); openWords(player)
            }
            set(18, item(Material.ARROW, text("Back", NamedTextColor.YELLOW), emptyList())) { MiscSettings.openMenu(player) }
        })
    }

    /** The words the owner added, each one a click away from being taken out again. */
    fun openWords(player: Player) {
        if (!allowed(player)) return
        val words = ChatModelService.ownWords()
        player.openInventory(brawlGui(Component.text(WORDS_TITLE), 54, selectionEffects = true) {
            onClick { isCancelled = true }
            if (words.isEmpty()) set(22, item(Material.BARRIER, text("None yet", NamedTextColor.GRAY), listOf("Add one with Add Word")))
            words.take(ChatModelService.MOST_WORDS).forEachIndexed { slot, word ->
                set(slot, item(Material.PAPER, text(word, NamedTextColor.WHITE), listOf("Click to remove"))) {
                    ChatModelService.removeWord(word); click(player, false); openWords(player)
                }
            }
            set(45, item(Material.ARROW, text("Back", NamedTextColor.YELLOW), emptyList())) { open(player) }
        })
    }

    /**
     * The word an operator was asked for. It is theirs alone to see: it is a word they do not want
     * said, so it is not said to the lobby on its way into the filter either, and the bots do not
     * hear it.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun chat(event: AsyncChatEvent) {
        val until = typing.remove(event.player.uniqueId) ?: return
        if (System.currentTimeMillis() > until) return
        event.isCancelled = true
        val typed = plain.serialize(event.message())
        val id = event.player.uniqueId
        Bukkit.getScheduler().runTask(plugin, Runnable { Bukkit.getPlayer(id)?.let { typed(it, typed) } })
    }

    /** [player] typed [word] for the filter. */
    fun typed(player: Player, word: String) {
        if (!allowed(player)) return
        if (word.trim().equals("cancel", true)) say(player, "Nothing added.")
        else ChatModelService.addWord(word)?.let { say(player, "The bots will not say \"$it\".") }
            ?: say(player, if (ChatModelService.ownWords().size >= ChatModelService.MOST_WORDS) "That is ${ChatModelService.MOST_WORDS} words already: remove one first."
                else "That cannot be added: letters, numbers and spaces only, 2 to ${ChatModelService.MOST_LETTERS} long.")
        open(player)
    }

    /** How long after clicking Add Word the next thing typed is taken for the word. */
    private const val TYPING_MS = 60_000L
}
