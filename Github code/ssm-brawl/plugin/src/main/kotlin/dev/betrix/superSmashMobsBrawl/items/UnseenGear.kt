package dev.betrix.superSmashMobsBrawl.items

import io.papermc.paper.datacomponent.DataComponentTypes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.kyori.adventure.key.Key
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * What an invisible player sees of themself from outside (third person): nothing.
 *
 * To everybody else an invisible fighter is simply not there: [ItemService.hide] takes the whole
 * of them away from every other player, held item, armour and name included. The player's own game
 * is another matter. It still draws its own figure; the body goes with the invisibility, but what
 * is in the hand and what armour is worn are drawn regardless, exactly as for someone who has drunk
 * an invisibility potion. All the server has a say in is what those items look like.
 *
 * So, for as long as the player is unseen, everything in the hotbar is given a model that is the
 * item's own everywhere except in a hand or on a head seen from outside, where it is nothing, and
 * the armour a look with no layers to draw. The items stay what they are and where they are: the
 * hotbar and the inventory show them as ever, and so does the hand in first person.
 *
 * (The other way to do it, telling the player's game that the slot is empty, is what used to make
 * the held item vanish from the hotbar for as long as the invisibility lasted.)
 */
object UnseenGear {
    private const val PREFIX = "unseen_minecraft_"
    /** The armour look with nothing in it (see [assets]). */
    private val NO_LAYERS: Key = Key.key("ssm", "unseen")
    /** Where an unseen item is not drawn: in either hand, or worn on the head, of a figure seen from outside. */
    val HIDDEN_IN = listOf("thirdperson_righthand", "thirdperson_lefthand", "head")
    /** The hotbar, the four pieces of armour and the off hand. */
    private val SLOTS = (0..8) + (36..40)

    /** The vanilla items the pack has an unseen model for: every one it has a selected icon of. */
    private val known: Set<String> by lazy { vanilla().keys }

    /** Item id -> its selected-icon definition from the jar, whose first layer is the item's own model. */
    private fun vanilla(): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        val start = "assets/ssm/items/selected_minecraft_"
        java.util.zip.ZipInputStream(requireNotNull(javaClass.getResourceAsStream("/pack/selection-vanilla.zip"))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.startsWith(start) && entry.name.endsWith(".json"))
                    found[entry.name.removePrefix(start).removeSuffix(".json")] = zip.readBytes().decodeToString()
            }
        }
        return found
    }

    /**
     * The unseen copy of an item's model, made from its selected-icon definition (the item's own
     * model with the selection drawn over it): the item's own model again, but nothing at all in
     * the places of [HIDDEN_IN]. Swapping to it does not dip the hand, so going invisible does not
     * look like changing items.
     */
    fun unseen(selected: String): String? {
        val own = (((Json.parseToJsonElement(selected) as? JsonObject)?.get("model") as? JsonObject)?.get("models") as? JsonArray)?.firstOrNull() ?: return null
        return buildJsonObject {
            put("hand_animation_on_swap", false)
            putJsonObject("model") {
                put("type", "minecraft:select")
                put("property", "minecraft:display_context")
                putJsonArray("cases") {
                    addJsonObject {
                        putJsonArray("when") { HIDDEN_IN.forEach { add(it) } }
                        putJsonObject("model") { put("type", "minecraft:empty") }
                    }
                }
                put("fallback", own)
            }
        }.toString()
    }

    /** The pack's share: an unseen model for every vanilla item, and the armour look with no layers. */
    fun assets(files: MutableMap<String, ByteArray>) {
        for ((id, selected) in vanilla()) unseen(selected)?.let { files["assets/ssm/items/$PREFIX$id.json"] = it.toByteArray() }
        // A look has to have a layer for something. This one has one for a wolf, which nobody
        // here is: on a person (body or legs) it has nothing to draw.
        files["assets/ssm/equipment/unseen.json"] = """{"layers":{"wolf_body":[{"texture":"minecraft:armadillo_scute"}]}}""".toByteArray()
    }

    private fun isUnseen(model: Key?) = model != null && model.namespace() == "ssm" && model.value().startsWith(PREFIX)
    /** An item whose own model the pack has an unseen copy of: the plain one, or the Flying Car's (a minecart that does not dip). */
    private fun plain(model: Key?, own: Key) = model == own || model == ItemPresentation.CAR_LOOK

    /**
     * [item] as an unseen fighter carries it, or null when it is that already (or cannot be: an
     * item with a model of its own that the pack has no unseen copy of is left as it is).
     */
    fun worn(item: ItemStack, armour: Boolean): ItemStack? {
        val own = item.type.asItemType()?.getDefaultData(DataComponentTypes.ITEM_MODEL) ?: return null
        val now = item.getData(DataComponentTypes.ITEM_MODEL)
        val want = Key.key("ssm", PREFIX + own.value())
        var copy: ItemStack? = null
        // (An unseen model for another item: the item was turned into this one since, a tool upgraded say.)
        if (own.namespace() == "minecraft" && own.value() in known && now != want && (plain(now, own) || isUnseen(now)))
            copy = item.clone().also { it.setData(DataComponentTypes.ITEM_MODEL, want) }
        if (armour) item.getData(DataComponentTypes.EQUIPPABLE)?.takeIf { it.assetId() != null && it.assetId() != NO_LAYERS }?.let { look ->
            copy = (copy ?: item.clone()).also { it.setData(DataComponentTypes.EQUIPPABLE, look.toBuilder().assetId(NO_LAYERS).build()) }
        }
        return copy
    }

    /** [item] with its own looks back, or null when it has them. */
    fun seen(item: ItemStack): ItemStack? {
        val model = isUnseen(item.getData(DataComponentTypes.ITEM_MODEL))
        val look = item.getData(DataComponentTypes.EQUIPPABLE)?.assetId() == NO_LAYERS
        if (!model && !look) return null
        return item.clone().also {
            if (model) it.resetData(DataComponentTypes.ITEM_MODEL)
            if (look) it.resetData(DataComponentTypes.EQUIPPABLE)
        }
    }

    /** Everything [player] holds or wears gets its unseen look. Cheap to call every tick: it only touches what has not got it. */
    fun wear(player: Player) {
        val inventory = player.inventory
        for (slot in SLOTS) {
            val item = inventory.getItem(slot)?.takeIf { !it.type.isAir } ?: continue
            worn(item, slot in 36..39)?.let { inventory.setItem(slot, it) }
        }
    }

    /** And everything gets its own looks back, wherever in the inventory it has got to. */
    fun shed(player: Player) {
        val inventory = player.inventory
        for (slot in 0..40) {
            val item = inventory.getItem(slot)?.takeIf { !it.type.isAir } ?: continue
            seen(item)?.let { inventory.setItem(slot, it) }
        }
    }

    /** Debug: what each slot looks like to the game right now. */
    fun describe(player: Player): String = SLOTS.mapNotNull { slot ->
        val item = player.inventory.getItem(slot)?.takeIf { !it.type.isAir } ?: return@mapNotNull null
        val model = item.getData(DataComponentTypes.ITEM_MODEL)?.asString()
        val look = item.getData(DataComponentTypes.EQUIPPABLE)?.assetId()?.asString()
        "$slot:${item.type.name.lowercase()}[$model${if (look != null) ", worn as $look" else ""}]"
    }.joinToString(" ")
}
