package dev.betrix.superSmashMobsBrawl.abilities

import dev.betrix.superSmashMobsBrawl.items.ItemPresentation
import dev.betrix.superSmashMobsBrawl.kits.BrawlKit
import dev.betrix.superSmashMobsBrawl.services.DataService
import dev.betrix.superSmashMobsBrawl.services.KitMenuLayout
import dev.betrix.superSmashMobsBrawl.services.KitPresentation
import dev.betrix.superSmashMobsBrawl.services.KitService
import dev.betrix.superSmashMobsBrawl.services.LangService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** What a borrowed ability still has left, and how it differs from the same ability in its own kit. */
class Borrowed(val sourceKit: String, var uses: Int, val total: Int, val cooldownScale: Double, val slot: Int) {
    /** The server tick the tool was handed out in. */
    var since = 0
    /** Clicks up to this tick are still the click that opened the bag (see [MysteryRules.stillBagClick]). */
    var quietUntil = 0
}

/** The rules of the Mystery Ability item, with nothing of the server in them. */
object MysteryRules {
    /** Abilities that only make sense inside their own kit. */
    val EXCLUDED = setOf("villager_arts", "trade_scatter", "phantom_flight", "fish_gun")

    /** Seven casts; the two that spray many small shots per click count every shot instead. */
    fun uses(abilityId: String) = when (abilityId) { "blizzard" -> 50; "inferno" -> 75; else -> 7 }

    /** Half the usual cooldown, except Slime Rocket, which keeps its own. */
    fun cooldownScale(abilityId: String) = if (abilityId == "slime_rocket") 1.0 else 0.5

    /**
     * A mouse button held down sends its click again every 4 ticks, and nobody lets go of a
     * button in less than that. So the click that opened the bag arrived a second time with the
     * new tool already in the hand, and used it. A click on a borrowed tool is that same click
     * for as long as clicks keep coming this close together...
     */
    const val QUIET_TICKS = 7
    /** ...but no longer than this after the bag was opened: a button held this long is meant. */
    const val QUIET_MOST = 15

    fun open(lent: Borrowed, now: Int) { lent.since = now; lent.quietUntil = now + QUIET_TICKS }

    /** Is a click at tick [now] still the one that opened the bag? (Counts it, if so.) */
    fun stillBagClick(lent: Borrowed, now: Int): Boolean {
        if (now > lent.quietUntil) return false
        lent.quietUntil = minOf(now + QUIET_TICKS, lent.since + QUIET_MOST)
        return true
    }

    /**
     * (kit, ability) pairs a player in [ownKit] can draw: every ability of every other kit, less
     * the excluded ones, the ones their own kit has too, and the ones they are [holding] already.
     */
    fun candidates(kits: Map<String, List<String>>, ownKit: String, holding: Set<String>): List<Pair<String, String>> {
        val own = kits[ownKit].orEmpty().toSet()
        return kits.filterKeys { it != ownKit }.flatMap { (kit, abilities) -> abilities.map { kit to it } }
            .filter { (_, ability) -> ability !in EXCLUDED && ability !in own && ability !in holding }
            .distinctBy { it.second }
    }
}

/** Mystery Ability: a random ability of another kit, as a copper tool with a few uses. */
object MysteryAbility : KoinComponent {
    private val data: DataService by inject()
    private val lang: LangService by inject()
    private val MARK = NamespacedKey("ssm", "borrowed_ability")
    /** Which ability a tool belongs to, as [BrawlAbility] writes it on every ability tool. */
    private val ABILITY get() = NamespacedKey(dev.betrix.superSmashMobsBrawl.items.ItemService.plugin, "abilityId")
    private val TOOL = Regex("(?:WOODEN|STONE|IRON|GOLDEN|DIAMOND|NETHERITE)_(SWORD|AXE|SHOVEL|PICKAXE|HOE)")

    fun isBorrowed(item: ItemStack?) = item?.itemMeta?.persistentDataContainer?.has(MARK, PersistentDataType.BYTE) == true

