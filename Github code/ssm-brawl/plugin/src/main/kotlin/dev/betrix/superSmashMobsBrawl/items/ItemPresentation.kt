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
    }
    val foods = listOf(Material.COOKED_BEEF, Material.COOKED_PORKCHOP, Material.COOKED_CHICKEN, Material.APPLE)
    fun material(kind: ArenaItem, player: Player? = null): Material = when (kind) {
        ArenaItem.FOOD -> foods.random()
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
