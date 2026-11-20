package dev.betrix.superSmashMobsBrawl.items

import dev.betrix.superSmashMobsBrawl.services.KitService
import io.papermc.paper.datacomponent.DataComponentTypes
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

object ItemPresentation {
    fun addAssets(files: MutableMap<String,ByteArray>) {
        files["assets/ssm/sounds.json"] = ("{" + (1..4).joinToString(",") {
            "\"item.shotbow.wood$it\":{\"sounds\":[\"minecraft:step/wood$it\"]}"
        } + "}").toByteArray()
        // The Flying Car's item: a minecart that is not lowered and raised again whenever it
        // changes, as any other item in the hand is. Its fuel changes once a second.
        files["assets/ssm/items/${CAR_LOOK.value()}.json"] =
            """{"hand_animation_on_swap":false,"model":{"type":"minecraft:model","model":"minecraft:item/minecart"}}""".toByteArray()
    }
    val CAR_LOOK: net.kyori.adventure.key.Key = net.kyori.adventure.key.Key.key("ssm", "flying_car")
    /** [item] as the Flying Car is held by someone whose game has the pack: steady in the hand while its fuel bar moves. */
    fun steady(item: ItemStack, player: Player): ItemStack {
        if (dev.betrix.superSmashMobsBrawl.services.ResourcePackService.hasPack(player)) item.setData(DataComponentTypes.ITEM_MODEL, CAR_LOOK)
        return item
    }
    fun material(kind: ArenaItem, player: Player? = null): Material = when (kind) {
        ArenaItem.MONSTER_EGG -> {
            val id = player?.let { KitService.getKitForPlayer(it)?.id }
            val mob = when(id) { "sky_squid" -> "squid"; "sir_sheep" -> "sheep"; "snowman" -> "snow_golem"; "wolf" -> "wolf"; else -> id }
            mob?.let { Material.matchMaterial(it.uppercase() + "_SPAWN_EGG") } ?: Material.ZOMBIE_SPAWN_EGG
        }
        else -> kind.material
    }
    /** A bundle says "Can hold a mixed stack of items" and draws an empty box: neither belongs on an arena item. */
    fun plainTooltip(item: ItemStack) {
        if (!item.type.name.endsWith("BUNDLE")) return
        item.unsetData(DataComponentTypes.BUNDLE_CONTENTS)
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, io.papermc.paper.datacomponent.item.TooltipDisplay.tooltipDisplay()
            .addHiddenComponents(DataComponentTypes.BUNDLE_CONTENTS).build())
    }
    /** Zero damage hides the native bar until a charge has actually been used. */
    fun durability(item: ItemStack, remaining: Int, maximum: Int) {
        item.editMeta { it.isUnbreakable = false }
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 1)
        item.setData(DataComponentTypes.MAX_DAMAGE, maximum * 100 + 1)
        item.setData(DataComponentTypes.DAMAGE, (maximum - remaining.coerceIn(0,maximum)) * 100)
    }
    fun clearDurability(item: ItemStack) {
        item.resetData(DataComponentTypes.MAX_DAMAGE); item.resetData(DataComponentTypes.DAMAGE)
        item.editMeta { it.isUnbreakable = true }
    }
}