    private fun kits(): Map<String, List<String>> = data.getAllKits()
        .filter { it.userFacing && it.id in KitMenuLayout.slots }.associate { kit -> kit.id to kit.abilities.map { it.id } }

    /** Everything a Mystery Ability can turn into, for the test command. */
    fun everything(): List<Pair<String, String>> = MysteryRules.candidates(kits(), "", emptySet())

    /** Swaps the Mystery Ability in the player's hand for a random borrowed tool. */
    fun grant(player: Player, forced: Pair<String, String>? = null): Boolean {
        val kit = KitService.getKitForPlayer(player) ?: return false
        val (sourceKit, id) = forced ?: MysteryRules.candidates(kits(), kit.id, kit.abilityIds().toSet()).randomOrNull() ?: return false
        val ability = BrawlKit.createAbility(id, player) ?: return false
        val uses = MysteryRules.uses(id)
        ability.borrowed = Borrowed(sourceKit, uses, uses, MysteryRules.cooldownScale(id), player.inventory.heldItemSlot).also {
            // (A bot presses a button for exactly one tick: its next click is a new one.)
            if (!dev.betrix.superSmashMobsBrawl.bots.BotManager.isBot(player)) MysteryRules.open(it, org.bukkit.Bukkit.getCurrentTick())
            else it.quietUntil = -1
        }
        kit.borrow(ability)
        player.sendMessage(Component.text("Item> ", NamedTextColor.BLUE)
            .append(Component.text("Mystery Ability: ", NamedTextColor.GOLD))
            .append(lang.t("abilities.$id.name").color(lang.kitColor(sourceKit)))
            .append(Component.text(" ($uses uses)", NamedTextColor.GRAY)))
        player.playSound(player.location, org.bukkit.Sound.ITEM_BUNDLE_DROP_CONTENTS, 1f, 1f)
        player.playSound(player.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, .4f, 1.6f)
        return true
    }

    /**
     * The drop key on a borrowed tool: the player is done with it and wants the hand free for
     * another item. The ability goes with the tool. False when [item] is not theirs to throw away.
     */
    fun discard(player: Player, item: ItemStack): Boolean {
        if (!isBorrowed(item)) return false
        val kit = KitService.getKitForPlayer(player) ?: return false
        val id = item.itemMeta?.persistentDataContainer?.get(ABILITY, PersistentDataType.STRING) ?: return false
        val ability = kit.getAbility(id)?.takeIf { it.borrowed != null } ?: return false
        kit.release(ability)
        player.sendMessage(Component.text("Item> ", NamedTextColor.BLUE)
            .append(Component.text("Dropped ", NamedTextColor.GRAY))
            .append(lang.t("abilities.$id.name").color(NamedTextColor.GOLD)))
        return true
    }

    /** The tool for a borrowed ability: copper where the kit has iron, so it cannot be mistaken for a kit tool. */
    fun tool(lent: Borrowed, id: String): ItemStack {
        val base = KitPresentation.abilityItem(lent.sourceKit, id)
        val copper = TOOL.matchEntire(base.type.name)?.let { Material.matchMaterial("COPPER_" + it.groupValues[1]) }
        return (if (copper != null) base.withType(copper) else base.clone()).apply {
            editMeta { meta ->
                meta.persistentDataContainer.set(MARK, PersistentDataType.BYTE, 1)
                // A bow, a pumpkin or a feather has no copper version: it glints instead.
                if (copper == null) meta.setEnchantmentGlintOverride(true)
            }
            showUses(this, lent)
        }
    }

    fun showUses(item: ItemStack, lent: Borrowed) {
        item.editMeta { meta ->
            val lore = (meta.lore() ?: emptyList()).filterNot { PLAIN.serialize(it).startsWith("Mystery Ability") }
            val pace = if (lent.cooldownScale < 1.0) "half cooldown" else "normal cooldown"
            meta.lore(lore + Component.text("Mystery Ability · ${lent.uses} uses left · $pace", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false))
        }
        ItemPresentation.durability(item, lent.uses, lent.total)
    }
    private val PLAIN = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
}
